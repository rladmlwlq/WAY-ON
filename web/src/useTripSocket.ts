import { useCallback, useEffect, useRef, useState } from 'react'
import {
  ApiError,
  getDepartureMessages,
  wsUrl,
  type DepartureCall,
  type DepartureMessage,
  type DeparturePerson,
  type RestAreaInfo,
} from './api'

// 서버 WebSocket (docs/app-api.md 4장): 첫 메시지 auth, 20초 ping, 끊기면 1·2·4…30초 간격으로 다시 접속
// 휴게소 안내(restarea.info, A1), 출발해요(departure.*, A2), 차 위치(trip.state)·경로(route.update),
// 경유지 요청 상태(waypoint.status)도 여기서 받는다

export interface Trip {
  tripId: string
  startedAt: string
}

export type SocketState =
  | { status: 'connecting' }
  | { status: 'online'; trip: Trip | null }
  | { status: 'retrying' }
  /** 4401 토큰 틀림·만료, 4403 운전자가 해제 → 토큰을 지우고 QR부터 */
  | { status: 'invalid'; revoked: boolean }
  /** 4409 같은 토큰으로 다른 창이 접속 → 이 창은 다시 붙지 않는다 */
  | { status: 'replaced' }

/** 출발해요 대화 화면 상태. closed가 있으면 끝난 알림(입력 잠김) */
export interface Departure extends DepartureCall {
  people: DeparturePerson[]
  /** 이번 출발 알림의 대화 (최신이 마지막). [출발해요]마다 새 대화방 */
  messages: DepartureMessage[]
  closed: 'DEPARTED' | 'CANCELLED' | 'EXPIRED' | null
  /** 보내기 실패 문구 (조금 뒤 사라짐) */
  error: string | null
}

/** trip.state (app-api.md 7장): 차 위치가 올 때마다 */
export interface CarState {
  car: { lat: number; lng: number; heading: number | null; at: string }
  gear: 'PARK' | 'DRIVE' | 'REVERSE' | 'NEUTRAL' | 'UNKNOWN' | null
  energy: { energyType: 'FUEL' | 'EV' | null; energyPct: number | null; rangeM: number | null } | null
}

export interface RoutePlace {
  name: string
  lat: number
  lng: number
}

/** route.update (7-1). path는 [lng, lat]. APPROX면 길찾기 실패라 직선 */
export interface RouteUpdate {
  routeVersion: number
  source: 'KAKAO' | 'APPROX'
  path: [number, number][]
  destination: RoutePlace | null
  waypoints: RoutePlace[]
  distanceM: number | null
  durationS: number | null
}

/** 6-1에 보내는 장소. 서버가 extId로 다시 찾아 저장한다 */
export interface ProposePlace {
  extId: string
  name: string
  lat: number
  lng: number
}

/** 7장 waypoint.status */
export interface WaypointStatus {
  requestId: number
  status: 'PENDING' | 'ACCEPTED' | 'APPLIED' | 'REJECTED' | 'FAILED' | 'EXPIRED'
  failReason: string | null
  requestedBy: string
  placeName: string
  action: 'ADD' | 'SET_DEST'
}

const PING_MS = 20_000
const MAX_RETRY_MS = 30_000
const ERROR_SHOW_MS = 3_000
const PROPOSE_TIMEOUT_MS = 10_000

export type TripSocket = SocketState & {
  /** 접근·도착한 휴게소. 떠나거나(LEFT) 세션이 끝나면 null */
  restArea: RestAreaInfo | null
  /** 열린(또는 방금 끝난) 출발 알림. 없으면 null */
  departure: Departure | null
  /** 나 (auth.ok의 me). 내 말풍선을 오른쪽에 둘 때 쓴다 */
  myPassengerId: number | null
  /** 서버로 보내기. 끊겨 있으면 false */
  send: (type: string, data: object) => boolean
  /** 끝난 출발 알림 화면 닫기 */
  dismissDeparture: () => void
  /** 차 위치·기어·에너지. 세션이 끝나면 null */
  carState: CarState | null
  /** 경로선·목적지. 세션이 끝나면 null */
  route: RouteUpdate | null
  /** 마지막 경유지 요청 상태 (누가 보냈든 모두에게 옴) */
  waypointStatus: WaypointStatus | null
  /** 6-1 경유지 제안. 성공하면 requestId, 실패하면 ApiError (WAYPOINT_FULL, TRIP_NOT_ACTIVE 등) */
  propose: (place: ProposePlace) => Promise<{ requestId: number }>
}

type Data = Record<string, unknown>

let seq = 0

export function useTripSocket(token: string): TripSocket {
  const [state, setState] = useState<SocketState>({ status: 'connecting' })
  const [restArea, setRestArea] = useState<RestAreaInfo | null>(null)
  const [departure, setDeparture] = useState<Departure | null>(null)
  const [myPassengerId, setMyPassengerId] = useState<number | null>(null)
  const [carState, setCarState] = useState<CarState | null>(null)
  const [route, setRoute] = useState<RouteUpdate | null>(null)
  const [waypointStatus, setWaypointStatus] = useState<WaypointStatus | null>(null)
  const wsRef = useRef<WebSocket | null>(null)
  // reqId를 붙여 보낸 요청(경유지 제안)의 ack/error를 찾는다 (4-2)
  const pending = useRef(new Map<string, { resolve: (data: unknown) => void; reject: (err: ApiError) => void }>())

  useEffect(() => {
    let retryTimer: ReturnType<typeof setTimeout> | undefined
    let pingTimer: ReturnType<typeof setInterval> | undefined
    let errorTimer: ReturnType<typeof setTimeout> | undefined
    let retryMs = 1000
    const stopped = { current: false }

    // 대화 화면을 열 때 이번 알림의 대화를 불러오고, 그사이 실시간으로 온 메시지와 합친다 ([출발해요]마다 새 대화방)
    const openDeparture = (call: DepartureCall, people: DeparturePerson[]) => {
      setDeparture((prev) => ({
        ...call,
        people,
        messages: prev?.callId === call.callId ? prev.messages : [],
        closed: null,
        error: null,
      }))
      getDepartureMessages(token, call.callId)
        .then(({ messages }) =>
          setDeparture((prev) => (prev && prev.callId === call.callId ? { ...prev, messages: merge(messages, prev.messages) } : prev)),
        )
        .catch(() => {
          // 지난 대화를 못 받아도 실시간 메시지는 보인다
        })
    }

    const open = () => {
      const socket = new WebSocket(wsUrl())
      wsRef.current = socket
      socket.onopen = () => socket.send(JSON.stringify({ type: 'auth', data: { token } }))
      socket.onmessage = (e) => {
        let m: { type?: string; reqId?: string; data?: Data }
        try {
          m = JSON.parse(e.data)
        } catch {
          return
        }
        const d: Data = m.data ?? {}
        // 경유지 제안의 답: 출발해요 오류 표시로 흘러가지 않게 먼저 거른다
        const waiting = m.reqId ? pending.current.get(m.reqId) : undefined
        if (waiting && (m.type === 'ack' || m.type === 'error')) {
          pending.current.delete(m.reqId!)
          if (m.type === 'ack') waiting.resolve(d)
          else waiting.reject(new ApiError(0, (d.code as string) ?? 'UNKNOWN', (d.message as string) ?? '잠시 뒤에 다시 시도해 주세요'))
          return
        }
        switch (m.type) {
          case 'auth.ok': {
            retryMs = 1000
            setState({ status: 'online', trip: (d.trip as Trip | null) ?? null })
            setMyPassengerId(((d.me as Data | undefined)?.passengerId as number | undefined) ?? null)
            clearInterval(pingTimer)
            pingTimer = setInterval(() => socket.send(JSON.stringify({ type: 'ping' })), PING_MS)
            const call = d.openCall as (DepartureCall & { people: DeparturePerson[] }) | null
            if (call) openDeparture(call, call.people)
            break
          }
          case 'trip.started':
            if (d.tripId) setState({ status: 'online', trip: { tripId: d.tripId as string, startedAt: (d.startedAt as string) ?? '' } })
            break
          case 'trip.ended':
            setState({ status: 'online', trip: null })
            setRestArea(null)
            setCarState(null)
            setRoute(null)
            break
          case 'trip.state':
            setCarState(d as unknown as CarState)
            break
          case 'route.update': {
            const next = d as unknown as RouteUpdate
            // 다시 붙을 때 서버가 같은 경로를 또 보낸다. 버전이 같으면 지도를 다시 맞추지 않게 그대로 둔다
            setRoute((prev) => (prev && prev.routeVersion === next.routeVersion ? prev : next))
            break
          }
          case 'waypoint.status':
            setWaypointStatus(d as unknown as WaypointStatus)
            break
          case 'restarea.info': {
            const phase = d.phase
            const detail = d.restArea as RestAreaInfo['restArea'] | null
            setRestArea((phase === 'APPROACH' || phase === 'ARRIVED') && detail ? { phase, restArea: detail } : null)
            break
          }
          case 'departure.called':
            openDeparture(d as unknown as DepartureCall, [])
            break
          case 'departure.status':
            setDeparture((prev) =>
              prev && prev.callId === d.callId ? { ...prev, people: d.people as DeparturePerson[] } : prev,
            )
            break
          case 'departure.message':
            setDeparture((prev) =>
              prev && prev.callId === d.callId
                ? { ...prev, messages: merge(prev.messages, [d as unknown as DepartureMessage]) }
                : prev,
            )
            break
          case 'departure.closed':
            setDeparture((prev) =>
              prev && prev.callId === d.callId ? { ...prev, closed: d.reason as Departure['closed'] } : prev,
            )
            break
          case 'error': {
            const message = (d.message as string) ?? '보내지 못했어요'
            setDeparture((prev) => (prev ? { ...prev, error: message } : prev))
            clearTimeout(errorTimer)
            errorTimer = setTimeout(() => setDeparture((prev) => (prev ? { ...prev, error: null } : prev)), ERROR_SHOW_MS)
            break
          }
        }
      }
      socket.onclose = (e) => {
        clearInterval(pingTimer)
        // 끊기면 답이 오지 않는다
        pending.current.forEach((p) => p.reject(new ApiError(0, 'NETWORK', '서버와 연결이 끊겼어요')))
        pending.current.clear()
        if (stopped.current) return
        if (e.code === 4401 || e.code === 4403) {
          setState({ status: 'invalid', revoked: e.code === 4403 })
          return
        }
        if (e.code === 4409) {
          setState({ status: 'replaced' })
          return
        }
        setState({ status: 'retrying' })
        retryTimer = setTimeout(open, retryMs)
        retryMs = Math.min(retryMs * 2, MAX_RETRY_MS)
      }
    }

    open()
    return () => {
      stopped.current = true
      clearTimeout(retryTimer)
      clearTimeout(errorTimer)
      clearInterval(pingTimer)
      wsRef.current?.close()
    }
  }, [token])

  const send = useCallback((type: string, data: object) => {
    const ws = wsRef.current
    if (!ws || ws.readyState !== WebSocket.OPEN) return false
    ws.send(JSON.stringify({ type, data }))
    return true
  }, [])

  const dismissDeparture = useCallback(() => setDeparture(null), [])

  const propose = useCallback((place: ProposePlace) => {
    return new Promise<{ requestId: number }>((resolve, reject) => {
      const ws = wsRef.current
      if (!ws || ws.readyState !== WebSocket.OPEN) {
        reject(new ApiError(0, 'NETWORK', '서버와 연결이 끊겼어요'))
        return
      }
      const reqId = `p${++seq}`
      pending.current.set(reqId, { resolve: (d) => resolve(d as { requestId: number }), reject })
      ws.send(JSON.stringify({ type: 'waypoint.propose', reqId, data: { place } }))
      setTimeout(() => {
        if (pending.current.delete(reqId)) reject(new ApiError(0, 'NETWORK', '응답이 없어요. 다시 시도해 주세요'))
      }, PROPOSE_TIMEOUT_MS)
    })
  }, [])

  return { ...state, restArea, departure, myPassengerId, send, dismissDeparture, carState, route, waypointStatus, propose }
}

/** messageId로 겹친 것을 빼고 순서대로 */
function merge(a: DepartureMessage[], b: DepartureMessage[]): DepartureMessage[] {
  const byId = new Map<number, DepartureMessage>()
  for (const m of [...a, ...b]) byId.set(m.messageId, m)
  return [...byId.values()].sort((x, y) => x.messageId - y.messageId)
}

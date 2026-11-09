import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, wsUrl, type RestAreaInfo } from './api'

// 서버 WebSocket (docs/app-api.md 4장): 첫 메시지 auth, 20초 ping, 끊기면 1·2·4…30초 간격으로 다시 접속
// 휴게소 안내(restarea.info, A1), 차 위치(trip.state)·경로(route.update)도 여기서 받는다

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

const PING_MS = 20_000
const MAX_RETRY_MS = 30_000

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

export type TripSocket = SocketState & {
  /** 접근·도착한 휴게소. 떠나거나(LEFT) 세션이 끝나면 null */
  restArea: RestAreaInfo | null
  /** 차 위치·기어·에너지. 세션이 끝나면 null */
  carState: CarState | null
  /** 경로선·목적지. 세션이 끝나면 null */
  route: RouteUpdate | null
  /** 마지막 경유지 요청 상태 (누가 보냈든 모두에게 옴) */
  waypointStatus: WaypointStatus | null
  propose: (place: ProposePlace) => Promise<{ requestId: number }>
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

export function useTripSocket(token: string): TripSocket {
  const [state, setState] = useState<SocketState>({ status: 'connecting' })
  const [restArea, setRestArea] = useState<RestAreaInfo | null>(null)
  const [carState, setCarState] = useState<CarState | null>(null)
  const [route, setRoute] = useState<RouteUpdate | null>(null)
  const [waypointStatus, setWaypointStatus] = useState<WaypointStatus | null>(null)
  // 보낸 요청의 ack/error를 reqId로 찾는다 (4-2)
  const socketRef = useRef<WebSocket | null>(null)
  const pending = useRef(new Map<string, { resolve: (data: unknown) => void; reject: (err: ApiError) => void }>())

  useEffect(() => {
    let ws: WebSocket | null = null
    let retryTimer: ReturnType<typeof setTimeout> | undefined
    let pingTimer: ReturnType<typeof setInterval> | undefined
    let retryMs = 1000
    const stopped = { current: false }

    const open = () => {
      const socket = new WebSocket(wsUrl())
      ws = socket
      socketRef.current = socket
      socket.onopen = () => socket.send(JSON.stringify({ type: 'auth', data: { token } }))
      socket.onmessage = (e) => {
        let m: { type?: string; reqId?: string; data?: { trip?: Trip | null } & Partial<Trip> & Partial<RestAreaInfo> }
        try {
          m = JSON.parse(e.data)
        } catch {
          return
        }
        const waiting = m.reqId ? pending.current.get(m.reqId) : undefined
        if (waiting && (m.type === 'ack' || m.type === 'error')) {
          pending.current.delete(m.reqId!)
          const d = (m.data ?? {}) as { code?: string; message?: string }
          if (m.type === 'ack') waiting.resolve(m.data)
          else waiting.reject(new ApiError(0, d.code ?? 'UNKNOWN', d.message ?? '잠시 뒤에 다시 시도해 주세요'))
          return
        }
        if (m.type === 'auth.ok') {
          retryMs = 1000
          setState({ status: 'online', trip: m.data?.trip ?? null })
          clearInterval(pingTimer)
          pingTimer = setInterval(() => socket.send(JSON.stringify({ type: 'ping' })), PING_MS)
        } else if (m.type === 'trip.started' && m.data?.tripId) {
          setState({ status: 'online', trip: { tripId: m.data.tripId, startedAt: m.data.startedAt ?? '' } })
        } else if (m.type === 'trip.ended') {
          setState({ status: 'online', trip: null })
          setRestArea(null)
          setCarState(null)
          setRoute(null)
        } else if (m.type === 'trip.state') {
          setCarState(m.data as unknown as CarState)
        } else if (m.type === 'route.update') {
          const next = m.data as unknown as RouteUpdate
          // 다시 붙을 때 서버가 같은 경로를 또 보낸다. 버전이 같으면 지도를 다시 맞추지 않게 그대로 둔다
          setRoute((prev) => (prev && prev.routeVersion === next.routeVersion ? prev : next))
        } else if (m.type === 'restarea.info') {
          const { phase, restArea: detail } = m.data ?? {}
          setRestArea((phase === 'APPROACH' || phase === 'ARRIVED') && detail ? { phase, restArea: detail } : null)
        } else if (m.type === 'waypoint.status') {
          setWaypointStatus(m.data as unknown as WaypointStatus)
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
      clearInterval(pingTimer)
      ws?.close()
    }
  }, [token])

  /** 6-1 경유지 제안. 성공하면 requestId, 실패하면 ApiError (WAYPOINT_FULL, TRIP_NOT_ACTIVE 등) */
  const propose = useCallback((place: ProposePlace) => {
    return new Promise<{ requestId: number }>((resolve, reject) => {
      const socket = socketRef.current
      if (!socket || socket.readyState !== WebSocket.OPEN) {
        reject(new ApiError(0, 'NETWORK', '서버와 연결이 끊겼어요'))
        return
      }
      const reqId = `p${++seq}`
      pending.current.set(reqId, { resolve: (d) => resolve(d as { requestId: number }), reject })
      socket.send(JSON.stringify({ type: 'waypoint.propose', reqId, data: { place } }))
      setTimeout(() => {
        if (pending.current.delete(reqId)) reject(new ApiError(0, 'NETWORK', '응답이 없어요. 다시 시도해 주세요'))
      }, PROPOSE_TIMEOUT_MS)
    })
  }, [])

  return { ...state, restArea, carState, route, waypointStatus, propose }
}

let seq = 0
const PROPOSE_TIMEOUT_MS = 10_000

import { useEffect, useState } from 'react'
import { wsUrl } from './api'

// 서버 WebSocket (docs/app-api.md 4장): 첫 메시지 auth, 20초 ping, 끊기면 1·2·4…30초 간격으로 다시 접속

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

export function useTripSocket(token: string): SocketState {
  const [state, setState] = useState<SocketState>({ status: 'connecting' })

  useEffect(() => {
    let ws: WebSocket | null = null
    let retryTimer: ReturnType<typeof setTimeout> | undefined
    let pingTimer: ReturnType<typeof setInterval> | undefined
    let retryMs = 1000
    let stopped = false

    const open = () => {
      const socket = new WebSocket(wsUrl())
      ws = socket
      socket.onopen = () => socket.send(JSON.stringify({ type: 'auth', data: { token } }))
      socket.onmessage = (e) => {
        let m: { type?: string; data?: { trip?: Trip | null } & Partial<Trip> }
        try {
          m = JSON.parse(e.data)
        } catch {
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
        }
      }
      socket.onclose = (e) => {
        clearInterval(pingTimer)
        if (stopped) return
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
      stopped = true
      clearTimeout(retryTimer)
      clearInterval(pingTimer)
      ws?.close()
    }
  }, [token])

  return state
}

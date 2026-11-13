// 서버 REST·WebSocket 주소와 동승자 토큰 (docs/app-api.md 0장, 3-1, 3-2)
// 로컬은 VITE_API_URL을 비워 Vite 프록시(/api, /ws)를 쓴다

const API_URL = (import.meta.env.VITE_API_URL ?? '').replace(/\/$/, '')
const TOKEN_KEY = 'wayon.passengerToken'

export function wsUrl(): string {
  const base = API_URL || location.origin
  return base.replace(/^http/, 'ws') + '/ws'
}

// 저장소가 막힌 브라우저(사생활 보호 모드 등)에서도 화면은 뜨게 한다
export function loadToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY)
  } catch {
    return null
  }
}

export function saveToken(token: string) {
  try {
    localStorage.setItem(TOKEN_KEY, token)
  } catch {
    // 저장 못 하면 이번 탭에서만 연결된다
  }
}

export function clearToken() {
  try {
    localStorage.removeItem(TOKEN_KEY)
  } catch {
    // 무시
  }
}

/** 서버 오류 응답 {"code", "message"}. message는 화면에 그대로 보여도 되는 문장 */
export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.status = status
    this.code = code
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  let res: Response
  try {
    res = await fetch(API_URL + path, init)
  } catch {
    throw new ApiError(0, 'NETWORK', '서버에 연결하지 못했어요. 잠시 뒤에 다시 시도해 주세요')
  }
  if (!res.ok) {
    const body = await res.json().catch(() => null)
    throw new ApiError(res.status, body?.code ?? 'UNKNOWN', body?.message ?? '잠시 뒤에 다시 시도해 주세요')
  }
  return res.json() as Promise<T>
}

export interface Connected {
  passengerToken: string
  passengerId: number
  nickname: string
  tokenExpiresAt: string
}

export interface Me {
  passengerId: number
  nickname: string
  pushEnabled: boolean
  tokenExpiresAt: string
}

/** 3-7 휴게소 상세. restarea.info의 restArea도 같은 모양 */
export interface RestAreaDetail {
  restAreaId: string
  name: string
  routeName: string
  direction: string
  topItems: { rank: number; name: string; store: string | null; price: number | null }[]
  /** 판매 순위 기준 달 (예: 2026-10) */
  topMonth: string | null
  brands: { name: string; open: string | null; close: string | null; openNow: boolean | null }[]
  facilities: { code: string; name: string; detail: string | null }[]
}

/** 7장 restarea.info (A1). LEFT면 카드를 닫는다 */
export interface RestAreaInfo {
  phase: 'APPROACH' | 'ARRIVED'
  restArea: RestAreaDetail
}

/** 출발해요 (A2). 사람 1명의 마지막 빠른 답장. null이면 응답 없음 */
export interface DeparturePerson {
  passengerId: number
  nickname: string
  lastQuickCode: QuickCode | null
  pushEnabled: boolean
}

export type QuickCode = 'TOILET' | 'IN_LINE' | 'COMING' | 'TAKE_TIME' | 'HURRY'

/** 2-5, 7장 departure.message. sender.passengerId가 null이면 운전자 */
export interface DepartureMessage {
  messageId: number
  callId: number
  sender: { passengerId: number | null; nickname: string }
  kind: 'QUICK' | 'TEXT'
  quickCode: QuickCode | null
  body: string | null
  sentAt: string
}

/** 열린 출발 알림 (departure.called, auth.ok의 openCall) */
export interface DepartureCall {
  callId: number
  calledAt: string
  etaS: number | null
  destName: string | null
}

/** 2-5 한 출발 알림의 대화 (최신이 마지막). [출발해요]마다 새 대화방 */
export function getDepartureMessages(token: string, callId: number) {
  const query = `?callId=${callId}&size=50`
  return request<{ messages: DepartureMessage[]; hasMore: boolean }>(`/api/vehicles/me/departure-messages${query}`, {
    headers: { Authorization: `Bearer ${token}` },
  })
}

/** 3-3 */
export function getVapidKey() {
  return request<{ publicKey: string }>('/api/push/vapid-public-key')
}

/** 3-4. 브라우저 PushSubscription.toJSON() 그대로 */
export async function savePushSubscription(token: string, subscription: PushSubscriptionJSON) {
  await requestNoBody('/api/passengers/me/push-subscription', {
    method: 'PUT',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(subscription),
  })
}

/** 3-5 */
export async function deletePushSubscription(token: string) {
  await requestNoBody('/api/passengers/me/push-subscription', {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${token}` },
  })
}

/** 204처럼 본문 없는 응답 */
async function requestNoBody(path: string, init: RequestInit) {
  let res: Response
  try {
    res = await fetch(API_URL + path, init)
  } catch {
    throw new ApiError(0, 'NETWORK', '서버에 연결하지 못했어요. 잠시 뒤에 다시 시도해 주세요')
  }
  if (!res.ok) {
    const body = await res.json().catch(() => null)
    throw new ApiError(res.status, body?.code ?? 'UNKNOWN', body?.message ?? '잠시 뒤에 다시 시도해 주세요')
  }
}

/** 3-1 QR로 연결 */
export function connect(code: string, nickname: string, locationConsent: boolean) {
  return request<Connected>('/api/connect', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code, nickname, locationConsent }),
  })
}

/** 3-10 검색 결과 1개. url은 카카오맵 모바일 웹(앱 없이 열림). restAreaId가 있으면 휴게소 안 매장 */
export interface SearchPlace {
  id: string
  name: string
  category: string | null
  phone: string | null
  address: string | null
  lat: number
  lng: number
  distM: number | null
  url: string
  restAreaId: string | null
}

const auth = (token: string) => ({ headers: { Authorization: `Bearer ${token}` } })

/** 3-10 차 위치 근처 장소 검색 (카카오) */
export function searchPlaces(token: string, query: string) {
  return request<{ places: SearchPlace[] }>(`/api/search/places?query=${encodeURIComponent(query)}`, auth(token))
}

/** 3-7 휴게소 상세 (메뉴·영업 중 매장) */
export function getRestArea(restAreaId: string) {
  return request<RestAreaDetail>(`/api/rest-areas/${encodeURIComponent(restAreaId)}`)
}

/** 3-2 토큰 확인. 401이면 토큰이 만료·해제된 것 */
export function getMe(token: string) {
  return request<Me>('/api/passengers/me', auth(token))
}

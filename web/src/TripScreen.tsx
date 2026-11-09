import { useEffect, useState } from 'react'
import { ApiError, getMe, type Me, type SearchPlace } from './api'
import { Notice } from './Notice'
import { RestAreaCard } from './RestAreaCard'
import { SearchPanel } from './SearchPanel'
import { TripMap } from './TripMap'
import { useTripSocket, type WaypointStatus } from './useTripSocket'

// 연결 완료 화면. 토큰이 있으면 열 때마다 자동 재연결 (docs/app-api.md 1장 F5, 3-2)

interface Props {
  token: string
  /** 토큰이 만료·해제됨 → 토큰을 지우고 처음 화면으로 */
  onSignedOut: (message: string) => void
}

const EXPIRED = {
  title: '연결이 만료되었습니다',
  message: '차량 화면의 QR을 다시 찍어 연결해 주세요.',
}
const REVOKED = {
  title: '연결이 해제되었습니다',
  message: '운전자가 연결을 해제했어요. 다시 연결하려면 차량 화면의 QR을 찍어 주세요.',
}

export function TripScreen({ token, onSignedOut }: Props) {
  const [me, setMe] = useState<Me | null>(null)
  const [meExpired, setMeExpired] = useState(false)
  const socket = useTripSocket(token)
  // 검색 결과는 목록(SearchPanel)과 지도 핀(TripMap)이 같이 쓴다
  const [places, setPlaces] = useState<SearchPlace[] | null>(null)
  const [selected, setSelected] = useState<SearchPlace | null>(null)

  useEffect(() => {
    getMe(token)
      .then(setMe)
      .catch((err) => {
        // 네트워크 오류는 WebSocket 재접속이 맡는다. 401만 연결 끝
        if (err instanceof ApiError && err.status === 401) setMeExpired(true)
      })
  }, [token])

  // 연결이 끝나면 화면을 막고 알림창을 띄운다. [확인]을 누르면 토큰을 지우고 처음 화면으로
  const ended = socket.status === 'invalid' ? (socket.revoked ? REVOKED : EXPIRED) : meExpired ? EXPIRED : null
  const status = describe(socket)

  return (
    <>
      <main className="screen" inert={ended !== null}>
        <header className="brand">WAY:ON</header>
        <h1>{me ? `${me.nickname}님, 연결됐어요` : '연결됐어요'}</h1>

        {socket.restArea && <RestAreaCard info={socket.restArea} />}

        {/* 경유지 요청 상태 (7장 waypoint.status): 누가 보냈든 모두에게 */}
        {socket.status === 'online' && socket.trip && socket.waypointStatus && (
          <section className="card request-status" aria-live="polite">
            <div className="status">
              <span className={`dot ${requestTone(socket.waypointStatus.status)}`} aria-hidden="true" />
              <strong>{socket.waypointStatus.placeName}</strong>
            </div>
            <p className="help">
              {socket.waypointStatus.requestedBy}님의 제안 · {requestText(socket.waypointStatus)}
            </p>
          </section>
        )}

        {socket.status === 'online' && socket.trip && (
          <section className="card map-card">
            <div>
              <strong>{socket.route?.destination ? `${socket.route.destination.name}까지` : '목적지 없음'}</strong>
              <p className="help">{summary(socket)}</p>
            </div>
            <TripMap
              carState={socket.carState}
              route={socket.route}
              places={places}
              selectedId={selected?.id ?? null}
              onSelect={setSelected}
            />
            {socket.route?.source === 'APPROX' && <p className="help">경로를 찾지 못해 직선으로 보여 드려요.</p>}
          </section>
        )}

        {socket.status === 'online' && socket.trip && (
          <SearchPanel
            token={token}
            places={places}
            selected={selected}
            onResults={setPlaces}
            onSelect={setSelected}
            propose={socket.propose}
          />
        )}

        <section className="card" aria-live="polite">
          <div className="status">
            <span className={`dot ${status.tone}`} aria-hidden="true" />
            <strong>{status.title}</strong>
          </div>
          <p className="help">{status.detail}</p>
        </section>

        {/* 알림 허용(출발해요 Web Push)은 출발해요 작업 때 붙인다. 브라우저가 버튼을 누른 뒤에만 권한을 묻는다 */}
        <section className="card">
          <strong>출발 알림 받기</strong>
          <p className="help">휴게소에서 차가 출발할 때 알려 드려요. 곧 켤 수 있어요.</p>
          <button type="button" className="secondary" disabled>
            알림 켜기 (준비 중)
          </button>
        </section>
      </main>
      {ended && <Notice title={ended.title} message={ended.message} onConfirm={() => onSignedOut(ended.message)} />}
    </>
  )
}

function requestText(s: WaypointStatus) {
  const where = s.action === 'SET_DEST' ? '목적지' : '경유지'
  switch (s.status) {
    case 'PENDING':
      return '운전자가 확인하고 있어요'
    case 'ACCEPTED':
      return `운전자가 수락했어요. 내비에 ${where}로 넣는 중`
    case 'APPLIED':
      return `${where}로 추가됐어요`
    case 'REJECTED':
      return '운전자가 이번엔 넘겼어요'
    case 'EXPIRED':
      return '응답이 없어서 취소됐어요'
    case 'FAILED':
      return s.failReason ?? '내비에 넣지 못했어요'
  }
}

function requestTone(status: WaypointStatus['status']) {
  return status === 'APPLIED' ? 'ok' : status === 'PENDING' || status === 'ACCEPTED' ? 'wait' : 'off'
}

/** "3시간 30분 · 312km · 주행 중 · 배터리 18%" (아는 것만) */
function summary(socket: ReturnType<typeof useTripSocket>) {
  const parts: string[] = []
  const route = socket.route
  if (route?.destination && route.durationS != null) parts.push(duration(route.durationS))
  if (route?.destination && route.distanceM != null) parts.push(`${Math.round(route.distanceM / 1000)}km`)
  const gear = socket.carState?.gear
  if (gear === 'PARK') parts.push('정차 중')
  else if (gear === 'DRIVE' || gear === 'REVERSE') parts.push('주행 중')
  const energy = socket.carState?.energy
  if (energy?.energyPct != null) parts.push(`${energy.energyType === 'EV' ? '배터리' : '연료'} ${energy.energyPct}%`)
  if (!socket.carState) parts.push('차 위치를 기다리는 중')
  return parts.join(' · ')
}

/** 분은 올림. 12600 → "3시간 30분" (차 화면 durationText와 같은 규칙) */
function duration(seconds: number) {
  const total = Math.max(1, Math.ceil(seconds / 60))
  const h = Math.floor(total / 60)
  const m = total % 60
  return h === 0 ? `${m}분` : m === 0 ? `${h}시간` : `${h}시간 ${m}분`
}

function describe(socket: ReturnType<typeof useTripSocket>) {
  switch (socket.status) {
    case 'online':
      return socket.trip
        ? { tone: 'ok', title: '차량과 연결됨', detail: '운전 중인 차의 경로를 함께 보고 있어요.' }
        : { tone: 'wait', title: '차량 앱을 기다리는 중', detail: '차량 앱이 켜지면 자동으로 연결돼요.' }
    case 'replaced':
      return { tone: 'off', title: '다른 창에서 연결됨', detail: '이 폰의 다른 창(탭)에서 연결을 이어받았어요.' }
    case 'retrying':
      return { tone: 'wait', title: '다시 연결하는 중', detail: '서버와 연결이 끊겼어요. 자동으로 다시 붙어요.' }
    default:
      return { tone: 'wait', title: '연결하는 중', detail: '잠시만 기다려 주세요.' }
  }
}

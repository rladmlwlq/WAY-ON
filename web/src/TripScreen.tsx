import { useEffect, useState } from 'react'
import { ApiError, getMe, type Me } from './api'
import { DepartureChat } from './DepartureChat'
import { Notice } from './Notice'
import { PushToggle } from './PushToggle'
import { RestAreaCard } from './RestAreaCard'
import { useTripSocket } from './useTripSocket'

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

        {/* 출발해요가 열려 있으면 맨 위에 (알림을 눌러 들어온 화면) */}
        {socket.departure && (
          <DepartureChat
            departure={socket.departure}
            myPassengerId={socket.myPassengerId}
            send={socket.send}
            onDismiss={socket.dismissDeparture}
          />
        )}

        {socket.restArea && <RestAreaCard info={socket.restArea} />}

        <section className="card" aria-live="polite">
          <div className="status">
            <span className={`dot ${status.tone}`} aria-hidden="true" />
            <strong>{status.title}</strong>
          </div>
          <p className="help">{status.detail}</p>
        </section>

        <PushToggle token={token} serverEnabled={me ? me.pushEnabled : null} />
      </main>
      {ended && <Notice title={ended.title} message={ended.message} onConfirm={() => onSignedOut(ended.message)} />}
    </>
  )
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

import { useEffect, useRef, useState } from 'react'
import type { DeparturePerson, QuickCode } from './api'
import type { Departure } from './useTripSocket'

// 출발해요 대화 (docs/2026-11-05-출발해요-기획.md 6-3). 운전자가 차에서 [출발해요]를 누르면 열린다.
// 위: 사람별 마지막 빠른 답장, 가운데: 말풍선(내 것은 오른쪽), 아래: 빠른 답장 3개 + 40자 메시지

const QUICK: Record<QuickCode, { icon: string; text: string }> = {
  TOILET: { icon: '🚻', text: '화장실이에요' },
  IN_LINE: { icon: '⏳', text: '줄 서 있어요' },
  COMING: { icon: '🏃', text: '가는 중이에요' },
  TAKE_TIME: { icon: '🙂', text: '천천히 와요' },
  HURRY: { icon: '⏰', text: '빨리 와요' },
}
const MY_QUICK: QuickCode[] = ['TOILET', 'IN_LINE', 'COMING']
const MAX_BODY = 40

const CLOSED_TEXT = {
  DEPARTED: '차가 출발했어요',
  CANCELLED: '운전자가 출발 알림을 취소했어요',
  EXPIRED: '출발 알림이 끝났어요 (30분)',
}

interface Props {
  departure: Departure
  myPassengerId: number | null
  send: (type: string, data: object) => boolean
  onDismiss: () => void
}

export function DepartureChat({ departure: d, myPassengerId, send, onDismiss }: Props) {
  const [text, setText] = useState('')
  const [offline, setOffline] = useState(false)
  const listEnd = useRef<HTMLLIElement>(null)
  const minutes = useMinutesSince(d.calledAt)
  const open = d.closed === null

  // 새 말풍선이 오면 맨 아래로
  useEffect(() => {
    listEnd.current?.scrollIntoView({ block: 'end' })
  }, [d.messages.length])

  // 페이지를 열어 둔 채 알림이 오면 진동 (지원하는 폰만)
  useEffect(() => {
    if (open) navigator.vibrate?.(200)
  }, [d.callId, open])

  const sendMessage = (data: object) => {
    setOffline(!send('departure.message', { callId: d.callId, ...data }))
  }

  const submitText = (e: React.FormEvent) => {
    e.preventDefault()
    const body = text.trim()
    if (!body) return
    sendMessage({ kind: 'TEXT', body })
    setText('')
  }

  return (
    <section className="card departure" aria-label="출발해요 대화">
      <div>
        <span className={`badge ${open ? '' : 'ok'}`}>{open ? `출발 대기 · ${minutes}분 지남` : '끝남'}</span>
        <h2>🚗 {open ? '곧 출발해요' : CLOSED_TEXT[d.closed!]}</h2>
        {d.destName && (
          <p className="help">
            {d.destName}
            {d.etaS ? `까지 약 ${durationText(d.etaS)}` : ''}
          </p>
        )}
      </div>

      {d.people.length > 0 && (
        <ul className="people" aria-label="사람별 상태">
          {d.people.map((p) => (
            <li key={p.passengerId}>
              <span aria-hidden="true">{personIcon(p)}</span> {p.nickname}
              {p.passengerId === myPassengerId && <span className="me"> (나)</span>}
            </li>
          ))}
        </ul>
      )}

      <ol className="bubbles" aria-live="polite">
        {d.messages.map((m, i) => {
          const mine = m.sender.passengerId !== null && m.sender.passengerId === myPassengerId
          const driver = m.sender.passengerId === null
          const newCall = i > 0 && d.messages[i - 1].callId !== m.callId
          return (
            <li key={m.messageId} className={`bubble ${mine ? 'mine' : ''} ${driver ? 'driver' : ''}`}>
              {newCall && <div className="divider">이번 출발 알림</div>}
              {!mine && <span className="who">{m.sender.nickname}</span>}
              <span className="say">
                {m.kind === 'QUICK' && m.quickCode ? `${QUICK[m.quickCode].icon} ${QUICK[m.quickCode].text}` : m.body}
              </span>
            </li>
          )
        })}
        <li ref={listEnd} aria-hidden="true" />
      </ol>

      {open ? (
        <>
          <div className="quick">
            {MY_QUICK.map((code) => (
              <button key={code} type="button" className="secondary" onClick={() => sendMessage({ kind: 'QUICK', quickCode: code })}>
                {QUICK[code].icon} {QUICK[code].text}
              </button>
            ))}
          </div>
          <form className="say-form" onSubmit={submitText}>
            <input
              value={text}
              onChange={(e) => setText(e.target.value)}
              maxLength={MAX_BODY}
              placeholder="메시지 (40자)"
              aria-label="메시지"
            />
            <button type="submit" className="primary" disabled={!text.trim()}>
              보내기
            </button>
          </form>
          {(d.error || offline) && (
            <p className="help warn" role="status">
              {d.error ?? '연결이 끊겨서 보내지 못했어요'}
            </p>
          )}
        </>
      ) : (
        <button type="button" className="secondary" onClick={onDismiss}>
          닫기
        </button>
      )}
    </section>
  )
}

/** 마지막 빠른 답장 아이콘. 답이 없으면 ⚪, 알림도 못 받으면 🔕 */
function personIcon(p: DeparturePerson) {
  if (p.lastQuickCode) return QUICK[p.lastQuickCode].icon
  return p.pushEnabled ? '⚪' : '🔕'
}

/** 30초마다 다시 센다 */
function useMinutesSince(iso: string) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 30_000)
    return () => clearInterval(t)
  }, [])
  return Math.max(0, Math.floor((now - Date.parse(iso)) / 60_000))
}

function durationText(seconds: number) {
  const total = Math.max(1, Math.ceil(seconds / 60))
  const h = Math.floor(total / 60)
  const m = total % 60
  return h === 0 ? `${m}분` : m === 0 ? `${h}시간` : `${h}시간 ${m}분`
}

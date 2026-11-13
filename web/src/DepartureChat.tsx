import { useEffect, useRef, useState } from 'react'
import type { DepartureMessage, DeparturePerson, QuickCode } from './api'
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
        <span className={`badge ${open ? '' : 'ok'}`}>
          {open ? `${timeText(d.calledAt)}에 출발 알림 · ${minutes}분 지남` : '끝남'}
        </span>
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
          const prev = d.messages[i - 1]
          const next = d.messages[i + 1]
          // 카톡처럼: 같은 사람이 같은 분에 이어 보낸 묶음에서 이름은 처음, 시간은 마지막에만
          const showName = !mine && (!prev || !sameGroup(prev, m))
          const showTime = !next || !sameGroup(m, next)
          return (
            <li key={m.messageId} className={`bubble ${mine ? 'mine' : ''} ${driver ? 'driver' : ''}`}>
              {(!prev || dayKey(prev) !== dayKey(m)) && <div className="divider">{dateText(m.sentAt)}</div>}
              {prev && prev.callId !== m.callId && <div className="divider">이번 출발 알림</div>}
              {showName && <span className="who">{m.sender.nickname}</span>}
              <div className="line">
                <span className="say">
                  {m.kind === 'QUICK' && m.quickCode ? `${QUICK[m.quickCode].icon} ${QUICK[m.quickCode].text}` : m.body}
                </span>
                {showTime && <time dateTime={m.sentAt}>{timeText(m.sentAt)}</time>}
              </div>
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

const TIME = new Intl.DateTimeFormat('ko-KR', { hour: 'numeric', minute: '2-digit' })
const DATE = new Intl.DateTimeFormat('ko-KR', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' })

/** "오후 3:05" */
function timeText(iso: string) {
  return TIME.format(new Date(iso))
}

/** "2026년 11월 9일 일요일" */
function dateText(iso: string) {
  return DATE.format(new Date(iso))
}

function dayKey(m: DepartureMessage) {
  return new Date(m.sentAt).toDateString()
}

/** 같은 출발 알림, 같은 사람, 같은 분 */
function sameGroup(a: DepartureMessage, b: DepartureMessage) {
  return (
    a.callId === b.callId &&
    a.sender.passengerId === b.sender.passengerId &&
    Math.floor(Date.parse(a.sentAt) / 60_000) === Math.floor(Date.parse(b.sentAt) / 60_000)
  )
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

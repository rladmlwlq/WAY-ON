import { useState, type FormEvent } from 'react'
import { ApiError, connect, saveToken } from './api'
import { Notice } from './Notice'

// QR 주소 /join#코드 → 닉네임 → 위치 공유 동의 → 연결 (docs/app-api.md 1장, 3-1)

const MAX_NICKNAME = 10
/** 코드를 다시 찍어야 하는 오류: 이 화면에서 다시 시도해도 소용없어 폼을 막는다 */
const DEAD_CODE: Record<string, string> = {
  CODE_EXPIRED: 'QR이 만료되었습니다',
  CODE_INVALID: 'QR을 사용할 수 없습니다',
}

interface Props {
  code: string
  onConnected: (token: string) => void
}

export function JoinScreen({ code, onConnected }: Props) {
  const [nickname, setNickname] = useState('')
  const [consent, setConsent] = useState(false)
  const [sending, setSending] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  const [noticeOpen, setNoticeOpen] = useState(false)

  const trimmed = nickname.trim()
  const length = [...trimmed].length // 이모지도 1자로 센다 (서버와 같음)
  const deadCode = error !== null && error.code in DEAD_CODE
  const canSubmit = length >= 1 && length <= MAX_NICKNAME && consent && !sending && !deadCode

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!canSubmit) return
    setSending(true)
    setError(null)
    try {
      const res = await connect(code, trimmed, consent)
      saveToken(res.passengerToken)
      history.replaceState(null, '', '/') // 주소창·방문 기록에 코드를 남기지 않는다
      onConnected(res.passengerToken)
    } catch (err) {
      const apiError = err instanceof ApiError ? err : new ApiError(0, 'UNKNOWN', '잠시 뒤에 다시 시도해 주세요')
      setError(apiError)
      setNoticeOpen(apiError.code in DEAD_CODE)
      setSending(false)
    }
  }

  return (
    <>
      <main className="screen">
        <header className="brand">WAY:ON</header>
        <h1>차량에 연결하기</h1>
        <p className="lead">운전자에게 장소를 보내고, 차의 경로를 이 폰에서 볼 수 있어요.</p>

        <form className="card" onSubmit={submit}>
          <fieldset className="plain" disabled={deadCode}>
            <label className="field">
              <span className="label">닉네임</span>
              <input
                value={nickname}
                onChange={(e) => setNickname(e.target.value)}
                placeholder="예: 민지"
                maxLength={20}
                autoComplete="nickname"
                enterKeyHint="done"
                aria-describedby="nickname-help"
              />
              <span id="nickname-help" className={length > MAX_NICKNAME ? 'help warn' : 'help'}>
                차 화면에 보이는 이름이에요 · {length}/{MAX_NICKNAME}자
              </span>
            </label>

            <label className="consent">
              <input type="checkbox" checked={consent} onChange={(e) => setConsent(e.target.checked)} />
              <span>
                <strong>위치 공유에 동의해요</strong>
                <span className="help">
                  연결된 동안 이 차의 현재 위치와 경로가 이 폰에 보여요. 이 폰의 위치는 보내지 않아요.
                </span>
              </span>
            </label>
          </fieldset>

          {error && (
            <p className="error" role="alert">
              {error.message}
              {deadCode && <span className="help">차량 화면에서 QR을 다시 띄워 찍어 주세요.</span>}
            </p>
          )}

          <button type="submit" className="primary" disabled={!canSubmit}>
            {sending ? '연결하는 중…' : '연결하기'}
          </button>
        </form>
      </main>
      {noticeOpen && error && (
        <Notice
          title={DEAD_CODE[error.code]}
          message="차량 화면에서 QR을 다시 띄워 찍어 주세요."
          onConfirm={() => setNoticeOpen(false)}
        />
      )}
    </>
  )
}

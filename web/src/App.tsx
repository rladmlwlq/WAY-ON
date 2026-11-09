import { useCallback, useState } from 'react'
import { clearToken, loadToken } from './api'
import { JoinScreen } from './JoinScreen'
import { TripScreen } from './TripScreen'
import './App.css'

// shortcut: 화면이 /join과 / 두 개라 라우터 없이 주소로 나눈다. 화면이 늘면 react-router 추가

/** QR 주소 https://{웹}/join#코드 의 코드. 코드는 서버로 가지 않게 # 뒤에 있다 */
function joinCode(): string | null {
  return location.pathname === '/join' && location.hash.length > 1 ? location.hash.slice(1) : null
}

export default function App() {
  const [code, setCode] = useState(joinCode)
  const [token, setToken] = useState(loadToken)
  const [notice, setNotice] = useState<string | null>(null)

  const connected = useCallback((newToken: string) => {
    setCode(null)
    setNotice(null)
    setToken(newToken)
  }, [])

  const signedOut = useCallback((message: string) => {
    clearToken()
    setToken(null)
    setNotice(message)
  }, [])

  // 토큰이 있어도 QR을 새로 찍으면 새로 연결한다 (한 폰은 차 1대만 기억, app-api 3-1)
  if (code) return <JoinScreen code={code} onConnected={connected} />
  if (token) return <TripScreen key={token} token={token} onSignedOut={signedOut} />

  return (
    <main className="screen">
      <header className="brand">WAY:ON</header>
      <h1>차량 화면의 QR을 찍어 주세요</h1>
      {notice && (
        <p className="notice" role="status">
          {notice}
        </p>
      )}
      <p className="lead">
        차가 서 있을 때 차량 화면에서 [동승자 연결]을 누르면 QR이 나와요. 폰 카메라로 찍으면 바로 연결돼요.
      </p>
    </main>
  )
}

import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'

// 출발해요 알림을 받는 서비스 워커 (public/sw.js). HTTPS나 localhost에서만 등록된다
if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('/sw.js').catch(() => {
    // 등록이 안 되면 알림 켜기 버튼이 "이 브라우저는 알림을 받을 수 없어요"로 보인다
  })
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)

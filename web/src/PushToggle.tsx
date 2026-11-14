import { useEffect, useState } from 'react'
import { deletePushSubscription, getVapidKey, savePushSubscription } from './api'

// 출발 알림 받기 (docs/app-api.md 3-3~3-5, 출발해요 기획 5장).
// 브라우저는 사용자가 버튼을 누른 뒤에만 알림 권한을 묻는다. 구독은 서버의 동승자 행에 저장한다.

type PushState = 'loading' | 'unsupported' | 'denied' | 'off' | 'on' | 'busy'

export function PushToggle({ token, serverEnabled }: { token: string; serverEnabled: boolean | null }) {
  const [state, setState] = useState<PushState>('loading')
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (serverEnabled === null) return
    if (!('serviceWorker' in navigator) || !('PushManager' in window) || !('Notification' in window)) {
      setState('unsupported')
      return
    }
    if (Notification.permission === 'denied') {
      setState('denied')
      return
    }
    navigator.serviceWorker.ready
      .then((reg) => reg.pushManager.getSubscription())
      .then((sub) => setState(sub && serverEnabled ? 'on' : 'off'))
      .catch(() => setState('unsupported'))
  }, [serverEnabled])

  const turnOn = async () => {
    setError(null)
    setState('busy')
    try {
      if ((await Notification.requestPermission()) !== 'granted') {
        setState('denied')
        return
      }
      const { publicKey } = await getVapidKey()
      const reg = await navigator.serviceWorker.ready
      const sub =
        (await reg.pushManager.getSubscription()) ??
        (await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: base64UrlToBytes(publicKey) }))
      await savePushSubscription(token, sub.toJSON())
      setState('on')
    } catch (e) {
      setError(e instanceof Error ? e.message : '알림을 켜지 못했어요')
      setState('off')
    }
  }

  const turnOff = async () => {
    setError(null)
    setState('busy')
    try {
      const sub = await (await navigator.serviceWorker.ready).pushManager.getSubscription()
      await sub?.unsubscribe()
      await deletePushSubscription(token)
      setState('off')
    } catch (e) {
      setError(e instanceof Error ? e.message : '알림을 끄지 못했어요')
      setState('on')
    }
  }

  return (
    <section className="card">
      <strong>출발 알림 받기</strong>
      <p className="help">{describe(state)}</p>
      {(state === 'off' || state === 'busy') && (
        <button type="button" className="primary" onClick={turnOn} disabled={state === 'busy'}>
          알림 켜기
        </button>
      )}
      {state === 'on' && (
        <button type="button" className="secondary" onClick={turnOff}>
          알림 끄기
        </button>
      )}
      {error && (
        <p className="help warn" role="status">
          {error}
        </p>
      )}
    </section>
  )
}

function describe(state: PushState) {
  switch (state) {
    case 'on':
      return '휴게소에서 차가 출발할 때 폰이 잠겨 있어도 알려 드려요.'
    case 'denied':
      return '알림이 막혀 있어요. 브라우저 설정에서 이 사이트의 알림을 허용해 주세요.'
    case 'unsupported':
      return '이 브라우저는 알림을 받을 수 없어요. iPhone은 Safari에서 [홈 화면에 추가]로 연 뒤 켤 수 있어요.'
    case 'loading':
      return '확인하는 중…'
    default:
      return '휴게소에서 차가 출발할 때 알려 드려요. 이 화면을 닫아도 받을 수 있어요.'
  }
}

/** VAPID 공개 키 (URL-safe Base64) → applicationServerKey */
function base64UrlToBytes(value: string): Uint8Array<ArrayBuffer> {
  const base64 = (value + '='.repeat((4 - (value.length % 4)) % 4)).replace(/-/g, '+').replace(/_/g, '/')
  const raw = atob(base64)
  const bytes = new Uint8Array(new ArrayBuffer(raw.length))
  for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i)
  return bytes
}

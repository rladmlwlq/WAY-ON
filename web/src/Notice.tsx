import { useEffect, useRef } from 'react'

// 화면 위 알림창. 브라우저 기본 <dialog>라 포커스 가두기·Esc·스크린리더를 브라우저가 맡는다

interface Props {
  title: string
  message: string
  onConfirm: () => void
}

export function Notice({ title, message, onConfirm }: Props) {
  const ref = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    const dialog = ref.current
    if (dialog && !dialog.open) dialog.showModal()
    return () => dialog?.close()
  }, [])

  return (
    <dialog
      ref={ref}
      className="notice-dialog"
      aria-labelledby="notice-title"
      aria-describedby="notice-message"
      // Esc로 닫아도 확인과 같게 처리한다
      onCancel={(e) => {
        e.preventDefault()
        onConfirm()
      }}
    >
      <h2 id="notice-title">{title}</h2>
      <p id="notice-message">{message}</p>
      <button type="button" className="primary" onClick={onConfirm} autoFocus>
        확인
      </button>
    </dialog>
  )
}

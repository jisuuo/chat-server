import { useState } from 'react'
import type { FormEvent, KeyboardEvent } from 'react'

type Props = { onSend: (content: string) => Promise<boolean> }

export function Composer({ onSend }: Props) {
  const [draft, setDraft] = useState('')

  async function submit() {
    if (await onSend(draft)) setDraft('')
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    void submit()
  }

  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    // ADR-113: 한글 조합 중 Enter를 전송으로 처리하면 마지막 글자가 다시 전송된다
    if (event.key !== 'Enter' || event.shiftKey || event.nativeEvent.isComposing) return
    event.preventDefault()
    if (draft.length > 0) void submit()
  }

  // ADR-085: 길이·문자 검사는 서버 한 곳에서 한다
  // ADR-034, F33: 전송 중에도 버튼을 끄지 않는다 (연속 제출 중복을 화면에서 미리 막지 않음)
  return (
    <form className="composer" onSubmit={onSubmit}>
      <textarea aria-label="메시지" rows={1} placeholder="메시지 입력 (Shift+Enter 줄바꿈)"
        value={draft} onChange={(e) => setDraft(e.target.value)} onKeyDown={onKeyDown} />
      <button type="submit" disabled={draft.length === 0}>보내기</button>
    </form>
  )
}

import { useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { createUser } from '../api/chat'
import { errorMessage } from '../api/client'
import { parseUserId } from '../session'

type Props = { onLogin: (userId: number) => void }

export function LoginPage({ onLogin }: Props) {
  const [nickname, setNickname] = useState('')
  const [idText, setIdText] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [isCreating, setIsCreating] = useState(false)
  const creating = useRef(false)
  const selected = useRef(false)

  async function startAsNew(event: FormEvent) {
    event.preventDefault()
    if (creating.current || selected.current) return
    creating.current = true
    setIsCreating(true)
    setError(null)
    try {
      const { data } = await createUser(nickname)
      // 이미 기존 id를 선택했다면 늦은 생성 응답으로 로그인을 바꾸지 않는다
      if (selected.current) return
      selected.current = true
      onLogin(data.id)
    } catch (e) {
      if (!selected.current) setError(errorMessage(e))
    } finally {
      creating.current = false
      if (!selected.current) setIsCreating(false)
    }
  }

  function startAsExisting(event: FormEvent) {
    event.preventDefault()
    if (selected.current) return
    const id = parseUserId(idText)
    if (id === null) {
      setError('사용자 id는 1 이상의 정수입니다.')
      return
    }
    selected.current = true
    onLogin(id)
  }

  return (
    <main className="login">
      <h1>chat</h1>
      <form onSubmit={startAsNew}>
        <input aria-label="닉네임" placeholder="닉네임" value={nickname} onChange={(e) => setNickname(e.target.value)} />
        <button type="submit" disabled={isCreating}>새 사용자로 시작</button>
      </form>
      <form onSubmit={startAsExisting}>
        <input aria-label="사용자 id" placeholder="seed 사용자 id" value={idText} onChange={(e) => setIdText(e.target.value)} />
        <button type="submit">이 id로 시작</button>
      </form>
      {error && <p role="alert">{error}</p>}
    </main>
  )
}

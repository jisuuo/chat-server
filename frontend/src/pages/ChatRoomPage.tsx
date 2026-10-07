import { useCallback, useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { joinRoom, leaveRoom, readMessages, sendMessage } from '../api/chat'
import { ApiError, errorMessage } from '../api/client'
import type { Message } from '../api/types'
import { PollingPanel } from '../components/PollingPanel'
import { lastId, mergeMessages } from '../messages/merge'
import { DEFAULT_POLL_INTERVAL_MS, usePolling } from '../messages/usePolling'

type Status = 'loading' | 'notMember' | 'ready' | 'error'
type Props = { userId: number; roomId: number; onBack: () => void }

export function ChatRoomPage({ userId, roomId, onBack }: Props) {
  const [status, setStatus] = useState<Status>('loading')
  const [messages, setMessages] = useState<Message[]>([])
  const [hasOlder, setHasOlder] = useState(false)
  const [draft, setDraft] = useState('')
  const [error, setError] = useState<string | null>(null)
  // 계획 4 세부 #8: 커서는 조회 응답으로만 전진한다. 내 메시지 id로 옮기면
  // 이미 커밋됐지만 아직 받지 못한 더 작은 id의 남의 메시지를 영원히 건너뛴다
  const cursorRef = useRef(0)
  const [cursorView, setCursorView] = useState(0)
  const [intervalMs, setIntervalMs] = useState(DEFAULT_POLL_INTERVAL_MS)
  const [paused, setPaused] = useState(false)
  const latestRequestRef = useRef(0)
  const listRef = useRef<HTMLOListElement>(null)

  const loadLatest = useCallback(() => {
    const requestId = ++latestRequestRef.current
    return readMessages(userId, roomId)
      .then(({ data }) => {
        // 앞선 최초 조회가 늦게 끝나도 최신 메시지와 커서를 되돌리지 않는다
        if (requestId !== latestRequestRef.current) return
        cursorRef.current = lastId(data.messages)
        setCursorView(cursorRef.current)
        setMessages(data.messages)
        setHasOlder(data.hasMore)
        setStatus('ready')
        setError(null)
      })
      .catch((e: unknown) => {
        if (requestId !== latestRequestRef.current) return
        // 계획 4 세부 #11: 멤버 여부 API가 없으므로 서버의 인가 결과로 판단한다
        if (e instanceof ApiError && e.code === 'NOT_A_MEMBER') {
          setStatus('notMember')
          return
        }
        setStatus('error')
        setError(errorMessage(e))
      })
  }, [userId, roomId])

  useEffect(() => {
    const requestRef = latestRequestRef
    void loadLatest()
    return () => { requestRef.current++ }
  }, [loadLatest])

  const poll = useCallback(async (isCurrent: () => boolean) => {
    const { data, info } = await readMessages(userId, roomId, { after: cursorRef.current })
    if (!isCurrent()) return { hasMore: false, info, count: 0 }
    if (data.messages.length > 0) {
      cursorRef.current = lastId(data.messages)
      setCursorView(cursorRef.current)
      setMessages((current) => mergeMessages(current, data.messages))
    }
    return { hasMore: data.hasMore, info, count: data.messages.length }
  }, [userId, roomId])

  const stats = usePolling({ enabled: status === 'ready' && !paused, intervalMs, poll })

  const newestId = lastId(messages)
  useEffect(() => {
    const list = listRef.current
    if (list) list.scrollTop = list.scrollHeight
  }, [newestId])

  async function join() {
    try {
      await joinRoom(userId, roomId)
    } catch (e) {
      if (!(e instanceof ApiError && e.code === 'ALREADY_MEMBER')) {
        setError(errorMessage(e))
        return
      }
    }
    await loadLatest()
  }

  async function send(event: FormEvent) {
    event.preventDefault()
    try {
      const { data } = await sendMessage(userId, roomId, draft)
      setMessages((current) => mergeMessages(current, [data]))
      setDraft('')
      setError(null)
    } catch (e) {
      setError(errorMessage(e))
    }
  }

  async function loadOlder() {
    try {
      const { data } = await readMessages(userId, roomId, { before: messages[0].id })
      setMessages((current) => mergeMessages(current, data.messages))
      setHasOlder(data.hasMore)
    } catch (e) {
      setError(errorMessage(e))
    }
  }

  async function leave() {
    try {
      await leaveRoom(userId, roomId)
      onBack()
    } catch (e) {
      setError(errorMessage(e))
    }
  }

  return (
    <section className="chat">
      <div className="toolbar">
        <button onClick={onBack}>방 목록으로</button>
        <h2>방 #{roomId}</h2>
        {status === 'ready' && <button onClick={() => void leave()}>나가기</button>}
      </div>
      {error && <p role="alert">{error}</p>}
      {status === 'loading' && <p>불러오는 중…</p>}
      {status === 'notMember' && (
        <div className="join">
          <p>이 방의 멤버가 아닙니다.</p>
          <button onClick={() => void join()}>입장</button>
        </div>
      )}
      {status === 'ready' && (
        <div className="room-body">
          <div className="conversation">
            {hasOlder && <button onClick={() => void loadOlder()}>이전 메시지 더 보기</button>}
            <ol aria-label="대화" className="messages" ref={listRef}>
              {messages.map((message) => (
                <li key={message.id}>
                  <span className="sender">사용자 #{message.senderId}</span>
                  <span className="content">{message.content}</span>
                </li>
              ))}
            </ol>
            {/* 계획 4 세부 #14: 길이·문자 검사는 서버 한 곳에서 하고 400 메시지를 보여 준다 */}
            <form onSubmit={send}>
              <input aria-label="메시지" value={draft} onChange={(e) => setDraft(e.target.value)} />
              <button type="submit" disabled={draft.length === 0}>보내기</button>
            </form>
          </div>
          <PollingPanel
            stats={stats}
            cursor={cursorView}
            intervalMs={intervalMs}
            paused={paused}
            onIntervalChange={setIntervalMs}
            onTogglePause={() => setPaused((current) => !current)}
          />
        </div>
      )}
    </section>
  )
}

import { useCallback, useEffect, useRef, useState } from 'react'
import { joinRoom, leaveRoom, readMessages, sendMessage } from '../api/chat'
import { ApiError, errorMessage } from '../api/client'
import type { Message } from '../api/types'
import { lastId, mergeMessages } from './merge'
import { DEFAULT_POLL_INTERVAL_MS, usePolling } from './usePolling'
import type { PollStats } from './usePolling'

export type RoomStatus = 'loading' | 'notMember' | 'ready' | 'error'
export type PollingControls = {
  stats: PollStats; cursor: number; intervalMs: number; paused: boolean
  setIntervalMs: (ms: number) => void; togglePause: () => void
}
export type RoomMessages = {
  status: RoomStatus
  messages: Message[]
  hasOlder: boolean
  error: string | null
  join: () => Promise<void>
  send: (content: string) => Promise<boolean>
  loadOlder: () => Promise<void>
  leave: () => Promise<boolean>
  polling: PollingControls
}

// ADR-108: P7에서 수신 방식(폴링 → WebSocket)만 바꾸도록 화면과 분리한다
export function useRoomMessages(userId: number, roomId: number): RoomMessages {
  const [status, setStatus] = useState<RoomStatus>('loading')
  const [messages, setMessages] = useState<Message[]>([])
  const [hasOlder, setHasOlder] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // ADR-083: 커서는 조회 응답으로만 전진한다. 내 메시지 id로 옮기면
  // 이미 커밋됐지만 아직 받지 못한 더 작은 id의 남의 메시지를 영원히 건너뛴다
  const cursorRef = useRef(0)
  const [cursorView, setCursorView] = useState(0)
  const [intervalMs, setIntervalMs] = useState(DEFAULT_POLL_INTERVAL_MS)
  const [paused, setPaused] = useState(false)
  const latestRequestRef = useRef(0)

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
        // ADR-084: 멤버 여부 API가 없으므로 서버의 인가 결과로 판단한다
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

  async function send(content: string) {
    try {
      const { data } = await sendMessage(userId, roomId, content)
      setMessages((current) => mergeMessages(current, [data]))
      setError(null)
      return true
    } catch (e) {
      setError(errorMessage(e))
      return false
    }
  }

  async function loadOlder() {
    if (messages.length === 0) return
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
      return true
    } catch (e) {
      setError(errorMessage(e))
      return false
    }
  }

  return {
    status, messages, hasOlder, error, join, send, loadOlder, leave,
    polling: {
      stats, cursor: cursorView, intervalMs, paused, setIntervalMs,
      togglePause: () => setPaused((current) => !current),
    },
  }
}

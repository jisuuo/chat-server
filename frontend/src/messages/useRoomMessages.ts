import { useCallback, useEffect, useRef, useState } from 'react'
import { joinRoom, leaveRoom, readMessages, sendMessage } from '../api/chat'
import { ApiError, CONNECTION_ERROR, errorMessage } from '../api/client'
import type { Message } from '../api/types'
import type { SocketStats } from '../realtime/chatSocket'
import { currentTransport } from '../realtime/transport'
import type { Transport } from '../realtime/transport'
import { useChatSocket, useSocketStats } from '../realtime/useChatSocket'
import { lastId, mergeMessages } from './merge'
import { recoverVisibleMessages } from './recover'
import { DEFAULT_POLL_INTERVAL_MS, usePolling } from './usePolling'
import type { PollStats } from './usePolling'

export type RoomStatus = 'loading' | 'notMember' | 'ready' | 'error'
export const RECONCILE_INTERVAL_MS = 60_000
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
  retry: () => Promise<void>
  send: (content: string) => Promise<boolean>
  loadOlder: () => Promise<void>
  leave: () => Promise<boolean>
  transport: Transport
  polling: PollingControls
  connection: SocketStats | null
}

// ADR-108: P7에서 수신 방식(폴링 → WebSocket)만 바꾸도록 화면과 분리한다
export function useRoomMessages(userId: number, roomId: number): RoomMessages {
  const [transport] = useState(currentTransport)
  const socket = useChatSocket()
  const connection = useSocketStats(socket)
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
  const pendingPushesRef = useRef<Message[]>([])
  const bufferPushesRef = useRef(true)
  const readyRef = useRef(false)
  const messagesRef = useRef(messages)
  const recoveredReconnectRef = useRef(0)
  const recoveryErrorRef = useRef<string | null>(null)

  useEffect(() => { messagesRef.current = messages }, [messages])

  useEffect(() => {
    if (transport !== 'websocket' || socket === null) return
    bufferPushesRef.current = true
    // 조회보다 먼저 구독해, 응답을 기다리는 동안 받은 프레임을 결과와 합친다.
    const unsubscribe = socket.subscribe((frame) => {
      if (frame.type === 'message') {
        if (frame.message.roomId !== roomId) return
        if (readyRef.current) {
          setMessages((current) => mergeMessages(current, [frame.message]))
        } else if (bufferPushesRef.current) {
          pendingPushesRef.current.push(frame.message)
        }
        return
      }
      if (frame.roomId !== null && frame.roomId !== roomId) return
      if (frame.code === 'NOT_A_MEMBER') {
        readyRef.current = false
        bufferPushesRef.current = false
        pendingPushesRef.current = []
        setStatus('notMember')
        return
      }
      setError(frame.message)
    })
    return () => {
      unsubscribe()
      readyRef.current = false
      bufferPushesRef.current = false
      pendingPushesRef.current = []
    }
  }, [transport, socket, roomId])

  const loadLatest = useCallback(() => {
    const requestId = ++latestRequestRef.current
    bufferPushesRef.current = true
    return readMessages(userId, roomId)
      .then(({ data }) => {
        // 앞선 최초 조회가 늦게 끝나도 최신 메시지와 커서를 되돌리지 않는다
        if (requestId !== latestRequestRef.current) return
        cursorRef.current = lastId(data.messages)
        setCursorView(cursorRef.current)
        const withPushes = transport === 'websocket'
          ? mergeMessages(data.messages, pendingPushesRef.current)
          : data.messages
        pendingPushesRef.current = []
        readyRef.current = true
        setMessages(withPushes)
        setHasOlder(data.hasMore)
        setStatus('ready')
        setError(null)
      })
      .catch((e: unknown) => {
        if (requestId !== latestRequestRef.current) return
        // ADR-084: 멤버 여부 API가 없으므로 서버의 인가 결과로 판단한다
        if (e instanceof ApiError && e.code === 'NOT_A_MEMBER') {
          readyRef.current = false
          bufferPushesRef.current = false
          pendingPushesRef.current = []
          setStatus('notMember')
          return
        }
        bufferPushesRef.current = false
        pendingPushesRef.current = []
        setStatus('error')
        setError(errorMessage(e))
      })
  }, [userId, roomId, transport])

  const retry = useCallback(() => {
    setStatus('loading')
    setError(null)
    return loadLatest()
  }, [loadLatest])

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

  const stats = usePolling({ enabled: transport === 'polling' && status === 'ready' && !paused, intervalMs, poll })

  useEffect(() => {
    if (transport !== 'websocket' || socket === null || status !== 'ready'
      || connection?.state !== 'open') return
    const reconnected = connection.reconnects > recoveredReconnectRef.current
    recoveredReconnectRef.current = connection.reconnects
    let active = true
    let timer: ReturnType<typeof setTimeout> | undefined
    function recover() {
      const oldestVisibleId = messagesRef.current[0]?.id ?? 0
      void recoverVisibleMessages(userId, roomId, oldestVisibleId)
        .then((recovered) => {
          if (!active) return
          setMessages((current) => mergeMessages(current, recovered))
          if (recoveryErrorRef.current !== null) {
            const previousError = recoveryErrorRef.current
            recoveryErrorRef.current = null
            setError((current) => current === previousError ? null : current)
          }
          timer = setTimeout(recover, RECONCILE_INTERVAL_MS)
        })
        .catch((e: unknown) => {
          if (!active) return
          if (e instanceof ApiError && e.code === 'NOT_A_MEMBER') {
            readyRef.current = false
            bufferPushesRef.current = false
            pendingPushesRef.current = []
            setStatus('notMember')
          } else {
            recoveryErrorRef.current = errorMessage(e)
            setError(recoveryErrorRef.current)
            timer = setTimeout(recover, 2000)
          }
        })
    }
    timer = setTimeout(recover, reconnected ? 0 : RECONCILE_INTERVAL_MS)
    return () => { active = false; clearTimeout(timer) }
  }, [transport, socket, status, connection?.state, connection?.reconnects, userId, roomId])

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
    if (transport === 'websocket') {
      // ADR-131: 응답 짝 맞춤이 없어 성공은 "보냈다"까지만 안다. 결과는 message push나 error 프레임으로 온다.
      // 전송 중 비활성화·낙관적 표시는 하지 않는다(ADR-034, F33)
      if (socket?.send(roomId, content)) {
        setError(null)
        return true
      }
      setError(CONNECTION_ERROR)
      return false
    }
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
    status, messages, hasOlder, error, join, retry, send, loadOlder, leave, transport, connection,
    polling: {
      stats, cursor: cursorView, intervalMs, paused, setIntervalMs,
      togglePause: () => setPaused((current) => !current),
    },
  }
}

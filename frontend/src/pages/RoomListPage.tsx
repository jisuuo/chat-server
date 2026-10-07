import { useCallback, useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { createRoom, listRooms } from '../api/chat'
import { errorMessage } from '../api/client'
import type { Room } from '../api/types'

type Props = { userId: number; onOpen: (roomId: number) => void }

export function RoomListPage({ userId, onOpen }: Props) {
  const [rooms, setRooms] = useState<Room[]>([])
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [hasMore, setHasMore] = useState(false)
  const [name, setName] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [isLoadingFirst, setIsLoadingFirst] = useState(true)
  const [isLoadingMore, setIsLoadingMore] = useState(false)
  const loadingFirst = useRef(true)
  const loadingMore = useRef(false)
  const generation = useRef(0)
  const requestedCursor = useRef<string | null>(null)

  // ADR-084: 자동 갱신하지 않는다. 넘기는 중 순서가 바뀐 방의 누락(F27)도 보정하지 않는다
  const loadFirst = useCallback(
    (requestGeneration: number) => listRooms(userId, null)
      .then(({ data }) => {
        if (requestGeneration !== generation.current) return
        setRooms(data.rooms)
        setNextCursor(data.nextCursor)
        setHasMore(data.hasMore)
        setError(null)
      })
      .catch((e: unknown) => {
        if (requestGeneration !== generation.current) return
        setError(errorMessage(e))
      })
      .finally(() => {
        if (requestGeneration !== generation.current) return
        loadingFirst.current = false
        setIsLoadingFirst(false)
      }),
    [userId],
  )

  useEffect(() => {
    const requestGeneration = ++generation.current
    const generationRef = generation
    void loadFirst(requestGeneration)
    return () => { generationRef.current++ }
  }, [loadFirst])

  function refresh() {
    if (loadingFirst.current) return
    loadingFirst.current = true
    loadingMore.current = false
    requestedCursor.current = null
    setIsLoadingFirst(true)
    setIsLoadingMore(false)
    void loadFirst(++generation.current)
  }

  function loadMore() {
    const cursor = nextCursor
    if (loadingFirst.current || loadingMore.current || !hasMore || cursor === null || requestedCursor.current === cursor) return
    loadingMore.current = true
    requestedCursor.current = cursor
    setIsLoadingMore(true)
    const requestGeneration = generation.current

    void listRooms(userId, cursor)
      .then(({ data }) => {
        // 새로고침 후에는 이전 커서의 응답을 현재 목록에 섞지 않는다
        if (requestGeneration !== generation.current) return
        setRooms((current) => [...current, ...data.rooms])
        setNextCursor(data.nextCursor)
        setHasMore(data.hasMore)
        setError(null)
      })
      .catch((e: unknown) => {
        if (requestGeneration !== generation.current) return
        requestedCursor.current = null
        setError(errorMessage(e))
      })
      .finally(() => {
        if (requestGeneration !== generation.current) return
        loadingMore.current = false
        setIsLoadingMore(false)
      })
  }

  async function create(event: FormEvent) {
    event.preventDefault()
    try {
      const { data } = await createRoom(userId, name)
      onOpen(data.id)
    } catch (e) {
      setError(errorMessage(e))
    }
  }

  return (
    <section className="rooms">
      <form onSubmit={create}>
        <input aria-label="방 이름" placeholder="방 이름" value={name} onChange={(e) => setName(e.target.value)} />
        <button type="submit">방 만들기</button>
      </form>
      <button onClick={refresh} disabled={isLoadingFirst}>새로고침</button>
      {error && <p role="alert">{error}</p>}
      <ul aria-label="방 목록">
        {rooms.map((room) => (
          <li key={room.id}>
            <button onClick={() => onOpen(room.id)}>{room.name}</button>
            <small>
              #{room.id} · {room.lastMessageId === null ? '메시지 없음' : `마지막 메시지 #${room.lastMessageId}`}
            </small>
          </li>
        ))}
      </ul>
      {hasMore && <button onClick={loadMore} disabled={isLoadingFirst || isLoadingMore}>더 보기</button>}
    </section>
  )
}

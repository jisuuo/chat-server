import { useCallback, useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { createRoom, listRooms } from '../api/chat'
import { errorMessage } from '../api/client'
import type { Room } from '../api/types'

type Props = { userId: number; activeRoomId: number | null; onOpen: (roomId: number) => void; onRoomsChange: (rooms: Room[]) => void }

export function RoomListPage({ userId, activeRoomId, onOpen, onRoomsChange }: Props) {
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
  const queuedRefresh = useRef(false)

  // ADR-117: 채팅방 제목을 사이드바가 읽은 목록에서 찾는다
  useEffect(() => { onRoomsChange(rooms) }, [rooms, onRoomsChange])

  // ADR-084: 자동 갱신하지 않는다. 넘기는 중 순서가 바뀐 방의 누락(F27)도 보정하지 않는다
  const loadFirst = useCallback(
    function loadFirst(requestGeneration: number) {
      return listRooms(userId, null)
        .then(({ data }) => {
        if (requestGeneration !== generation.current || queuedRefresh.current) return
        setRooms(data.rooms)
        setNextCursor(data.nextCursor)
        setHasMore(data.hasMore)
        setError(null)
        })
        .catch((e: unknown) => {
        if (requestGeneration !== generation.current || queuedRefresh.current) return
        setError(errorMessage(e))
        })
        .finally(() => {
        if (requestGeneration !== generation.current) return
        if (queuedRefresh.current) {
          queuedRefresh.current = false
          loadingMore.current = false
          requestedCursor.current = null
          setIsLoadingMore(false)
          void loadFirst(++generation.current)
          return
        }
        loadingFirst.current = false
        setIsLoadingFirst(false)
        })
    },
    [userId],
  )

  useEffect(() => {
    const requestGeneration = ++generation.current
    const generationRef = generation
    void loadFirst(requestGeneration)
    return () => { generationRef.current++ }
  }, [loadFirst])

  function refresh() {
    if (loadingFirst.current) {
      // ADR-116: 방 생성이 첫 조회보다 먼저 끝나면 그 조회 뒤 새 목록을 다시 읽는다
      queuedRefresh.current = true
      return
    }
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
      setName('')
      // ADR-116: 사이드바에 만든 방이 보이도록 첫 페이지부터 다시 읽는다
      refresh()
      onOpen(data.id)
    } catch (e) {
      setError(errorMessage(e))
    }
  }

  return (
    <aside className="sidebar" aria-label="방">
      <form className="new-room" onSubmit={create}>
        <input aria-label="방 이름" placeholder="새 방 이름" value={name} onChange={(e) => setName(e.target.value)} />
        <button type="submit">방 만들기</button>
      </form>
      <div className="sidebar-actions">
        <button onClick={refresh} disabled={isLoadingFirst}>새로고침</button>
      </div>
      {error && <p role="alert" className="error">{error}</p>}
      <ul aria-label="방 목록" className="room-list">
        {rooms.map((room) => (
          <li key={room.id}>
            <button aria-current={room.id === activeRoomId ? 'page' : undefined} onClick={() => onOpen(room.id)}>{room.name}</button>
          </li>
        ))}
      </ul>
      {hasMore && <button className="more" onClick={loadMore} disabled={isLoadingFirst || isLoadingMore}>더 보기</button>}
    </aside>
  )
}

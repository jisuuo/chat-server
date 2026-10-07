import { useState } from 'react'
import type { Room } from './api/types'
import { ChatRoomPage } from './pages/ChatRoomPage'
import { LoginPage } from './pages/LoginPage'
import { RoomListPage } from './pages/RoomListPage'
import { ROOMS_HASH, roomHash, useHashRoute } from './route'
import { clearUserId, loadUserId, saveUserId } from './session'
import { NicknameProvider } from './users/useNicknames'

export default function App() {
  const [userId, setUserId] = useState<number | null>(loadUserId)
  const [rooms, setRooms] = useState<Room[]>([])
  const route = useHashRoute()

  if (userId === null) {
    return (
      <LoginPage
        onLogin={(id) => {
          saveUserId(id)
          setUserId(id)
        }}
      />
    )
  }

  function switchUser() {
    clearUserId()
    setUserId(null)
    window.location.hash = ''
  }

  const roomId = route.page === 'room' ? route.roomId : null
  // ADR-117: 방 하나를 조회하는 API가 없어 사이드바가 읽은 목록에서 찾고, 없으면 id로 보인다
  const title = rooms.find((room) => room.id === roomId)?.name ?? `방 #${roomId}`

  return (
    <NicknameProvider key={userId}>
      {/* ADR-107: 좁은 화면에서는 data-view로 목록과 대화 중 하나만 보인다 */}
      <div className="app" data-view={roomId === null ? 'rooms' : 'room'}>
        <header className="top">
          <strong>chat</strong>
          <span className="me">사용자 #{userId}</span>
          <button onClick={switchUser}>사용자 바꾸기</button>
        </header>
        <div className="shell">
          <RoomListPage userId={userId} activeRoomId={roomId} onRoomsChange={setRooms}
            onOpen={(id) => (window.location.hash = roomHash(id))} />
          <main className="main">
            {roomId === null ? (
              <p className="placeholder">방을 고르거나 새로 만드세요.</p>
            ) : (
              // ADR-082: 방을 옮기면 커서·메시지 상태를 새로 시작하도록 다시 만든다
              <ChatRoomPage key={roomId} userId={userId} roomId={roomId} title={title}
                onBack={() => (window.location.hash = ROOMS_HASH)} />
            )}
          </main>
        </div>
      </div>
    </NicknameProvider>
  )
}

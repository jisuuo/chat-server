import { useState } from 'react'
import { ChatRoomPage } from './pages/ChatRoomPage'
import { LoginPage } from './pages/LoginPage'
import { RoomListPage } from './pages/RoomListPage'
import { ROOMS_HASH, roomHash, useHashRoute } from './route'
import { clearUserId, loadUserId, saveUserId } from './session'

export default function App() {
  const [userId, setUserId] = useState<number | null>(loadUserId)
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

  return (
    <div className="app">
      <header className="top">
        <span>사용자 #{userId}</span>
        <button onClick={switchUser}>사용자 바꾸기</button>
      </header>
      {route.page === 'room' ? (
        // 계획 4 세부 #4: 방을 옮기면 커서·메시지 상태를 새로 시작하도록 다시 만든다
        <ChatRoomPage key={route.roomId} userId={userId} roomId={route.roomId} onBack={() => (window.location.hash = ROOMS_HASH)} />
      ) : (
        <RoomListPage userId={userId} onOpen={(roomId) => (window.location.hash = roomHash(roomId))} />
      )}
    </div>
  )
}

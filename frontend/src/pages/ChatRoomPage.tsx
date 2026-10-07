import { useRef } from 'react'
import { PollingPanel } from '../components/PollingPanel'
import { Composer } from '../components/Composer'
import { MessageList } from '../components/MessageList'
import { buildChatItems } from '../messages/chatItems'
import { useChatScroll } from '../messages/useChatScroll'
import { useRoomMessages } from '../messages/useRoomMessages'
import { useNicknames } from '../users/useNicknames'

type Props = { userId: number; roomId: number; title: string; onBack: () => void }

export function ChatRoomPage({ userId, roomId, title, onBack }: Props) {
  const room = useRoomMessages(userId, roomId)
  const listRef = useRef<HTMLOListElement>(null)
  const scroll = useChatScroll(listRef, room.messages, userId)
  const senderName = useNicknames(userId, room.messages.map((m) => m.senderId))

  async function leave() {
    if (await room.leave()) onBack()
  }

  return (
    <section className="chat">
      <header className="chat-header">
        <button className="back" onClick={onBack}>방 목록으로</button>
        <h2>{title}</h2>
        {room.status === 'ready' && (
          <>
            {/* ADR-114: 관측용 패널(ADR-081)은 남기되 평소에는 접어 둔다 */}
            <details className="debug">
              <summary>폴링 상태</summary>
              <PollingPanel stats={room.polling.stats} cursor={room.polling.cursor} intervalMs={room.polling.intervalMs}
                paused={room.polling.paused} onIntervalChange={room.polling.setIntervalMs} onTogglePause={room.polling.togglePause} />
            </details>
            <button onClick={() => void leave()}>나가기</button>
          </>
        )}
      </header>
      {room.error && <p role="alert" className="error">{room.error}</p>}
      {room.status === 'loading' && <p className="placeholder">불러오는 중…</p>}
      {room.status === 'notMember' && (
        <div className="join">
          <p>이 방의 멤버가 아닙니다.</p>
          <button onClick={() => void room.join()}>입장</button>
        </div>
      )}
      {room.status === 'ready' && (
        <>
          <MessageList items={buildChatItems(room.messages, userId)} senderName={senderName} listRef={listRef}
            onScroll={scroll.onScroll} hasOlder={room.hasOlder} onLoadOlder={() => void room.loadOlder()}
            unseen={scroll.unseen} onJumpToBottom={scroll.scrollToBottom} />
          <Composer onSend={room.send} />
        </>
      )}
    </section>
  )
}

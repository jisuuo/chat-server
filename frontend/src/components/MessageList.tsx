import type { RefObject } from 'react'
import type { ChatItem } from '../messages/chatItems'

type Props = {
  items: ChatItem[]
  senderName: (senderId: number) => string
  listRef: RefObject<HTMLOListElement | null>
  onScroll: () => void
  hasOlder: boolean
  onLoadOlder: () => void
  unseen: number
  onJumpToBottom: () => void
}

export function MessageList({ items, senderName, listRef, onScroll, hasOlder, onLoadOlder, unseen, onJumpToBottom }: Props) {
  return (
    <div className="conversation">
      <ol aria-label="대화" className="messages" ref={listRef} onScroll={onScroll}>
        {/* ADR-112: 버튼을 목록 안 맨 위에 두어 위로 스크롤한 끝에서 바로 누른다 */}
        {hasOlder && (
          <li className="older"><button onClick={onLoadOlder}>이전 메시지 더 보기</button></li>
        )}
        {items.map((item) =>
          item.kind === 'day' ? (
            <li key={item.key} className="day"><span>{item.label}</span></li>
          ) : (
            <li key={item.key} className={item.mine ? 'msg mine' : 'msg'}>
              {item.showSender && <span className="sender">{senderName(item.message.senderId)}</span>}
              <div className="line">
                <p className="bubble">{item.message.content}</p>
                <time dateTime={item.message.createdAt}>{item.time}</time>
              </div>
            </li>
          ),
        )}
      </ol>
      {unseen > 0 && <button className="jump" onClick={onJumpToBottom}>새 메시지 {unseen}개</button>}
    </div>
  )
}

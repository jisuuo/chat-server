import { useLayoutEffect, useRef, useState } from 'react'
import type { RefObject } from 'react'
import type { Message } from '../api/types'
import { lastId } from './merge'

export const BOTTOM_THRESHOLD_PX = 80

// ADR-111: 위로 올려 읽는 중에는 끌어내리지 않고, 위에 붙일 때는 보던 메시지를 그대로 둔다
export function useChatScroll(listRef: RefObject<HTMLElement | null>, messages: Message[], myId: number) {
  const [atBottom, setAtBottom] = useState(true)
  const [seenId, setSeenId] = useState(0)
  const previous = useRef<{ oldest: number; newest: number; anchor: HTMLElement | null; anchorTop: number }>({
    oldest: 0, newest: 0, anchor: null, anchorTop: 0,
  })
  const newest = lastId(messages)

  useLayoutEffect(() => {
    const list = listRef.current
    if (!list) return
    const before = previous.current
    const oldest = messages.length === 0 ? 0 : messages[0].id
    const latest = lastId(messages)
    const mineArrived = messages.some((m) => m.id > before.newest && m.senderId === myId)
    if (latest > before.newest && (before.newest === 0 || atBottom || mineArrived)) {
      list.scrollTop = list.scrollHeight
    } else if (before.newest !== 0 && oldest < before.oldest && before.anchor?.isConnected) {
      // ADR-111: 아래에도 메시지가 붙을 수 있어 전체 높이 대신 기존 첫 메시지의 화면 위치를 기준으로 보정한다
      list.scrollTop += before.anchor.getBoundingClientRect().top - before.anchorTop
    }
    const anchor = list.querySelector<HTMLElement>('.msg')
    previous.current = { oldest, newest: latest, anchor, anchorTop: anchor?.getBoundingClientRect().top ?? 0 }
  }, [listRef, messages, myId, atBottom])

  function onScroll() {
    const list = listRef.current
    if (!list) return
    const nowAtBottom = list.scrollHeight - list.scrollTop - list.clientHeight <= BOTTOM_THRESHOLD_PX
    if (previous.current.anchor?.isConnected) previous.current.anchorTop = previous.current.anchor.getBoundingClientRect().top
    // 맨 아래에 있던 동안 보인 메시지는 읽은 것으로 본다 (맨 아래를 떠나는 순간 포함)
    if (nowAtBottom || atBottom) setSeenId(newest)
    setAtBottom(nowAtBottom)
  }

  function scrollToBottom() {
    const list = listRef.current
    if (list) list.scrollTop = list.scrollHeight
    setAtBottom(true)
    setSeenId(newest)
  }

  const unseen = atBottom ? 0 : messages.filter((m) => m.id > seenId && m.senderId !== myId).length
  return { unseen, onScroll, scrollToBottom }
}

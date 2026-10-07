import type { Message } from '../api/types'

export type ChatItem =
  | { kind: 'day'; key: string; label: string }
  | { kind: 'message'; key: string; message: Message; mine: boolean; showSender: boolean; time: string }

// ADR-109·110: timeZone은 테스트 결과를 고정하려고 넘긴다. 화면은 브라우저 시간대를 쓴다
export function buildChatItems(messages: Message[], myId: number, timeZone?: string): ChatItem[] {
  const dayKey = new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' })
  const dayLabel = new Intl.DateTimeFormat('ko-KR', { timeZone, year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' })
  const timeLabel = new Intl.DateTimeFormat('ko-KR', { timeZone, hour: 'numeric', minute: '2-digit' })

  const items: ChatItem[] = []
  let previousDay: string | null = null
  let previousSender: number | null = null
  for (const message of messages) {
    const at = new Date(message.createdAt)
    const day = dayKey.format(at)
    if (day !== previousDay) {
      items.push({ kind: 'day', key: `day-${day}`, label: dayLabel.format(at) })
      previousDay = day
      previousSender = null
    }
    const mine = message.senderId === myId
    items.push({
      kind: 'message',
      key: `m-${message.id}`,
      message,
      mine,
      showSender: !mine && message.senderId !== previousSender,
      time: timeLabel.format(at),
    })
    previousSender = message.senderId
  }
  return items
}

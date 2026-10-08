import { readMessages } from '../api/chat'
import type { Message } from '../api/types'

// 현재 화면의 가장 오래된 id까지 다시 읽어 커밋 순서가 뒤집힌 메시지도 보이는 구간 안에서는 합친다.
export async function recoverVisibleMessages(userId: number, roomId: number, oldestVisibleId: number): Promise<Message[]> {
  const recovered: Message[] = []
  let before: number | undefined
  while (true) {
    const { data } = await readMessages(userId, roomId, before === undefined ? {} : { before })
    recovered.push(...data.messages)
    if (!data.hasMore || data.messages.length === 0) return recovered
    const oldestFetched = data.messages[0].id
    // 경계 id와 같은 페이지에서 멈추면, 그보다 작은 id가 뒤늦게 커밋된 다음 페이지를 놓친다.
    if (oldestVisibleId > 0 && oldestFetched < oldestVisibleId) return recovered
    if (before !== undefined && oldestFetched >= before) return recovered
    before = oldestFetched
  }
}

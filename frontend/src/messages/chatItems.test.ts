import { describe, expect, it } from 'vitest'
import type { Message } from '../api/types'
import { buildChatItems } from './chatItems'

const msg = (id: number, senderId: number, createdAt: string): Message => ({ id, roomId: 1, senderId, content: `m${id}`, createdAt })
const SEOUL = 'Asia/Seoul'

describe('buildChatItems', () => {
  it('날짜가 바뀔 때마다 구분선을 넣는다 (시간대 기준)', () => {
    // UTC 14:59 = 서울 23:59, UTC 15:00 = 서울 다음 날 00:00
    const items = buildChatItems([
      msg(1, 2, '2026-10-06T14:59:00Z'),
      msg(2, 2, '2026-10-06T15:00:00Z'),
    ], 1, SEOUL)
    expect(items.map((i) => i.kind)).toEqual(['day', 'message', 'day', 'message'])
    expect(items[0]).toMatchObject({ kind: 'day', label: expect.stringContaining('10월 6일') })
    expect(items[2]).toMatchObject({ kind: 'day', label: expect.stringContaining('10월 7일') })
  })

  it('내 메시지를 표시하고, 남의 연속 메시지는 이름을 첫 번째에만 보인다', () => {
    const at = '2026-10-07T03:05:00Z'
    const items = buildChatItems([msg(1, 2, at), msg(2, 2, at), msg(3, 1, at), msg(4, 2, at)], 1, SEOUL)
      .filter((i) => i.kind === 'message')
    expect(items.map((i) => [i.message.id, i.mine, i.showSender])).toEqual([
      [1, false, true], [2, false, false], [3, true, false], [4, false, true],
    ])
  })

  it('시간은 시:분으로 보인다', () => {
    const [, item] = buildChatItems([msg(1, 2, '2026-10-07T06:05:00Z')], 1, SEOUL)
    // 서울 15:05. ICU 버전에 따라 "오후 3:05" 앞뒤 공백이 다를 수 있어 숫자만 확인한다
    expect(item).toMatchObject({ kind: 'message', time: expect.stringMatching(/3:05/) })
  })
})

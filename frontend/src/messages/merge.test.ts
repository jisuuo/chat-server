import { describe, expect, it } from 'vitest'
import type { Message } from '../api/types'
import { lastId, mergeMessages } from './merge'

const msg = (id: number, content = `m${id}`): Message => ({ id, roomId: 1, senderId: 2, content, createdAt: '2026-10-07T00:00:00Z' })

describe('mergeMessages', () => {
  it('id로 중복을 없애고 id 순서로 정렬한다', () => {
    const merged = mergeMessages([msg(1), msg(3)], [msg(3), msg(2), msg(4)])
    expect(merged.map((m) => m.id)).toEqual([1, 2, 3, 4])
  })

  it('과거 메시지를 앞에 붙여도 순서를 지킨다', () => {
    expect(mergeMessages([msg(10), msg(11)], [msg(8), msg(9)]).map((m) => m.id)).toEqual([8, 9, 10, 11])
  })
})

describe('lastId', () => {
  it('마지막 id, 비면 0', () => {
    expect(lastId([msg(4), msg(7)])).toBe(7)
    expect(lastId([])).toBe(0)
  })
})

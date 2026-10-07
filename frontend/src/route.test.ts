import { describe, expect, it } from 'vitest'
import { parseRoute, roomHash } from './route'

describe('route', () => {
  it('방 hash면 채팅방', () => {
    expect(parseRoute('#/rooms/12')).toEqual({ page: 'room', roomId: 12 })
    expect(parseRoute(roomHash(3))).toEqual({ page: 'room', roomId: 3 })
  })

  it('그 밖에는 방 목록', () => {
    for (const hash of ['', '#', '#/rooms', '#/rooms/0', '#/rooms/abc', '#/rooms/1/x', '#/rooms/9007199254740993']) {
      expect(parseRoute(hash)).toEqual({ page: 'rooms' })
    }
  })
})

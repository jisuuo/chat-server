import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiFetch } from './client'
import { createRoom, createUser, joinRoom, leaveRoom, listRooms, readMessages, sendMessage } from './chat'

vi.mock('./client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./client')>()),
  apiFetch: vi.fn(),
}))

describe('chat API 경로', () => {
  beforeEach(() => vi.mocked(apiFetch).mockReset())

  it('메시지 조회는 커서가 있을 때만 쿼리를 붙인다', () => {
    readMessages(3, 1)
    readMessages(3, 1, { after: 5 })
    readMessages(3, 1, { before: 9 })
    expect(vi.mocked(apiFetch).mock.calls.map((call) => call[0])).toEqual([
      '/api/rooms/1/messages',
      '/api/rooms/1/messages?after=5',
      '/api/rooms/1/messages?before=9',
    ])
  })

  it('방 목록 커서는 인코딩한다', () => {
    listRooms(3, '-:3')
    listRooms(3)
    expect(vi.mocked(apiFetch).mock.calls.map((call) => call[0])).toEqual(['/api/rooms?cursor=-%3A3', '/api/rooms'])
  })

  it('사용자·방 생성의 메서드와 본문', () => {
    createUser('가')
    createRoom(3, '방')
    expect(vi.mocked(apiFetch).mock.calls).toEqual([
      ['/api/dev/users', { method: 'POST', body: { nickname: '가' } }],
      ['/api/rooms', { method: 'POST', userId: 3, body: { name: '방' } }],
    ])
  })

  it('입장·나가기·전송의 메서드와 경로', () => {
    joinRoom(3, 1)
    leaveRoom(3, 1)
    sendMessage(3, 1, '안녕')
    expect(vi.mocked(apiFetch).mock.calls).toEqual([
      ['/api/rooms/1/members', { method: 'POST', userId: 3 }],
      ['/api/rooms/1/members/me', { method: 'DELETE', userId: 3 }],
      ['/api/rooms/1/messages', { method: 'POST', userId: 3, body: { content: '안녕' } }],
    ])
  })
})

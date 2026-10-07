import { beforeEach, describe, expect, it } from 'vitest'
import { clearUserId, loadUserId, parseUserId, saveUserId } from './session'

describe('session', () => {
  beforeEach(() => sessionStorage.clear())

  it('저장한 사용자 id를 다시 읽는다', () => {
    saveUserId(7)
    expect(loadUserId()).toBe(7)
  })

  it('저장된 값이 없거나 형식이 틀리면 null', () => {
    expect(loadUserId()).toBeNull()
    sessionStorage.setItem('chat.userId', '007')
    expect(loadUserId()).toBeNull()
  })

  it('지우면 null', () => {
    saveUserId(7)
    clearUserId()
    expect(loadUserId()).toBeNull()
  })

  it('ADR-046 형식이고 JS 안전 정수인 id만 받는다', () => {
    expect(parseUserId('12')).toBe(12)
    for (const bad of ['', '0', '-1', '007', '+5', ' 5', 'abc', '1.5', '9007199254740993']) {
      expect(parseUserId(bad)).toBeNull()
    }
  })
})

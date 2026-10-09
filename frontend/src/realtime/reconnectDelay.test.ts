import { describe, expect, it } from 'vitest'
import { reconnectDelay } from './reconnectDelay'

describe('reconnectDelay', () => {
  it('재시도 횟수에 따라 무작위 범위를 두 배씩 넓힌다', () => {
    expect(reconnectDelay(0, () => 0.5)).toBe(500)
    expect(reconnectDelay(3, () => 0.5)).toBe(4000)
  })

  it('대기 범위를 30초로 제한한다', () => {
    expect(reconnectDelay(20, () => 0.999)).toBeLessThan(30_000)
  })

  it('0을 뽑으면 즉시 재접속할 수 있다', () => {
    expect(reconnectDelay(5, () => 0)).toBe(0)
  })
})

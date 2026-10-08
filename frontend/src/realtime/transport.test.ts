import { describe, expect, it } from 'vitest'
import { currentTransport } from './transport'

describe('currentTransport', () => {
  it('기본은 websocket이고 ?transport=polling일 때만 polling', () => {
    expect(currentTransport('')).toBe('websocket')
    expect(currentTransport('?transport=polling')).toBe('polling')
    expect(currentTransport('?transport=websocket')).toBe('websocket')
    expect(currentTransport('?transport=other')).toBe('websocket')
  })
})

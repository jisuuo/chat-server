import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/client'
import { usePolling } from './usePolling'
import type { PollResult } from './usePolling'

const info = { status: 200, requestId: 'r-1', durationMs: 3 }

async function advance(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms)
  })
}

describe('usePolling', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('응답을 받은 뒤에야 다음 요청을 예약한다', async () => {
    let resolve: (result: PollResult) => void = () => {}
    const poll = vi.fn(() => new Promise<PollResult>((r) => (resolve = r)))
    renderHook(() => usePolling({ enabled: true, intervalMs: 1000, poll }))

    await advance(1000)
    expect(poll).toHaveBeenCalledTimes(1)
    await advance(5000)
    expect(poll).toHaveBeenCalledTimes(1)

    await act(async () => resolve({ hasMore: false, info, count: 0 }))
    await advance(1000)
    expect(poll).toHaveBeenCalledTimes(2)
  })

  it('hasMore면 기다리지 않고 바로 다시 요청하고 받은 수를 센다', async () => {
    const poll = vi.fn<() => Promise<PollResult>>()
      .mockResolvedValueOnce({ hasMore: true, info, count: 50 })
      .mockResolvedValue({ hasMore: false, info, count: 1 })
    const { result } = renderHook(() => usePolling({ enabled: true, intervalMs: 1000, poll }))

    await advance(1000)
    await advance(1)
    expect(poll).toHaveBeenCalledTimes(2)
    expect(result.current).toMatchObject({ requests: 2, received: 51, errors: 0, last: info })
  })

  it('오류도 세고 같은 주기로 계속한다', async () => {
    const denied = { ...info, status: 403, requestId: 'r-9' }
    const poll = vi.fn<() => Promise<PollResult>>()
      .mockRejectedValueOnce(new ApiError(403, 'NOT_A_MEMBER', '멤버가 아닙니다.', denied))
      .mockResolvedValue({ hasMore: false, info, count: 0 })
    const { result } = renderHook(() => usePolling({ enabled: true, intervalMs: 1000, poll }))

    await advance(1000)
    expect(result.current).toMatchObject({ requests: 1, errors: 1, last: denied, lastError: '멤버가 아닙니다.' })
    await advance(1000)
    expect(poll).toHaveBeenCalledTimes(2)
    expect(result.current).toMatchObject({ requests: 2, errors: 1, lastError: null })
  })

  it('enabled가 false가 되면 멈춘다', async () => {
    const poll = vi.fn<() => Promise<PollResult>>().mockResolvedValue({ hasMore: false, info, count: 0 })
    const { rerender } = renderHook(({ enabled }) => usePolling({ enabled, intervalMs: 1000, poll }), {
      initialProps: { enabled: true },
    })
    rerender({ enabled: false })
    await advance(5000)
    expect(poll).not.toHaveBeenCalled()
  })

  it('진행 중인 요청 뒤에 꺼지면 결과를 반영하거나 재예약하지 않는다', async () => {
    let resolve: (result: PollResult) => void = () => {}
    const poll = vi.fn(() => new Promise<PollResult>((r) => (resolve = r)))
    const { result, rerender } = renderHook(({ enabled }) => usePolling({ enabled, intervalMs: 1000, poll }), {
      initialProps: { enabled: true },
    })
    await advance(1000)
    rerender({ enabled: false })
    await act(async () => resolve({ hasMore: false, info, count: 1 }))
    await advance(5000)
    expect(poll).toHaveBeenCalledTimes(1)
    expect(result.current.requests).toBe(0)
  })

  it('진행 중에 일시정지했다 다시 시작해도 이전 요청과 겹치지 않는다', async () => {
    let resolveFirst: (result: PollResult) => void = () => {}
    const poll = vi.fn<() => Promise<PollResult>>()
      .mockImplementationOnce(() => new Promise<PollResult>((resolve) => { resolveFirst = resolve }))
      .mockResolvedValue({ hasMore: false, info, count: 1 })
    const { result, rerender } = renderHook(({ enabled }) => usePolling({ enabled, intervalMs: 1000, poll }), {
      initialProps: { enabled: true },
    })
    await advance(1000)
    rerender({ enabled: false })
    rerender({ enabled: true })
    await advance(1000)
    expect(poll).toHaveBeenCalledTimes(1)

    await act(async () => resolveFirst({ hasMore: false, info, count: 9 }))
    expect(poll).toHaveBeenCalledTimes(2)
    expect(result.current).toMatchObject({ requests: 1, received: 1 })
  })

  it('바꾼 주기는 다음 예약부터 적용한다', async () => {
    const poll = vi.fn<() => Promise<PollResult>>().mockResolvedValue({ hasMore: false, info, count: 0 })
    const { rerender } = renderHook(({ intervalMs }) => usePolling({ enabled: true, intervalMs, poll }), {
      initialProps: { intervalMs: 5000 },
    })
    await advance(5000)
    expect(poll).toHaveBeenCalledTimes(1)
    rerender({ intervalMs: 500 })
    await advance(5000)
    expect(poll).toHaveBeenCalledTimes(2)
    await advance(500)
    expect(poll).toHaveBeenCalledTimes(3)
  })

  it('요청을 기다리는 중 바꾼 주기도 다음 예약에 적용한다', async () => {
    let resolve: (result: PollResult) => void = () => {}
    const poll = vi.fn<() => Promise<PollResult>>()
      .mockImplementationOnce(() => new Promise<PollResult>((r) => (resolve = r)))
      .mockResolvedValue({ hasMore: false, info, count: 0 })
    const { rerender } = renderHook(({ intervalMs }) => usePolling({ enabled: true, intervalMs, poll }), {
      initialProps: { intervalMs: 1000 },
    })
    await advance(1000)
    rerender({ intervalMs: 500 })
    await act(async () => resolve({ hasMore: false, info, count: 0 }))
    await advance(499)
    expect(poll).toHaveBeenCalledTimes(1)
    await advance(1)
    expect(poll).toHaveBeenCalledTimes(2)
  })
})

import { useEffect, useRef, useState } from 'react'
import { ApiError } from '../api/client'
import type { CallInfo } from '../api/client'

export const POLL_INTERVALS = [500, 1000, 2000, 5000]
export const DEFAULT_POLL_INTERVAL_MS = 2000

export type PollResult = { hasMore: boolean; info: CallInfo; count: number }
export type PollStats = { requests: number; errors: number; received: number; last: CallInfo | null; lastError: string | null }

type Options = { enabled: boolean; intervalMs: number; poll: () => Promise<PollResult> }

const EMPTY: PollStats = { requests: 0, errors: 0, received: 0, last: null, lastError: null }

export function usePolling({ enabled, intervalMs, poll }: Options): PollStats {
  const [stats, setStats] = useState<PollStats>(EMPTY)
  const pollRef = useRef(poll)
  const intervalRef = useRef(intervalMs)

  useEffect(() => {
    pollRef.current = poll
    intervalRef.current = intervalMs
  })

  useEffect(() => {
    if (!enabled) return
    let stopped = false
    let timer: ReturnType<typeof setTimeout> | undefined

    // 계획 4 세부 #6: 응답을 받은 뒤 다음 요청을 예약해 한 탭의 요청이 겹치지 않게 한다
    // 계획 4 세부 #10: 오류가 나도 같은 주기로 계속한다 (장애 선행, 대기를 늘리지 않음)
    const tick = async () => {
      let delay: number
      try {
        const result = await pollRef.current()
        if (stopped) return
        setStats((current) => ({
          ...current,
          requests: current.requests + 1,
          received: current.received + result.count,
          last: result.info,
          lastError: null,
        }))
        // 계획 4 세부 #9: 한 번에 다 못 받았으면 다음 주기까지 기다리지 않는다
        delay = result.hasMore ? 0 : intervalRef.current
      } catch (e) {
        if (stopped) return
        setStats((current) => ({
          ...current,
          requests: current.requests + 1,
          errors: current.errors + 1,
          last: e instanceof ApiError ? e.info : current.last,
          lastError: e instanceof Error ? e.message : String(e),
        }))
        delay = intervalRef.current
      }
      timer = setTimeout(tick, delay)
    }

    timer = setTimeout(tick, intervalRef.current)
    return () => {
      stopped = true
      clearTimeout(timer)
    }
  }, [enabled])

  return stats
}

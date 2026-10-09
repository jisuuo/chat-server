const BASE_MS = 1000
const CAP_MS = 30_000

// ADR-159: 동시에 끊긴 탭의 재접속을 같은 시각에 고정하지 않고 전체 대기 구간에 흩는다.
export function reconnectDelay(attempt: number, random: () => number = Math.random): number {
  return Math.floor(random() * Math.min(CAP_MS, BASE_MS * 2 ** attempt))
}

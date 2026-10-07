import { POLL_INTERVALS } from '../messages/usePolling'
import type { PollStats } from '../messages/usePolling'

type Props = {
  stats: PollStats
  cursor: number
  intervalMs: number
  paused: boolean
  onIntervalChange: (ms: number) => void
  onTogglePause: () => void
}

// 계획 4 세부 #1: X-Request-Id로 Kibana·app.json의 같은 요청 로그를 찾는다
export function PollingPanel({ stats, cursor, intervalMs, paused, onIntervalChange, onTogglePause }: Props) {
  return (
    <aside aria-label="폴링 상태" className="panel">
      <div className="controls">
        <select aria-label="폴링 주기" value={intervalMs} onChange={(event) => onIntervalChange(Number(event.target.value))}>
          {POLL_INTERVALS.map((ms) => (
            <option key={ms} value={ms}>
              {ms / 1000}초
            </option>
          ))}
        </select>
        <button onClick={onTogglePause}>{paused ? '다시 시작' : '일시정지'}</button>
      </div>
      <dl>
        <dt>after 커서</dt>
        <dd>{cursor}</dd>
        <dt>요청</dt>
        <dd>{stats.requests}</dd>
        <dt>오류</dt>
        <dd>{stats.errors}</dd>
        <dt>받은 메시지</dt>
        <dd>{stats.received}</dd>
        <dt>마지막 응답</dt>
        <dd>{stats.last ? `${stats.last.status} · ${stats.last.durationMs}ms` : '-'}</dd>
        <dt>X-Request-Id</dt>
        <dd>{stats.last?.requestId ?? '-'}</dd>
        {stats.lastError && (
          <>
            <dt>마지막 오류</dt>
            <dd>{stats.lastError}</dd>
          </>
        )}
      </dl>
    </aside>
  )
}

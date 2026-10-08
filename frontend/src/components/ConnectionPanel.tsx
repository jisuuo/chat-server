import type { ConnectionState, SocketStats } from '../realtime/chatSocket'

const STATE_LABEL: Record<ConnectionState, string> = { connecting: '연결 중', open: '연결됨', closed: '끊김' }

type Props = { stats: SocketStats }

// 계획 7 세부 11: 폴링 패널(ADR-081)과 같은 자리에서 연결을 관찰한다. 종료 코드 1006은 비정상 종료다
export function ConnectionPanel({ stats }: Props) {
  return (
    <aside aria-label="연결 상태" className="panel">
      <dl>
        <dt>연결</dt>
        <dd>{STATE_LABEL[stats.state]}</dd>
        <dt>재연결</dt>
        <dd>{stats.reconnects}</dd>
        <dt>받은 프레임</dt>
        <dd>{stats.received}</dd>
        <dt>보낸 프레임</dt>
        <dd>{stats.sent}</dd>
        <dt>마지막 종료 코드</dt>
        <dd>{stats.lastCloseCode ?? '-'}</dd>
      </dl>
    </aside>
  )
}

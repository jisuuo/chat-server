export type Transport = 'websocket' | 'polling'

// ADR-139: 폴링은 Step 6 비교용으로 URL에서만 고른다. 기본은 websocket
export function currentTransport(search: string = window.location.search): Transport {
  return new URLSearchParams(search).get('transport') === 'polling' ? 'polling' : 'websocket'
}

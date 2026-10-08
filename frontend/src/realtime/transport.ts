export type Transport = 'websocket' | 'polling'

// 계획 7 세부 10: 폴링은 Step 6 비교용으로 URL에서만 고른다. 기본은 websocket
export function currentTransport(search: string = window.location.search): Transport {
  return new URLSearchParams(search).get('transport') === 'polling' ? 'polling' : 'websocket'
}

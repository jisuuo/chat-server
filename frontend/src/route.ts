import { useSyncExternalStore } from 'react'

export type Route = { page: 'rooms' } | { page: 'room'; roomId: number }

export const ROOMS_HASH = '#/rooms'

export function roomHash(roomId: number): string {
  return `#/rooms/${roomId}`
}

export function parseRoute(hash: string): Route {
  const match = /^#\/rooms\/([1-9][0-9]*)$/.exec(hash)
  if (!match) return { page: 'rooms' }
  const roomId = Number(match[1])
  return Number.isSafeInteger(roomId) ? { page: 'room', roomId } : { page: 'rooms' }
}

function subscribe(onChange: () => void): () => void {
  window.addEventListener('hashchange', onChange)
  return () => window.removeEventListener('hashchange', onChange)
}

// 계획 4 세부 #4: 화면이 둘뿐이라 라우터 라이브러리 대신 hash를 구독한다
export function useHashRoute(): Route {
  return parseRoute(useSyncExternalStore(subscribe, () => window.location.hash))
}

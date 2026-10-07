const KEY = 'chat.userId'

// ADR-046과 같은 형식만 받는다. JS Number로 정확히 표현할 수 없는 값도 거절한다
export function parseUserId(text: string): number | null {
  if (!/^[1-9][0-9]{0,18}$/.test(text)) return null
  const id = Number(text)
  return Number.isSafeInteger(id) ? id : null
}

// 계획 4 세부 #3: 탭마다 다른 사용자로 대화를 확인하려고 sessionStorage에 둔다
export function loadUserId(): number | null {
  const value = sessionStorage.getItem(KEY)
  return value === null ? null : parseUserId(value)
}

export function saveUserId(id: number): void {
  sessionStorage.setItem(KEY, String(id))
}

export function clearUserId(): void {
  sessionStorage.removeItem(KEY)
}

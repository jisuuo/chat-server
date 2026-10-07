import type { ApiBody } from './types'

export type CallInfo = { status: number; requestId: string | null; durationMs: number }
export type ApiResult<T> = { data: T; info: CallInfo }

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly info: CallInfo

  constructor(status: number, code: string, message: string, info: CallInfo) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.info = info
  }
}

type Options = { method?: string; userId?: number | null; body?: unknown }

// ADR-020: 성공/실패를 success 하나로 가른다. 상태 코드와 X-Request-Id는 폴링 상태 패널이 쓴다
export async function apiFetch<T>(path: string, options: Options = {}): Promise<ApiResult<T>> {
  const headers: Record<string, string> = {}
  if (options.userId != null) headers['X-User-Id'] = String(options.userId)
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'

  const started = performance.now()
  const response = await fetch(path, {
    method: options.method ?? 'GET',
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  })
  const body = (await response.json().catch(() => null)) as ApiBody<T> | null
  const info: CallInfo = {
    status: response.status,
    requestId: response.headers.get('X-Request-Id'),
    durationMs: Math.round(performance.now() - started),
  }

  if (body?.success) return { data: body.data as T, info }
  throw new ApiError(response.status, body?.error?.code ?? 'UNKNOWN', body?.error?.message ?? `HTTP ${response.status}`, info)
}

export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) return `${e.message} (${e.code})`
  return e instanceof Error ? e.message : String(e)
}

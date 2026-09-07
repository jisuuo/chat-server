// Placeholder REST client. No real request logic yet.

const BASE_URL = 'http://localhost:8080/api'

export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${BASE_URL}${path}`, init)
  return response.json() as Promise<T>
}

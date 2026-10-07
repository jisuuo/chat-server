import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// Vitest를 globals 없이 쓰므로 Testing Library의 자동 정리가 동작하지 않는다
afterEach(() => cleanup())

/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react()],
  server: {
    // ADR-086: E2E와 문서가 5173을 가정한다. 다른 포트로 몰래 바뀌지 않게 한다
    port: 5173,
    strictPort: true,
    proxy: {
      // ADR-028: 브라우저는 한 주소하고만 통신하고 /api만 백엔드로 넘긴다
      // ADR-086: 백엔드 clientIp가 proxy의 X-Forwarded-For를 반영하는지 확인한다
      '/api': { target: 'http://localhost:8080', xfwd: true },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    include: ['src/**/*.test.{ts,tsx}'],
  },
})

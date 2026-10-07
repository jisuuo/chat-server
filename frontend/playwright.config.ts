import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: 'e2e',
  // 같은 로컬 DB를 쓰므로 순서대로 실행한다
  workers: 1,
  use: { baseURL: 'http://localhost:5173', trace: 'retain-on-failure' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  // 계획 4 세부 #16: 사용자 생성 API가 열리는 local 프로필이 필요하다. DB compose는 미리 띄운다
  webServer: [
    {
      command: "./gradlew bootRun --args='--spring.profiles.active=local,mysql'",
      cwd: '../backend',
      url: 'http://localhost:8080/actuator/health',
      reuseExistingServer: true,
      timeout: 180_000,
    },
    { command: 'npm run dev', url: 'http://localhost:5173', reuseExistingServer: true },
  ],
})

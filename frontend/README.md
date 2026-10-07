# 프론트엔드 실행

Node 22.22.2가 필요합니다. 버전 관리자(nvm)를 쓴다면 `nvm use`로 `frontend/.nvmrc`의 버전을 선택하세요. macOS에서 Homebrew의 Node 22를 직접 쓰는 경우에는 다음처럼 실행할 수 있습니다.

```bash
export PATH="/opt/homebrew/opt/node@22/bin:$PATH"
node --version  # v22.22.2
npm ci
npm test
npm run build
```

`frontend/.npmrc`는 다른 Node 버전에서 의존성을 설치하지 못하게 합니다.

## 브라우저에서 채팅하기

저장소 루트에서 DB를 띄우고, 별도 터미널에서 백엔드와 프론트엔드를 실행합니다.

```bash
docker compose -f infra/compose.db.yml up -d --wait
cd backend && ./gradlew bootRun --args='--spring.profiles.active=local,mysql'
```

```bash
cd frontend
export PATH="/opt/homebrew/opt/node@22/bin:$PATH" # Homebrew Node 22 사용 시
npm run dev
```

`http://localhost:5173`에서 닉네임으로 새 사용자를 만든 뒤 방을 만듭니다. 다른 탭에서는 다른 사용자로 시작해 같은 방 링크를 열고 **입장**을 누르면 메시지 입력창이 보입니다. 탭마다 사용자 ID가 따로 저장됩니다.

브라우저 E2E는 DB를 먼저 띄운 뒤 `npm run e2e`로 실행합니다. Playwright가 `local,mysql` 백엔드와 Vite를 자동으로 시작할 수 있습니다.

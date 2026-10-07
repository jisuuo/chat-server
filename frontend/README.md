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

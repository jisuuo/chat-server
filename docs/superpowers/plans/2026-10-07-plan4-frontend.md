# 계획 4: 프론트엔드 (사용자 선택, 방 목록, 채팅방, 폴링 상태 패널) 구현 계획

> **실행하는 에이전트에게**: 작업은 하나씩 사용자 승인을 받고 시작한다. 작업이 끝나면 결과(테스트 출력 포함)를 보고하고 멈춘다. **커밋하지 않는다** (사용자가 요청할 때만). 단계는 체크박스(`- [ ]`)로 추적한다.

**목표:** 브라우저에서 두 사용자가 방을 만들고, 입장하고, HTTP 폴링으로 메시지를 주고받고, 나가고 다시 입장하는 흐름을 직접 확인할 수 있게 한다. 폴링 상태 패널에서 주기, `after` 커서, 마지막 응답의 `X-Request-Id`와 응답 시간을 보여 줘서 계획 3의 관측 도구(Grafana, Kibana)와 연결한다.

**구조:** `frontend/`는 Vite + React + TypeScript 단일 페이지다. 화면 전환은 라우터 라이브러리 없이 URL hash(`#/rooms`, `#/rooms/{id}`)로 한다. 개발 서버(5173)가 `/api`를 백엔드(8080)로 전달해서 브라우저는 한 주소(origin)하고만 통신한다(ADR-028). 폴링은 응답을 받은 뒤 다음 요청을 예약하는 `usePolling` 훅 하나에 모으고, 커서와 메시지 병합은 순수 함수로 분리해 단위 테스트한다.

**기술:** Node 22.22.2(설치되어 있음), Vite, React, TypeScript, Vitest + jsdom + Testing Library, Playwright(chromium). 2026-10-07 `npm view`로 확인한 최신 버전은 vite 8.3.3, react 19.3.0, typescript 7.0.2, vitest 5.0.3, @playwright/test 1.63.0, create-vite 9.2.1이다. 실제로는 작업 1에서 생성·설치된 버전을 쓴다.

## Context
- 계획 3(관측)이 끝났다. ADR-033(Step 1을 계획 5개로 나눔)의 순서상 다음은 계획 4(프론트엔드)다. 계획 2 개요는 "시작 전에 정할 것"으로 **화면 범위**(설계 문서의 남은 미결정 1번)를 남겼다.
- 이미 결정된 것 (그대로 구현): ADR-028(모노레포, 같은 주소로 서비스, 개발 중 Vite proxy), ADR-029(Vite + React + TypeScript), ADR-020(모든 응답은 `ApiResponse`, 상태 코드는 실제 결과대로), ADR-046(`X-User-Id` 형식 `^[1-9][0-9]{0,18}$`), ADR-031(사용자는 개발용 API + seed로 만든다).
- 사용자 결정 (2026-10-07)
  - 화면 범위: **최소 화면 + 폴링 상태 패널**. 폴링/WebSocket 비교 화면은 Step 2로 미룬다.
  - 작성자 표시: **"사용자 #id"만**. 백엔드는 바꾸지 않는다. F26(보이지 않는 서식 문자로 같아 보이는 닉네임) 확인은 이후로 넘긴다.
  - 테스트: **Vitest 단위 테스트 + Playwright E2E**.
  - 방금 만든 방이 목록 맨 아래에 보이는 문제: **그대로 두고** 화면에서 관찰한 뒤 따로 결정한다.
- 확인한 백엔드 사실 (코드 기준)
  - 응답 DTO: `RoomResponse(id, name, createdBy, lastMessageId|null, createdAt)`, `RoomPageResponse(rooms, hasMore, nextCursor)`, `MemberResponse(roomId, userId)`, `MessageResponse(id, roomId, senderId, content, createdAt)`, `MessagePageResponse(messages, hasMore)`, `UserResponse(id, nickname)`. 나가기는 `data: null`.
  - 요청 DTO: 방 `{name}`, 메시지 `{content}`, 사용자 `{nickname}`.
  - 방 목록은 내가 멤버인지 알려 주지 않는다. 방 하나를 조회하는 API와 사용자 조회 API도 없다. 그래서 채팅방 제목은 "방 #id"이고, 멤버인지는 메시지 조회가 403인지로 판단한다.
  - `X-Request-Id`는 모든 응답 헤더에 있다(계획 3). 같은 주소라서 브라우저 JS에서 읽을 수 있다.
  - `/api/dev/users`는 `local`, `bench` 프로필에서만 열린다.

## 지켜야 할 조건
- **장애 선행 (ADR-034)**: 클라이언트도 예상 문제를 미리 고치지 않는다. 특히 F22(커밋 순서 역전으로 인한 영구 누락)를 화면에서 보정하지 않는다(예: 커서를 일부러 뒤로 당겨 겹쳐 조회하기 금지). F27(방 목록을 넘기는 중 순서가 바뀐 방의 누락)도 보정하지 않는다. 화면을 만들다 발견한 위험은 `docs/failure-lab.md`에 가설로만 적는다.
- 백엔드 코드와 API 동작은 바꾸지 않는다. 기존 `./gradlew test`가 그대로 통과해야 한다.
- 브라우저는 `/api`로만 요청한다. 절대 주소(`http://localhost:8080`)를 코드에 쓰지 않는다 (ADR-028, CORS와 preflight 방지).
- 버전은 **추측하지 않고** 생성·설치된 실제 버전을 쓰고, `package.json`에 `^` 없이 고정한다(ADR-080과 같은 이유: 측정 중 버전이 바뀌면 비교할 수 없다).
- 생성된 템플릿의 TypeScript 설정(`verbatimModuleSyntax`, `erasableSyntaxOnly` 등)을 따른다. 타입만 가져올 때는 `import type`을 쓰고, 생성자 매개변수 속성(`constructor(readonly x)`)은 쓰지 않는다.
- 코드 주석은 "왜"만 쓰고, 이 계획의 세부를 근거로 하면 승인 후 받은 ADR 번호를 적는다(작업 중에는 `계획 4 세부 #n`으로 적고 작업 9에서 ADR 번호로 바꾼다).
- 측정·관찰 결과를 적을 때 "예상"과 "측정"을 구분한다.

## 이 계획에서 새로 정하는 세부 (검토 필요, 승인되면 작업 9에서 ADR-081부터 기록)
| # | 항목 | 제안 | 이유 |
|---|---|---|---|
| 1 | 화면 범위 | 사용자 선택, 방 목록, 채팅방, 채팅방 옆 폴링 상태 패널 | 사용자 결정. 비교 화면은 WebSocket이 생기는 Step 2에서 |
| 2 | 작성자 표시 | `사용자 #{senderId}` | 사용자 결정. message가 user를 모르는 의존 규칙을 지킨다 |
| 3 | 사용자 선택 | 닉네임으로 새로 만들기(`POST /api/dev/users`) 또는 기존 id 입력. id는 `sessionStorage`에 저장한다. 존재하는지는 확인하지 않는다 | 탭마다 다른 사용자로 대화를 확인할 수 있다(`localStorage`면 모든 탭이 같은 사용자). 서버도 형식만 검사한다(ADR-006). 없는 id는 방 생성·입장 때 서버가 401로 알려 준다 |
| 4 | 화면 전환 | 라우터 라이브러리 없이 hash: `#/rooms`(목록), `#/rooms/{id}`(채팅방) | 화면이 2개뿐이다. 새로고침해도 방이 유지되고, 링크로 다른 사용자가 같은 방을 연다(E2E가 쓴다). Step 3 nginx에서 경로 되돌림 설정이 필요 없다 |
| 5 | 상태 관리·스타일 | React state/hook만 쓰고, CSS는 파일 하나 | 라이브러리를 늘리지 않는다 |
| 6 | 폴링 예약 방식 | 응답을 받은 뒤 다음 요청을 `setTimeout`으로 예약한다(`setInterval` 아님). 한 탭에서는 요청이 겹치지 않는다 | `setInterval`이면 서버가 느릴 때 응답을 기다리는 요청이 쌓여 클라이언트가 부하를 키운다. 부하 조건은 계획 5의 k6가 따로 만든다 |
| 7 | 폴링 주기 | 기본 2초, 패널에서 0.5/1/2/5초로 바꾼다. 바꾼 값은 다음 예약부터 적용 | 폴링 주기 최종값은 README 미결정(부하 테스트 변수)이다. 화면은 관찰용 기본값만 정한다 |
| 8 | 폴링 커서 | `after`는 **조회 응답의 마지막 id로만** 전진한다. 내가 보낸 메시지는 전송 응답으로 바로 표시하되 커서는 옮기지 않는다. 처음 커서는 최신 조회의 마지막 id, 메시지가 없으면 0 | 내 메시지 id(102)로 커서를 옮기면, 이미 커밋됐지만 아직 폴링하지 않은 남의 101을 영원히 건너뛴다. F22와 별개로 클라이언트가 만드는 누락이다. 화면에는 id 기준으로 중복을 없애고 id 순서로 보여 준다 |
| 9 | 밀린 메시지 | 폴링 응답이 `hasMore=true`면 기다리지 않고 바로 다음 요청 | 한 번에 50건(기본 size)을 넘게 밀려도 다음 주기까지 기다리지 않는다 |
| 10 | 폴링 오류 | 같은 주기로 계속한다(점점 늘리는 대기·중단 없음). 패널에 오류 수와 마지막 오류를 보인다 | 장애 선행. 403이 계속되면 F28(비멤버 폴링의 감사 로그 폭증)을 화면으로 재현할 수 있다. 재시도 폭주는 가설로 기록한다 |
| 11 | 멤버인지 판단 | 방을 열면 최신 조회 → 403 `NOT_A_MEMBER`면 "입장" 버튼. 입장이 409 `ALREADY_MEMBER`면 이미 멤버로 보고 다시 조회 | 멤버 여부 API가 없다. 서버 인가 결과를 그대로 쓴다 |
| 12 | 과거 메시지 | "이전 메시지 더 보기" 버튼(`before` = 화면의 가장 오래된 id). 자동 무한 스크롤은 하지 않는다 | 스크롤 위치 계산 없이 `before` 커서를 확인할 수 있다 |
| 13 | 방 목록 | 자동 갱신 없음(새로고침 버튼), "더 보기"는 `nextCursor` | 설계에 없는 목록 폴링으로 부하를 늘리지 않는다. F27은 보정하지 않는다 |
| 14 | 입력 검사 | 화면은 길이·문자를 검사하지 않고 서버의 400 메시지를 그대로 보여 준다(빈 메시지면 보내기 버튼만 끈다). 사용자 id 입력만 ADR-046 형식 + JS 안전 정수 범위로 검사한다 | 규칙을 서버 한 곳에 둔다. id는 헤더로 보내기 전에 숫자로 바꿔야 하므로 검사가 필요하다 |
| 15 | Vite proxy | `/api` → `http://localhost:8080`, `xfwd: true`, 포트 5173 고정(`strictPort`) | 계획 3의 `clientIp`가 proxy가 붙인 `X-Forwarded-For`를 반영하는 경로를 실제로 쓴다. 포트가 바뀌면 E2E 주소가 어긋난다 |
| 16 | 테스트 구성 | 단위: Vitest + jsdom + Testing Library(`npm test`, `src/**/*.test.ts(x)`). E2E: Playwright chromium(`npm run e2e`, `e2e/`). E2E는 `webServer`로 백엔드(`local,mysql`)와 Vite를 띄우고, DB compose는 미리 띄운다 | 사용자 결정. local 프로필이어야 E2E가 `/api/dev/users`로 사용자를 만든다 |

## 파일 구조
```
frontend/                         (새로, create-vite react-ts 템플릿에서 시작)
 ├─ package.json                  scripts: dev, build, lint, test, e2e / 버전 고정
 ├─ vite.config.ts                proxy, Vitest 설정
 ├─ playwright.config.ts
 ├─ index.html, tsconfig*.json, eslint.config.js   (템플릿)
 ├─ e2e/chat.spec.ts
 └─ src/
     ├─ main.tsx, App.tsx, App.test.tsx, styles.css
     ├─ session.ts(.test.ts)      sessionStorage 사용자 id, id 형식 검사
     ├─ route.ts(.test.ts)        hash → 화면
     ├─ api/types.ts              응답 타입
     ├─ api/client.ts(.test.ts)   apiFetch, ApiError, errorMessage
     ├─ api/chat.ts(.test.ts)     API 7종 함수
     ├─ messages/merge.ts(.test.ts)          mergeMessages, lastId
     ├─ messages/usePolling.ts(.test.ts)     폴링 훅, 주기 상수
     ├─ pages/LoginPage.tsx(.test.tsx)
     ├─ pages/RoomListPage.tsx(.test.tsx)
     ├─ pages/ChatRoomPage.tsx(.test.tsx)
     ├─ components/PollingPanel.tsx(.test.tsx)
     └─ test/setup.ts
.gitignore                        (수정: frontend/test-results/, frontend/playwright-report/)
```

---

## 작업 0. 계획 저장
- [x] 이 문서를 `docs/superpowers/plans/2026-10-07-plan4-frontend.md`에 저장한다 (실행 날짜가 다르면 그 날짜)

---

## 작업 1. 뼈대, proxy, Vitest, 세션·화면 전환 도구

**Files:**
- Create: `frontend/` (템플릿), `frontend/src/test/setup.ts`, `frontend/src/session.ts`, `frontend/src/session.test.ts`, `frontend/src/route.ts`, `frontend/src/route.test.ts`
- Modify: `frontend/vite.config.ts`, `frontend/package.json`, `.gitignore`
- Delete: 템플릿 예제(`src/App.css`, `src/index.css`, `src/assets/`, `public/vite.svg` 등 카운터 예제)

**Interfaces:**
- Produces: `loadUserId(): number | null`, `saveUserId(id: number): void`, `clearUserId(): void`, `parseUserId(text: string): number | null`, `type Route = { page: 'rooms' } | { page: 'room'; roomId: number }`, `parseRoute(hash: string): Route`, `roomHash(roomId: number): string`, `ROOMS_HASH = '#/rooms'`, `useHashRoute(): Route`

- [x] **Step 1: 템플릿 생성.** 먼저 `npx create-vite@9.2.1 --help`로 대화형 질문을 끄는 옵션을 확인한다. 그 옵션으로 저장소 루트에서 `npx create-vite@9.2.1 frontend --template react-ts`를 실행한다(설치·실행은 하지 않음). 옵션이 없어서 질문이 나오면 멈추고 보고한다.
- [x] **Step 2: 의존성 설치와 버전 고정.**
  ```bash
  cd frontend
  npm install --save-exact
  npm install --save-dev --save-exact vitest jsdom @testing-library/react @testing-library/dom @testing-library/user-event @testing-library/jest-dom
  ```
  설치 후 `package.json`의 `dependencies`·`devDependencies`에 남은 `^`를 지워 정확한 버전으로 고정하고 `npm install`로 lock을 맞춘다. 설치된 버전을 보고에 적는다(작업 9 ADR에 기록).
- [x] **Step 3: 템플릿 예제 제거.** 카운터 예제와 그 CSS·이미지를 지우고 `src/main.tsx`가 `./styles.css`와 `App`만 가져오게 한다. `src/styles.css`는 빈 파일로 만들고(작업 7에서 채움), `src/App.tsx`는 임시로 `export default function App() { return <h1>chat</h1>; }`.
- [x] **Step 4: `vite.config.ts`**

```ts
/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    // 계획 4 세부 #15: E2E와 문서가 5173을 가정한다. 다른 포트로 몰래 바뀌지 않게 한다
    port: 5173,
    strictPort: true,
    proxy: {
      // ADR-028: 브라우저는 한 주소하고만 통신하고 /api만 백엔드로 넘긴다 (CORS·preflight 없음)
      // 계획 4 세부 #15: 백엔드 clientIp가 proxy가 붙인 X-Forwarded-For를 반영하는지 실제 경로로 확인한다
      '/api': { target: 'http://localhost:8080', xfwd: true },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    include: ['src/**/*.test.{ts,tsx}'],
  },
});
```
  템플릿의 플러그인 import 이름이 다르면(예: SWC 플러그인) 생성된 것을 그대로 둔다.
- [x] **Step 5: `package.json` scripts에 추가**: `"test": "vitest run"`, `"test:watch": "vitest"` (e2e는 작업 8)
- [x] **Step 6: `src/test/setup.ts`**

```ts
import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

// Vitest를 globals 없이 쓰므로 Testing Library의 자동 정리가 동작하지 않는다
afterEach(() => cleanup());
```
- [x] **Step 7: 실패하는 테스트** `src/session.test.ts`

```ts
import { beforeEach, describe, expect, it } from 'vitest';
import { clearUserId, loadUserId, parseUserId, saveUserId } from './session';

describe('session', () => {
  beforeEach(() => sessionStorage.clear());

  it('저장한 사용자 id를 다시 읽는다', () => {
    saveUserId(7);
    expect(loadUserId()).toBe(7);
  });

  it('저장된 값이 없거나 형식이 틀리면 null', () => {
    expect(loadUserId()).toBeNull();
    sessionStorage.setItem('chat.userId', '007');
    expect(loadUserId()).toBeNull();
  });

  it('지우면 null', () => {
    saveUserId(7);
    clearUserId();
    expect(loadUserId()).toBeNull();
  });

  it('ADR-046 형식이고 JS 안전 정수인 id만 받는다', () => {
    expect(parseUserId('12')).toBe(12);
    for (const bad of ['', '0', '-1', '007', '+5', ' 5', 'abc', '1.5', '9007199254740993']) {
      expect(parseUserId(bad)).toBeNull();
    }
  });
});
```
  `src/route.test.ts`

```ts
import { describe, expect, it } from 'vitest';
import { parseRoute, roomHash } from './route';

describe('route', () => {
  it('방 hash면 채팅방', () => {
    expect(parseRoute('#/rooms/12')).toEqual({ page: 'room', roomId: 12 });
    expect(parseRoute(roomHash(3))).toEqual({ page: 'room', roomId: 3 });
  });

  it('그 밖에는 방 목록', () => {
    for (const hash of ['', '#', '#/rooms', '#/rooms/0', '#/rooms/abc', '#/rooms/1/x']) {
      expect(parseRoute(hash)).toEqual({ page: 'rooms' });
    }
  });
});
```
- [x] **Step 8: 실패 확인** — `npm test` → 모듈을 찾을 수 없어 실패
- [x] **Step 9: 구현** `src/session.ts`

```ts
const KEY = 'chat.userId';

// ADR-046과 같은 형식만 받는다. 19자리 중 JS Number로 정확히 표현할 수 없는 값도 거절한다
export function parseUserId(text: string): number | null {
  if (!/^[1-9][0-9]{0,18}$/.test(text)) return null;
  const id = Number(text);
  return Number.isSafeInteger(id) ? id : null;
}

// 계획 4 세부 #3: 탭마다 다른 사용자로 대화를 확인하려고 sessionStorage에 둔다
export function loadUserId(): number | null {
  const value = sessionStorage.getItem(KEY);
  return value === null ? null : parseUserId(value);
}

export function saveUserId(id: number): void {
  sessionStorage.setItem(KEY, String(id));
}

export function clearUserId(): void {
  sessionStorage.removeItem(KEY);
}
```
  `src/route.ts`

```ts
import { useSyncExternalStore } from 'react';

export type Route = { page: 'rooms' } | { page: 'room'; roomId: number };

export const ROOMS_HASH = '#/rooms';

export function roomHash(roomId: number): string {
  return `#/rooms/${roomId}`;
}

export function parseRoute(hash: string): Route {
  const match = /^#\/rooms\/([1-9][0-9]*)$/.exec(hash);
  return match ? { page: 'room', roomId: Number(match[1]) } : { page: 'rooms' };
}

function subscribe(onChange: () => void): () => void {
  window.addEventListener('hashchange', onChange);
  return () => window.removeEventListener('hashchange', onChange);
}

// 계획 4 세부 #4: 화면이 둘뿐이라 라우터 라이브러리 대신 hash를 구독한다
export function useHashRoute(): Route {
  return parseRoute(useSyncExternalStore(subscribe, () => window.location.hash));
}
```
- [x] **Step 10: 통과 확인** — `npm test` PASS, `npm run lint`, `npm run build` 성공
- [x] **Step 11: `.gitignore`**의 Frontend 절에 `frontend/test-results/`, `frontend/playwright-report/` 추가
- [x] **Step 12: proxy 확인** — DB compose와 `bootRun`(local,mysql)을 띄운 상태에서 `npm run dev` 후:
  ```bash
  curl -si localhost:5173/api/rooms -H 'X-User-Id: 1' | head -20   # 200, X-Request-Id 헤더, ApiResponse 본문
  ```
  백엔드 콘솔의 `access` 줄에 같은 `requestId`가 있는지 본다. 결과를 보고하고 멈춘다.

---

## 작업 2. API 클라이언트

**Files:**
- Create: `frontend/src/api/types.ts`, `frontend/src/api/client.ts`, `frontend/src/api/client.test.ts`, `frontend/src/api/chat.ts`, `frontend/src/api/chat.test.ts`

**Interfaces:**
- Produces:
  - 타입 `User`, `Room`, `RoomPage`, `Member`, `Message`, `MessagePage`, `CallInfo = { status: number; requestId: string | null; durationMs: number }`, `ApiResult<T> = { data: T; info: CallInfo }`
  - `class ApiError extends Error { status: number; code: string; info: CallInfo }`, `errorMessage(e: unknown): string`
  - `apiFetch<T>(path, { method?, userId?, body? }): Promise<ApiResult<T>>`
  - `createUser(nickname)`, `listRooms(userId, cursor?)`, `createRoom(userId, name)`, `joinRoom(userId, roomId)`, `leaveRoom(userId, roomId)`, `sendMessage(userId, roomId, content)`, `readMessages(userId, roomId, query?: { after?: number; before?: number })` — 모두 `Promise<ApiResult<…>>`

- [x] **Step 1: 타입** `src/api/types.ts`

```ts
// 백엔드 DTO와 같은 모양 (RoomResponse, MessageResponse 등). Instant는 ISO 문자열로 온다
export type ApiBody<T> = {
  success: boolean;
  data: T | null;
  error: { code: string; message: string } | null;
};
export type User = { id: number; nickname: string };
export type Room = { id: number; name: string; createdBy: number; lastMessageId: number | null; createdAt: string };
export type RoomPage = { rooms: Room[]; hasMore: boolean; nextCursor: string | null };
export type Member = { roomId: number; userId: number };
export type Message = { id: number; roomId: number; senderId: number; content: string; createdAt: string };
export type MessagePage = { messages: Message[]; hasMore: boolean };
```
- [x] **Step 2: 실패하는 테스트** `src/api/client.test.ts`

```ts
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, apiFetch, errorMessage } from './client';

function respond(status: number, body: unknown, requestId = 'r-1') {
  const text = typeof body === 'string' ? body : JSON.stringify(body);
  return vi.fn().mockResolvedValue(new Response(text, { status, headers: { 'X-Request-Id': requestId } }));
}

describe('apiFetch', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('성공하면 data와 상태·요청 id를 돌려준다', async () => {
    vi.stubGlobal('fetch', respond(200, { success: true, data: { id: 1 }, error: null }));
    const result = await apiFetch<{ id: number }>('/api/rooms', { userId: 3 });
    expect(result.data).toEqual({ id: 1 });
    expect(result.info.status).toBe(200);
    expect(result.info.requestId).toBe('r-1');
    expect(result.info.durationMs).toBeGreaterThanOrEqual(0);
  });

  it('X-User-Id와 JSON 본문을 보낸다', async () => {
    const fetch = respond(201, { success: true, data: null, error: null });
    vi.stubGlobal('fetch', fetch);
    await apiFetch('/api/rooms', { method: 'POST', userId: 3, body: { name: '방' } });
    const [path, init] = fetch.mock.calls[0];
    expect(path).toBe('/api/rooms');
    expect(init.method).toBe('POST');
    expect(init.headers).toEqual({ 'X-User-Id': '3', 'Content-Type': 'application/json' });
    expect(init.body).toBe('{"name":"방"}');
  });

  it('사용자가 없으면 X-User-Id를 보내지 않는다', async () => {
    const fetch = respond(201, { success: true, data: { id: 1, nickname: 'a' }, error: null });
    vi.stubGlobal('fetch', fetch);
    await apiFetch('/api/dev/users', { method: 'POST', body: { nickname: 'a' } });
    expect(fetch.mock.calls[0][1].headers).toEqual({ 'Content-Type': 'application/json' });
  });

  it('실패 응답은 ApiError로 바꾼다', async () => {
    vi.stubGlobal('fetch', respond(403, { success: false, data: null, error: { code: 'NOT_A_MEMBER', message: '멤버가 아닙니다.' } }, 'r-9'));
    const error = await apiFetch('/api/rooms/1/messages', { userId: 3 }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 403, code: 'NOT_A_MEMBER', message: '멤버가 아닙니다.' });
    expect((error as ApiError).info.requestId).toBe('r-9');
  });

  it('JSON이 아닌 응답(예: 백엔드가 꺼져 proxy가 502)도 ApiError', async () => {
    vi.stubGlobal('fetch', respond(502, '<html>Bad Gateway</html>'));
    await expect(apiFetch('/api/rooms', { userId: 3 })).rejects.toMatchObject({ status: 502, code: 'UNKNOWN', message: 'HTTP 502' });
  });
});

describe('errorMessage', () => {
  it('ApiError는 메시지와 코드를 함께 보여 준다', () => {
    const info = { status: 409, requestId: null, durationMs: 1 };
    expect(errorMessage(new ApiError(409, 'ALREADY_MEMBER', '이미 멤버입니다.', info))).toBe('이미 멤버입니다. (ALREADY_MEMBER)');
    expect(errorMessage(new TypeError('Failed to fetch'))).toBe('Failed to fetch');
  });
});
```
  `src/api/chat.test.ts`

```ts
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiFetch } from './client';
import { joinRoom, leaveRoom, listRooms, readMessages, sendMessage } from './chat';

vi.mock('./client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./client')>()),
  apiFetch: vi.fn(),
}));

describe('chat API 경로', () => {
  beforeEach(() => vi.mocked(apiFetch).mockReset());

  it('메시지 조회는 커서가 있을 때만 쿼리를 붙인다', () => {
    readMessages(3, 1);
    readMessages(3, 1, { after: 5 });
    readMessages(3, 1, { before: 9 });
    expect(vi.mocked(apiFetch).mock.calls.map((call) => call[0])).toEqual([
      '/api/rooms/1/messages',
      '/api/rooms/1/messages?after=5',
      '/api/rooms/1/messages?before=9',
    ]);
  });

  it('방 목록 커서는 인코딩한다', () => {
    listRooms(3, '-:3');
    listRooms(3);
    expect(vi.mocked(apiFetch).mock.calls.map((call) => call[0])).toEqual(['/api/rooms?cursor=-%3A3', '/api/rooms']);
  });

  it('입장·나가기·전송의 메서드와 경로', () => {
    joinRoom(3, 1);
    leaveRoom(3, 1);
    sendMessage(3, 1, '안녕');
    expect(vi.mocked(apiFetch).mock.calls).toEqual([
      ['/api/rooms/1/members', { method: 'POST', userId: 3 }],
      ['/api/rooms/1/members/me', { method: 'DELETE', userId: 3 }],
      ['/api/rooms/1/messages', { method: 'POST', userId: 3, body: { content: '안녕' } }],
    ]);
  });
});
```
- [x] **Step 3: 실패 확인** — `npm test` → 모듈 없음
- [x] **Step 4: 구현** `src/api/client.ts`

```ts
import type { ApiBody } from './types';

export type CallInfo = { status: number; requestId: string | null; durationMs: number };
export type ApiResult<T> = { data: T; info: CallInfo };

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly info: CallInfo;

  constructor(status: number, code: string, message: string, info: CallInfo) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.info = info;
  }
}

type Options = { method?: string; userId?: number | null; body?: unknown };

// ADR-020: 성공/실패를 success 하나로 가른다. 상태 코드와 X-Request-Id는 폴링 상태 패널이 쓴다
export async function apiFetch<T>(path: string, options: Options = {}): Promise<ApiResult<T>> {
  const headers: Record<string, string> = {};
  if (options.userId != null) headers['X-User-Id'] = String(options.userId);
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';

  const started = performance.now();
  const response = await fetch(path, {
    method: options.method ?? 'GET',
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  });
  const body = (await response.json().catch(() => null)) as ApiBody<T> | null;
  const info: CallInfo = {
    status: response.status,
    requestId: response.headers.get('X-Request-Id'),
    durationMs: Math.round(performance.now() - started),
  };

  if (body?.success) return { data: body.data as T, info };
  throw new ApiError(response.status, body?.error?.code ?? 'UNKNOWN', body?.error?.message ?? `HTTP ${response.status}`, info);
}

export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) return `${e.message} (${e.code})`;
  return e instanceof Error ? e.message : String(e);
}
```
  `src/api/chat.ts`

```ts
import { apiFetch } from './client';
import type { Member, Message, MessagePage, Room, RoomPage, User } from './types';

export type MessageQuery = { after?: number; before?: number };

export function createUser(nickname: string) {
  return apiFetch<User>('/api/dev/users', { method: 'POST', body: { nickname } });
}

export function listRooms(userId: number, cursor?: string | null) {
  const query = cursor ? `?cursor=${encodeURIComponent(cursor)}` : '';
  return apiFetch<RoomPage>(`/api/rooms${query}`, { userId });
}

export function createRoom(userId: number, name: string) {
  return apiFetch<Room>('/api/rooms', { method: 'POST', userId, body: { name } });
}

export function joinRoom(userId: number, roomId: number) {
  return apiFetch<Member>(`/api/rooms/${roomId}/members`, { method: 'POST', userId });
}

export function leaveRoom(userId: number, roomId: number) {
  return apiFetch<null>(`/api/rooms/${roomId}/members/me`, { method: 'DELETE', userId });
}

export function sendMessage(userId: number, roomId: number, content: string) {
  return apiFetch<Message>(`/api/rooms/${roomId}/messages`, { method: 'POST', userId, body: { content } });
}

export function readMessages(userId: number, roomId: number, query: MessageQuery = {}) {
  const params = new URLSearchParams();
  if (query.after !== undefined) params.set('after', String(query.after));
  if (query.before !== undefined) params.set('before', String(query.before));
  const qs = params.toString();
  return apiFetch<MessagePage>(`/api/rooms/${roomId}/messages${qs ? `?${qs}` : ''}`, { userId });
}
```
  (`chat.test.ts`에서 `readMessages(3, 1)` 호출 시 두 번째 인자가 `{ userId: 3 }`인지는 첫 테스트에서 검사하지 않는다. 경로만 본다.)
- [x] **Step 5: 통과 확인** — `npm test`, `npm run lint`, `npm run build`. 결과를 보고하고 멈춘다.

---

## 작업 3. 사용자 선택 화면과 App 연결

**Files:**
- Create: `frontend/src/pages/LoginPage.tsx`, `frontend/src/pages/LoginPage.test.tsx`, `frontend/src/pages/RoomListPage.tsx`(임시), `frontend/src/pages/ChatRoomPage.tsx`(임시), `frontend/src/App.test.tsx`
- Modify: `frontend/src/App.tsx`

**Interfaces:**
- Consumes: `createUser`, `errorMessage`, `parseUserId`, `loadUserId`/`saveUserId`/`clearUserId`, `useHashRoute`, `roomHash`, `ROOMS_HASH`
- Produces: `LoginPage({ onLogin: (userId: number) => void })`. App이 쓰는 페이지 props: `RoomListPage({ userId, onOpen: (roomId) => void })`, `ChatRoomPage({ userId, roomId, onBack: () => void })` (작업 4·6에서 구현을 채운다)

- [x] **Step 1: 실패하는 테스트** `src/pages/LoginPage.test.tsx`

```tsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as chat from '../api/chat';
import { ApiError } from '../api/client';
import { LoginPage } from './LoginPage';

vi.mock('../api/chat');

const info = { status: 201, requestId: 'r', durationMs: 1 };

describe('LoginPage', () => {
  beforeEach(() => vi.resetAllMocks());

  it('닉네임으로 새 사용자를 만들고 그 id로 시작한다', async () => {
    vi.mocked(chat.createUser).mockResolvedValue({ data: { id: 5, nickname: '지수' }, info });
    const onLogin = vi.fn();
    render(<LoginPage onLogin={onLogin} />);
    await userEvent.type(screen.getByLabelText('닉네임'), '지수');
    await userEvent.click(screen.getByRole('button', { name: '새 사용자로 시작' }));
    expect(chat.createUser).toHaveBeenCalledWith('지수');
    expect(onLogin).toHaveBeenCalledWith(5);
  });

  it('기존 id로 시작한다', async () => {
    const onLogin = vi.fn();
    render(<LoginPage onLogin={onLogin} />);
    await userEvent.type(screen.getByLabelText('사용자 id'), '12');
    await userEvent.click(screen.getByRole('button', { name: '이 id로 시작' }));
    expect(onLogin).toHaveBeenCalledWith(12);
  });

  it('형식이 틀린 id는 안내하고 시작하지 않는다', async () => {
    const onLogin = vi.fn();
    render(<LoginPage onLogin={onLogin} />);
    await userEvent.type(screen.getByLabelText('사용자 id'), '007');
    await userEvent.click(screen.getByRole('button', { name: '이 id로 시작' }));
    expect(screen.getByRole('alert')).toHaveTextContent('1 이상의 정수');
    expect(onLogin).not.toHaveBeenCalled();
  });

  it('서버가 거절한 닉네임은 서버 메시지를 보여 준다', async () => {
    vi.mocked(chat.createUser).mockRejectedValue(new ApiError(400, 'INVALID_REQUEST', '요청 값이 올바르지 않습니다.', { ...info, status: 400 }));
    render(<LoginPage onLogin={vi.fn()} />);
    await userEvent.type(screen.getByLabelText('닉네임'), ' ');
    await userEvent.click(screen.getByRole('button', { name: '새 사용자로 시작' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('INVALID_REQUEST');
  });
});
```
  `src/App.test.tsx`

```tsx
import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';

vi.mock('./pages/RoomListPage', () => ({ RoomListPage: () => <p>방 목록 화면</p> }));
vi.mock('./pages/ChatRoomPage', () => ({ ChatRoomPage: ({ roomId }: { roomId: number }) => <p>채팅방 {roomId}</p> }));

describe('App', () => {
  beforeEach(() => {
    sessionStorage.clear();
    window.location.hash = '';
  });

  it('사용자가 없으면 사용자 선택 화면', () => {
    render(<App />);
    expect(screen.getByRole('button', { name: '새 사용자로 시작' })).toBeInTheDocument();
  });

  it('사용자가 있으면 hash에 따라 방 목록 또는 채팅방', () => {
    sessionStorage.setItem('chat.userId', '3');
    window.location.hash = '#/rooms/7';
    render(<App />);
    expect(screen.getByText('채팅방 7')).toBeInTheDocument();
    expect(screen.getByText('사용자 #3')).toBeInTheDocument();
  });
});
```
- [x] **Step 2: 실패 확인** — `npm test`
- [x] **Step 3: 구현** `src/pages/LoginPage.tsx`

```tsx
import { useState } from 'react';
import type { FormEvent } from 'react';
import { createUser } from '../api/chat';
import { errorMessage } from '../api/client';
import { parseUserId } from '../session';

type Props = { onLogin: (userId: number) => void };

export function LoginPage({ onLogin }: Props) {
  const [nickname, setNickname] = useState('');
  const [idText, setIdText] = useState('');
  const [error, setError] = useState<string | null>(null);

  async function startAsNew(event: FormEvent) {
    event.preventDefault();
    try {
      const { data } = await createUser(nickname);
      onLogin(data.id);
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  function startAsExisting(event: FormEvent) {
    event.preventDefault();
    const id = parseUserId(idText);
    if (id === null) {
      setError('사용자 id는 1 이상의 정수입니다.');
      return;
    }
    onLogin(id);
  }

  return (
    <main className="login">
      <h1>chat</h1>
      <form onSubmit={startAsNew}>
        <input aria-label="닉네임" placeholder="닉네임" value={nickname} onChange={(e) => setNickname(e.target.value)} />
        <button type="submit">새 사용자로 시작</button>
      </form>
      <form onSubmit={startAsExisting}>
        <input aria-label="사용자 id" placeholder="seed 사용자 id" value={idText} onChange={(e) => setIdText(e.target.value)} />
        <button type="submit">이 id로 시작</button>
      </form>
      {error && <p role="alert">{error}</p>}
    </main>
  );
}
```
  임시 페이지 (작업 4·6에서 교체):

```tsx
// src/pages/RoomListPage.tsx
type Props = { userId: number; onOpen: (roomId: number) => void };
export function RoomListPage(_props: Props) {
  return <p>방 목록</p>;
}
```
```tsx
// src/pages/ChatRoomPage.tsx
type Props = { userId: number; roomId: number; onBack: () => void };
export function ChatRoomPage({ roomId }: Props) {
  return <p>방 #{roomId}</p>;
}
```
  (`_props`가 lint 규칙에 걸리면 `{ userId }: Props`로 받고 `<p>방 목록 {userId}</p>`로 쓴다.)

  `src/App.tsx`

```tsx
import { useState } from 'react';
import { ChatRoomPage } from './pages/ChatRoomPage';
import { LoginPage } from './pages/LoginPage';
import { RoomListPage } from './pages/RoomListPage';
import { ROOMS_HASH, roomHash, useHashRoute } from './route';
import { clearUserId, loadUserId, saveUserId } from './session';

export default function App() {
  const [userId, setUserId] = useState<number | null>(loadUserId);
  const route = useHashRoute();

  if (userId === null) {
    return (
      <LoginPage
        onLogin={(id) => {
          saveUserId(id);
          setUserId(id);
        }}
      />
    );
  }

  function switchUser() {
    clearUserId();
    setUserId(null);
    window.location.hash = '';
  }

  return (
    <div className="app">
      <header className="top">
        <span>사용자 #{userId}</span>
        <button onClick={switchUser}>사용자 바꾸기</button>
      </header>
      {route.page === 'room' ? (
        // 방을 옮기면 커서·메시지 상태를 새로 시작하도록 key로 다시 만든다
        <ChatRoomPage key={route.roomId} userId={userId} roomId={route.roomId} onBack={() => (window.location.hash = ROOMS_HASH)} />
      ) : (
        <RoomListPage userId={userId} onOpen={(roomId) => (window.location.hash = roomHash(roomId))} />
      )}
    </div>
  );
}
```
- [x] **Step 4: 통과 확인** — `npm test`, `npm run lint`, `npm run build`. 결과를 보고하고 멈춘다.

---

## 작업 4. 방 목록 화면

**Files:**
- Modify: `frontend/src/pages/RoomListPage.tsx`
- Create: `frontend/src/pages/RoomListPage.test.tsx`

**Interfaces:**
- Consumes: `listRooms`, `createRoom`, `errorMessage`, `Room`
- Produces: `RoomListPage({ userId: number; onOpen: (roomId: number) => void })`

- [x] **Step 1: 실패하는 테스트** `src/pages/RoomListPage.test.tsx`

```tsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as chat from '../api/chat';
import { ApiError } from '../api/client';
import type { Room } from '../api/types';
import { RoomListPage } from './RoomListPage';

vi.mock('../api/chat');

const info = { status: 200, requestId: 'r', durationMs: 1 };
const room = (id: number, name: string, lastMessageId: number | null = null): Room => ({
  id, name, createdBy: 1, lastMessageId, createdAt: '2026-10-07T00:00:00Z',
});

describe('RoomListPage', () => {
  beforeEach(() => vi.resetAllMocks());

  it('목록을 보여 주고 더 보기로 다음 커서를 읽어 이어 붙인다', async () => {
    vi.mocked(chat.listRooms)
      .mockResolvedValueOnce({ data: { rooms: [room(2, '둘째', 5), room(1, '첫째')], hasMore: true, nextCursor: '-:1' }, info })
      .mockResolvedValueOnce({ data: { rooms: [room(9, '셋째')], hasMore: false, nextCursor: null }, info });
    render(<RoomListPage userId={3} onOpen={vi.fn()} />);
    expect(await screen.findByRole('button', { name: '둘째' })).toBeInTheDocument();
    expect(screen.getByText('#2 · 마지막 메시지 #5')).toBeInTheDocument();
    expect(screen.getByText('#1 · 메시지 없음')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '더 보기' }));
    expect(chat.listRooms).toHaveBeenLastCalledWith(3, '-:1');
    expect(await screen.findByRole('button', { name: '셋째' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '둘째' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '더 보기' })).not.toBeInTheDocument();
  });

  it('방을 누르면 그 방을 연다', async () => {
    vi.mocked(chat.listRooms).mockResolvedValue({ data: { rooms: [room(2, '둘째')], hasMore: false, nextCursor: null }, info });
    const onOpen = vi.fn();
    render(<RoomListPage userId={3} onOpen={onOpen} />);
    await userEvent.click(await screen.findByRole('button', { name: '둘째' }));
    expect(onOpen).toHaveBeenCalledWith(2);
  });

  it('방을 만들면 만든 방을 연다', async () => {
    vi.mocked(chat.listRooms).mockResolvedValue({ data: { rooms: [], hasMore: false, nextCursor: null }, info });
    vi.mocked(chat.createRoom).mockResolvedValue({ data: room(10, '새 방'), info: { ...info, status: 201 } });
    const onOpen = vi.fn();
    render(<RoomListPage userId={3} onOpen={onOpen} />);
    await userEvent.type(screen.getByLabelText('방 이름'), '새 방');
    await userEvent.click(screen.getByRole('button', { name: '방 만들기' }));
    expect(chat.createRoom).toHaveBeenCalledWith(3, '새 방');
    expect(onOpen).toHaveBeenCalledWith(10);
  });

  it('없는 사용자로 방을 만들면 서버의 401 메시지를 보여 준다', async () => {
    vi.mocked(chat.listRooms).mockResolvedValue({ data: { rooms: [], hasMore: false, nextCursor: null }, info });
    vi.mocked(chat.createRoom).mockRejectedValue(new ApiError(401, 'UNAUTHENTICATED', '인증 정보가 없거나 올바르지 않습니다.', { ...info, status: 401 }));
    render(<RoomListPage userId={999} onOpen={vi.fn()} />);
    await userEvent.type(screen.getByLabelText('방 이름'), '방');
    await userEvent.click(screen.getByRole('button', { name: '방 만들기' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('UNAUTHENTICATED');
  });
});
```
- [x] **Step 2: 실패 확인** — `npm test`
- [x] **Step 3: 구현** `src/pages/RoomListPage.tsx`

```tsx
import { useCallback, useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { createRoom, listRooms } from '../api/chat';
import { errorMessage } from '../api/client';
import type { Room } from '../api/types';

type Props = { userId: number; onOpen: (roomId: number) => void };

export function RoomListPage({ userId, onOpen }: Props) {
  const [rooms, setRooms] = useState<Room[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [name, setName] = useState('');
  const [error, setError] = useState<string | null>(null);

  // 계획 4 세부 #13: 자동 갱신하지 않는다. 넘기는 중 순서가 바뀐 방의 누락(F27)도 보정하지 않는다
  const load = useCallback(
    async (cursor: string | null) => {
      try {
        const { data } = await listRooms(userId, cursor);
        setRooms((current) => (cursor ? [...current, ...data.rooms] : data.rooms));
        setNextCursor(data.nextCursor);
        setHasMore(data.hasMore);
        setError(null);
      } catch (e) {
        setError(errorMessage(e));
      }
    },
    [userId],
  );

  useEffect(() => {
    void load(null);
  }, [load]);

  async function create(event: FormEvent) {
    event.preventDefault();
    try {
      const { data } = await createRoom(userId, name);
      onOpen(data.id);
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <section className="rooms">
      <form onSubmit={create}>
        <input aria-label="방 이름" placeholder="방 이름" value={name} onChange={(e) => setName(e.target.value)} />
        <button type="submit">방 만들기</button>
      </form>
      <button onClick={() => void load(null)}>새로고침</button>
      {error && <p role="alert">{error}</p>}
      <ul aria-label="방 목록">
        {rooms.map((room) => (
          <li key={room.id}>
            <button onClick={() => onOpen(room.id)}>{room.name}</button>
            <small>
              #{room.id} · {room.lastMessageId === null ? '메시지 없음' : `마지막 메시지 #${room.lastMessageId}`}
            </small>
          </li>
        ))}
      </ul>
      {hasMore && <button onClick={() => void load(nextCursor)}>더 보기</button>}
    </section>
  );
}
```
- [x] **Step 4: 통과 확인** — `npm test`, `npm run lint`, `npm run build`. 결과를 보고하고 멈춘다.

---

## 작업 5. 메시지 병합과 폴링 훅

**Files:**
- Create: `frontend/src/messages/merge.ts`, `merge.test.ts`, `frontend/src/messages/usePolling.ts`, `usePolling.test.ts`

**Interfaces:**
- Consumes: `Message`, `CallInfo`, `ApiError`
- Produces:
  - `mergeMessages(current: Message[], incoming: Message[]): Message[]` (id 기준 중복 제거, id 오름차순), `lastId(messages: Message[]): number` (비면 0)
  - `type PollResult = { hasMore: boolean; info: CallInfo; count: number }`
  - `type PollStats = { requests: number; errors: number; received: number; last: CallInfo | null; lastError: string | null }`
  - `usePolling({ enabled: boolean; intervalMs: number; poll: () => Promise<PollResult> }): PollStats`
  - `POLL_INTERVALS = [500, 1000, 2000, 5000]`, `DEFAULT_POLL_INTERVAL_MS = 2000`

- [x] **Step 1: 실패하는 테스트** `src/messages/merge.test.ts`

```ts
import { describe, expect, it } from 'vitest';
import type { Message } from '../api/types';
import { lastId, mergeMessages } from './merge';

const msg = (id: number, content = `m${id}`): Message => ({ id, roomId: 1, senderId: 2, content, createdAt: '2026-10-07T00:00:00Z' });

describe('mergeMessages', () => {
  it('id로 중복을 없애고 id 순서로 정렬한다', () => {
    const merged = mergeMessages([msg(1), msg(3)], [msg(3), msg(2), msg(4)]);
    expect(merged.map((m) => m.id)).toEqual([1, 2, 3, 4]);
  });

  it('과거 메시지를 앞에 붙여도 순서를 지킨다', () => {
    expect(mergeMessages([msg(10), msg(11)], [msg(8), msg(9)]).map((m) => m.id)).toEqual([8, 9, 10, 11]);
  });
});

describe('lastId', () => {
  it('마지막 id, 비면 0', () => {
    expect(lastId([msg(4), msg(7)])).toBe(7);
    expect(lastId([])).toBe(0);
  });
});
```
  `src/messages/usePolling.test.ts`

```ts
import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import { usePolling } from './usePolling';
import type { PollResult } from './usePolling';

const info = { status: 200, requestId: 'r-1', durationMs: 3 };

async function advance(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

describe('usePolling', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('응답을 받은 뒤에야 다음 요청을 예약한다', async () => {
    let resolve: (result: PollResult) => void = () => {};
    const poll = vi.fn(() => new Promise<PollResult>((r) => (resolve = r)));
    renderHook(() => usePolling({ enabled: true, intervalMs: 1000, poll }));

    await advance(1000);
    expect(poll).toHaveBeenCalledTimes(1);
    await advance(5000);
    expect(poll).toHaveBeenCalledTimes(1);

    await act(async () => resolve({ hasMore: false, info, count: 0 }));
    await advance(1000);
    expect(poll).toHaveBeenCalledTimes(2);
  });

  it('hasMore면 기다리지 않고 바로 다시 요청하고 받은 수를 센다', async () => {
    const poll = vi.fn<() => Promise<PollResult>>()
      .mockResolvedValueOnce({ hasMore: true, info, count: 50 })
      .mockResolvedValue({ hasMore: false, info, count: 1 });
    const { result } = renderHook(() => usePolling({ enabled: true, intervalMs: 1000, poll }));

    await advance(1000);
    expect(poll).toHaveBeenCalledTimes(2);
    expect(result.current).toMatchObject({ requests: 2, received: 51, errors: 0, last: info });
  });

  it('오류도 세고 같은 주기로 계속한다', async () => {
    const denied = { ...info, status: 403, requestId: 'r-9' };
    const poll = vi.fn<() => Promise<PollResult>>()
      .mockRejectedValueOnce(new ApiError(403, 'NOT_A_MEMBER', '멤버가 아닙니다.', denied))
      .mockResolvedValue({ hasMore: false, info, count: 0 });
    const { result } = renderHook(() => usePolling({ enabled: true, intervalMs: 1000, poll }));

    await advance(1000);
    expect(result.current).toMatchObject({ requests: 1, errors: 1, last: denied, lastError: '멤버가 아닙니다.' });
    await advance(1000);
    expect(poll).toHaveBeenCalledTimes(2);
    expect(result.current).toMatchObject({ requests: 2, errors: 1, lastError: null });
  });

  it('enabled가 false가 되면 멈춘다', async () => {
    const poll = vi.fn<() => Promise<PollResult>>().mockResolvedValue({ hasMore: false, info, count: 0 });
    const { rerender } = renderHook(({ enabled }) => usePolling({ enabled, intervalMs: 1000, poll }), {
      initialProps: { enabled: true },
    });
    rerender({ enabled: false });
    await advance(5000);
    expect(poll).not.toHaveBeenCalled();
  });

  it('바꾼 주기는 다음 예약부터 적용한다', async () => {
    const poll = vi.fn<() => Promise<PollResult>>().mockResolvedValue({ hasMore: false, info, count: 0 });
    const { rerender } = renderHook(({ intervalMs }) => usePolling({ enabled: true, intervalMs, poll }), {
      initialProps: { intervalMs: 5000 },
    });
    await advance(5000);
    expect(poll).toHaveBeenCalledTimes(1);
    rerender({ intervalMs: 500 });
    await advance(5000);
    expect(poll).toHaveBeenCalledTimes(2);
    await advance(500);
    expect(poll).toHaveBeenCalledTimes(3);
  });
});
```
- [x] **Step 2: 실패 확인** — `npm test`
- [x] **Step 3: 구현** `src/messages/merge.ts`

```ts
import type { Message } from '../api/types';

// 폴링 응답과 내가 보낸 메시지가 겹쳐 오므로 id로 합친다
export function mergeMessages(current: Message[], incoming: Message[]): Message[] {
  const byId = new Map(current.map((m) => [m.id, m]));
  for (const m of incoming) byId.set(m.id, m);
  return [...byId.values()].sort((a, b) => a.id - b.id);
}

export function lastId(messages: Message[]): number {
  return messages.length === 0 ? 0 : messages[messages.length - 1].id;
}
```
  `src/messages/usePolling.ts`

```ts
import { useEffect, useRef, useState } from 'react';
import { ApiError } from '../api/client';
import type { CallInfo } from '../api/client';

export const POLL_INTERVALS = [500, 1000, 2000, 5000];
export const DEFAULT_POLL_INTERVAL_MS = 2000;

export type PollResult = { hasMore: boolean; info: CallInfo; count: number };
export type PollStats = { requests: number; errors: number; received: number; last: CallInfo | null; lastError: string | null };

type Options = { enabled: boolean; intervalMs: number; poll: () => Promise<PollResult> };

const EMPTY: PollStats = { requests: 0, errors: 0, received: 0, last: null, lastError: null };

export function usePolling({ enabled, intervalMs, poll }: Options): PollStats {
  const [stats, setStats] = useState<PollStats>(EMPTY);
  const pollRef = useRef(poll);
  const intervalRef = useRef(intervalMs);

  useEffect(() => {
    pollRef.current = poll;
    intervalRef.current = intervalMs;
  });

  useEffect(() => {
    if (!enabled) return;
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;

    // 계획 4 세부 #6: 응답을 받은 뒤 다음 요청을 예약해 한 탭의 요청이 겹치지 않게 한다
    // 계획 4 세부 #10: 오류가 나도 같은 주기로 계속한다 (장애 선행, 대기를 늘리지 않음)
    const tick = async () => {
      let delay = intervalRef.current;
      try {
        const result = await pollRef.current();
        if (stopped) return;
        setStats((s) => ({ ...s, requests: s.requests + 1, received: s.received + result.count, last: result.info, lastError: null }));
        // 계획 4 세부 #9: 한 번에 다 못 받았으면 다음 주기까지 기다리지 않는다
        if (result.hasMore) delay = 0;
      } catch (e) {
        if (stopped) return;
        setStats((s) => ({
          ...s,
          requests: s.requests + 1,
          errors: s.errors + 1,
          last: e instanceof ApiError ? e.info : s.last,
          lastError: e instanceof Error ? e.message : String(e),
        }));
      }
      timer = setTimeout(tick, delay);
    };

    timer = setTimeout(tick, intervalRef.current);
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, [enabled]);

  return stats;
}
```
- [x] **Step 4: 통과 확인** — `npm test`, `npm run lint`(hook 규칙 경고가 나오면 내용을 그대로 보고하고 의논), `npm run build`. 결과를 보고하고 멈춘다.

---

## 작업 6. 채팅방 화면

**Files:**
- Modify: `frontend/src/pages/ChatRoomPage.tsx`
- Create: `frontend/src/pages/ChatRoomPage.test.tsx`

**Interfaces:**
- Consumes: `readMessages`, `sendMessage`, `joinRoom`, `leaveRoom`, `ApiError`, `errorMessage`, `mergeMessages`, `lastId`, `usePolling`, `DEFAULT_POLL_INTERVAL_MS`
- Produces: `ChatRoomPage({ userId, roomId, onBack })`. 접근 가능한 이름: 버튼 `입장`·`나가기`·`보내기`·`이전 메시지 더 보기`·`방 목록으로`, 입력 `메시지`, 목록 `대화`(작업 8 E2E가 쓴다)

- [x] **Step 1: 실패하는 테스트** `src/pages/ChatRoomPage.test.tsx`

```tsx
import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as chat from '../api/chat';
import { ApiError } from '../api/client';
import type { Message } from '../api/types';
import { DEFAULT_POLL_INTERVAL_MS } from '../messages/usePolling';
import { ChatRoomPage } from './ChatRoomPage';

vi.mock('../api/chat');

const info = { status: 200, requestId: 'r', durationMs: 1 };
const msg = (id: number, senderId = 2, content = `m${id}`): Message => ({ id, roomId: 1, senderId, content, createdAt: '2026-10-07T00:00:00Z' });
const page = (messages: Message[], hasMore = false) => ({ data: { messages, hasMore }, info });

describe('ChatRoomPage', () => {
  beforeEach(() => vi.resetAllMocks());
  afterEach(() => vi.useRealTimers());

  it('멤버면 최신 메시지를 작성자 id와 함께 보여 준다', async () => {
    vi.mocked(chat.readMessages).mockResolvedValue(page([msg(1, 2, '안녕')]));
    render(<ChatRoomPage userId={1} roomId={1} onBack={vi.fn()} />);
    expect(await screen.findByText('안녕')).toBeInTheDocument();
    expect(screen.getByText('사용자 #2')).toBeInTheDocument();
    expect(chat.readMessages).toHaveBeenCalledWith(1, 1);
  });

  it('멤버가 아니면 입장 버튼을 보이고, 입장하면 메시지를 읽는다', async () => {
    vi.mocked(chat.readMessages)
      .mockRejectedValueOnce(new ApiError(403, 'NOT_A_MEMBER', '멤버가 아닙니다.', { ...info, status: 403 }))
      .mockResolvedValue(page([msg(5, 2, '입장 후')]));
    vi.mocked(chat.joinRoom).mockResolvedValue({ data: { roomId: 1, userId: 1 }, info: { ...info, status: 201 } });
    render(<ChatRoomPage userId={1} roomId={1} onBack={vi.fn()} />);
    await userEvent.click(await screen.findByRole('button', { name: '입장' }));
    expect(chat.joinRoom).toHaveBeenCalledWith(1, 1);
    expect(await screen.findByText('입장 후')).toBeInTheDocument();
  });

  it('입장이 409(이미 멤버)면 그대로 다시 읽는다', async () => {
    vi.mocked(chat.readMessages)
      .mockRejectedValueOnce(new ApiError(403, 'NOT_A_MEMBER', '멤버가 아닙니다.', { ...info, status: 403 }))
      .mockResolvedValue(page([msg(5, 2, '이미 멤버')]));
    vi.mocked(chat.joinRoom).mockRejectedValue(new ApiError(409, 'ALREADY_MEMBER', '이미 멤버입니다.', { ...info, status: 409 }));
    render(<ChatRoomPage userId={1} roomId={1} onBack={vi.fn()} />);
    await userEvent.click(await screen.findByRole('button', { name: '입장' }));
    expect(await screen.findByText('이미 멤버')).toBeInTheDocument();
  });

  it('보낸 메시지는 바로 보이지만 폴링 커서는 조회 응답으로만 전진한다 (세부 #8)', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    vi.mocked(chat.readMessages)
      .mockResolvedValueOnce(page([msg(10)]))
      .mockResolvedValueOnce(page([msg(11, 3, '남의 메시지'), msg(12, 1, '내 메시지')]))
      .mockResolvedValue(page([]));
    vi.mocked(chat.sendMessage).mockResolvedValue({ data: msg(12, 1, '내 메시지'), info: { ...info, status: 201 } });
    render(<ChatRoomPage userId={1} roomId={1} onBack={vi.fn()} />);
    await screen.findByText('m10');

    await user.type(screen.getByLabelText('메시지'), '내 메시지');
    await user.click(screen.getByRole('button', { name: '보내기' }));
    expect(await screen.findByText('내 메시지')).toBeInTheDocument();
    expect(screen.getByLabelText('메시지')).toHaveValue('');

    await act(async () => {
      await vi.advanceTimersByTimeAsync(DEFAULT_POLL_INTERVAL_MS);
    });
    expect(chat.readMessages).toHaveBeenNthCalledWith(2, 1, 1, { after: 10 });
    expect(await screen.findByText('남의 메시지')).toBeInTheDocument();
    const items = within(screen.getByRole('list', { name: '대화' })).getAllByRole('listitem');
    expect(items.map((li) => li.textContent)).toEqual(['사용자 #2m10', '사용자 #3남의 메시지', '사용자 #1내 메시지']);
  });

  it('이전 메시지 더 보기는 가장 오래된 id를 before로 보낸다', async () => {
    vi.mocked(chat.readMessages)
      .mockResolvedValueOnce(page([msg(20), msg(21)], true))
      .mockResolvedValueOnce(page([msg(18), msg(19)], false));
    render(<ChatRoomPage userId={1} roomId={1} onBack={vi.fn()} />);
    await userEvent.click(await screen.findByRole('button', { name: '이전 메시지 더 보기' }));
    expect(chat.readMessages).toHaveBeenLastCalledWith(1, 1, { before: 20 });
    expect(await screen.findByText('m18')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '이전 메시지 더 보기' })).not.toBeInTheDocument();
  });

  it('전송이 거절되면 서버 메시지를 보여 준다', async () => {
    vi.mocked(chat.readMessages).mockResolvedValue(page([]));
    vi.mocked(chat.sendMessage).mockRejectedValue(new ApiError(403, 'NOT_A_MEMBER', '멤버가 아닙니다.', { ...info, status: 403 }));
    render(<ChatRoomPage userId={1} roomId={1} onBack={vi.fn()} />);
    await userEvent.type(await screen.findByLabelText('메시지'), '안녕');
    await userEvent.click(screen.getByRole('button', { name: '보내기' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('NOT_A_MEMBER');
  });

  it('나가면 방 목록으로 돌아간다', async () => {
    vi.mocked(chat.readMessages).mockResolvedValue(page([]));
    vi.mocked(chat.leaveRoom).mockResolvedValue({ data: null, info });
    const onBack = vi.fn();
    render(<ChatRoomPage userId={1} roomId={1} onBack={onBack} />);
    await userEvent.click(await screen.findByRole('button', { name: '나가기' }));
    expect(chat.leaveRoom).toHaveBeenCalledWith(1, 1);
    expect(onBack).toHaveBeenCalled();
  });
});
```
- [x] **Step 2: 실패 확인** — `npm test`
- [x] **Step 3: 구현** `src/pages/ChatRoomPage.tsx`

```tsx
import { useCallback, useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { joinRoom, leaveRoom, readMessages, sendMessage } from '../api/chat';
import { ApiError, errorMessage } from '../api/client';
import type { Message } from '../api/types';
import { lastId, mergeMessages } from '../messages/merge';
import { DEFAULT_POLL_INTERVAL_MS, usePolling } from '../messages/usePolling';

type Status = 'loading' | 'notMember' | 'ready' | 'error';
type Props = { userId: number; roomId: number; onBack: () => void };

export function ChatRoomPage({ userId, roomId, onBack }: Props) {
  const [status, setStatus] = useState<Status>('loading');
  const [messages, setMessages] = useState<Message[]>([]);
  const [hasOlder, setHasOlder] = useState(false);
  const [draft, setDraft] = useState('');
  const [error, setError] = useState<string | null>(null);
  // 계획 4 세부 #8: 커서는 조회 응답으로만 전진한다. 내 메시지 id로 옮기면
  // 이미 커밋됐지만 아직 받지 못한 더 작은 id의 남의 메시지를 영원히 건너뛴다
  const cursorRef = useRef(0);
  const listRef = useRef<HTMLOListElement>(null);

  const loadLatest = useCallback(async () => {
    try {
      const { data } = await readMessages(userId, roomId);
      cursorRef.current = lastId(data.messages);
      setMessages(data.messages);
      setHasOlder(data.hasMore);
      setStatus('ready');
      setError(null);
    } catch (e) {
      // 계획 4 세부 #11: 멤버 여부 API가 없으므로 서버의 인가 결과로 판단한다
      if (e instanceof ApiError && e.code === 'NOT_A_MEMBER') {
        setStatus('notMember');
        return;
      }
      setStatus('error');
      setError(errorMessage(e));
    }
  }, [userId, roomId]);

  useEffect(() => {
    void loadLatest();
  }, [loadLatest]);

  const poll = useCallback(async () => {
    const { data, info } = await readMessages(userId, roomId, { after: cursorRef.current });
    if (data.messages.length > 0) {
      cursorRef.current = lastId(data.messages);
      setMessages((current) => mergeMessages(current, data.messages));
    }
    return { hasMore: data.hasMore, info, count: data.messages.length };
  }, [userId, roomId]);

  usePolling({ enabled: status === 'ready', intervalMs: DEFAULT_POLL_INTERVAL_MS, poll });

  const newestId = lastId(messages);
  useEffect(() => {
    const list = listRef.current;
    if (list) list.scrollTop = list.scrollHeight;
  }, [newestId]);

  async function join() {
    try {
      await joinRoom(userId, roomId);
    } catch (e) {
      if (!(e instanceof ApiError && e.code === 'ALREADY_MEMBER')) {
        setError(errorMessage(e));
        return;
      }
    }
    await loadLatest();
  }

  async function send(event: FormEvent) {
    event.preventDefault();
    try {
      const { data } = await sendMessage(userId, roomId, draft);
      setMessages((current) => mergeMessages(current, [data]));
      setDraft('');
      setError(null);
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  async function loadOlder() {
    try {
      const { data } = await readMessages(userId, roomId, { before: messages[0].id });
      setMessages((current) => mergeMessages(current, data.messages));
      setHasOlder(data.hasMore);
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  async function leave() {
    try {
      await leaveRoom(userId, roomId);
      onBack();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <section className="chat">
      <div className="toolbar">
        <button onClick={onBack}>방 목록으로</button>
        <h2>방 #{roomId}</h2>
        {status === 'ready' && <button onClick={() => void leave()}>나가기</button>}
      </div>
      {error && <p role="alert">{error}</p>}
      {status === 'loading' && <p>불러오는 중…</p>}
      {status === 'notMember' && (
        <div className="join">
          <p>이 방의 멤버가 아닙니다.</p>
          <button onClick={() => void join()}>입장</button>
        </div>
      )}
      {status === 'ready' && (
        <div className="conversation">
          {hasOlder && <button onClick={() => void loadOlder()}>이전 메시지 더 보기</button>}
          <ol aria-label="대화" className="messages" ref={listRef}>
            {messages.map((m) => (
              <li key={m.id}>
                <span className="sender">사용자 #{m.senderId}</span>
                <span className="content">{m.content}</span>
              </li>
            ))}
          </ol>
          {/* 계획 4 세부 #14: 길이·문자 검사는 서버 한 곳에서 하고 400 메시지를 보여 준다 */}
          <form onSubmit={send}>
            <input aria-label="메시지" value={draft} onChange={(e) => setDraft(e.target.value)} />
            <button type="submit" disabled={draft.length === 0}>보내기</button>
          </form>
        </div>
      )}
    </section>
  );
}
```
- [x] **Step 4: 통과 확인** — `npm test`, `npm run lint`, `npm run build`. 결과를 보고하고 멈춘다.

---

## 작업 7. 폴링 상태 패널과 화면 배치

**Files:**
- Create: `frontend/src/components/PollingPanel.tsx`, `frontend/src/components/PollingPanel.test.tsx`
- Modify: `frontend/src/pages/ChatRoomPage.tsx`, `frontend/src/pages/ChatRoomPage.test.tsx`, `frontend/src/styles.css`

**Interfaces:**
- Consumes: `PollStats`, `POLL_INTERVALS`
- Produces: `PollingPanel({ stats, cursor, intervalMs, paused, onIntervalChange, onTogglePause })`. 접근 가능한 이름: 영역 `폴링 상태`, 선택 `폴링 주기`, 버튼 `일시정지`/`다시 시작`

- [x] **Step 1: 실패하는 테스트** `src/components/PollingPanel.test.tsx`

```tsx
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { PollingPanel } from './PollingPanel';

const stats = { requests: 3, errors: 1, received: 7, last: { status: 200, requestId: 'r-9', durationMs: 12 }, lastError: null };

describe('PollingPanel', () => {
  it('커서, 요청 수, 마지막 응답과 요청 id를 보여 준다', () => {
    render(<PollingPanel stats={stats} cursor={10} intervalMs={2000} paused={false} onIntervalChange={vi.fn()} onTogglePause={vi.fn()} />);
    const panel = within(screen.getByRole('complementary', { name: '폴링 상태' }));
    expect(panel.getByText('10')).toBeInTheDocument();
    expect(panel.getByText('3')).toBeInTheDocument();
    expect(panel.getByText('200 · 12ms')).toBeInTheDocument();
    expect(panel.getByText('r-9')).toBeInTheDocument();
    expect(panel.queryByText('마지막 오류')).not.toBeInTheDocument();
  });

  it('마지막 오류가 있으면 보여 준다', () => {
    render(<PollingPanel stats={{ ...stats, lastError: '멤버가 아닙니다.' }} cursor={0} intervalMs={2000} paused={false} onIntervalChange={vi.fn()} onTogglePause={vi.fn()} />);
    expect(screen.getByText('멤버가 아닙니다.')).toBeInTheDocument();
  });

  it('주기 변경과 일시정지를 알린다', async () => {
    const onIntervalChange = vi.fn();
    const onTogglePause = vi.fn();
    render(<PollingPanel stats={stats} cursor={0} intervalMs={2000} paused={false} onIntervalChange={onIntervalChange} onTogglePause={onTogglePause} />);
    await userEvent.selectOptions(screen.getByLabelText('폴링 주기'), '500');
    expect(onIntervalChange).toHaveBeenCalledWith(500);
    await userEvent.click(screen.getByRole('button', { name: '일시정지' }));
    expect(onTogglePause).toHaveBeenCalled();
  });
});
```
  `ChatRoomPage.test.tsx`에 추가:

```tsx
  it('폴링 패널에 커서를 보이고, 일시정지하면 폴링하지 않는다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    vi.mocked(chat.readMessages).mockResolvedValue(page([msg(10)]));
    render(<ChatRoomPage userId={1} roomId={1} onBack={vi.fn()} />);
    const panel = within(await screen.findByRole('complementary', { name: '폴링 상태' }));
    expect(panel.getByText('10')).toBeInTheDocument();

    await user.click(panel.getByRole('button', { name: '일시정지' }));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(DEFAULT_POLL_INTERVAL_MS * 3);
    });
    expect(chat.readMessages).toHaveBeenCalledTimes(1);
    expect(panel.getByRole('button', { name: '다시 시작' })).toBeInTheDocument();
  });
```
- [x] **Step 2: 실패 확인** — `npm test`
- [x] **Step 3: 구현** `src/components/PollingPanel.tsx`

```tsx
import { POLL_INTERVALS } from '../messages/usePolling';
import type { PollStats } from '../messages/usePolling';

type Props = {
  stats: PollStats;
  cursor: number;
  intervalMs: number;
  paused: boolean;
  onIntervalChange: (ms: number) => void;
  onTogglePause: () => void;
};

// 계획 4 세부 #1: X-Request-Id로 Kibana·app.json의 같은 요청 로그를 찾는다
export function PollingPanel({ stats, cursor, intervalMs, paused, onIntervalChange, onTogglePause }: Props) {
  return (
    <aside aria-label="폴링 상태" className="panel">
      <div className="controls">
        <select aria-label="폴링 주기" value={intervalMs} onChange={(e) => onIntervalChange(Number(e.target.value))}>
          {POLL_INTERVALS.map((ms) => (
            <option key={ms} value={ms}>
              {ms / 1000}초
            </option>
          ))}
        </select>
        <button onClick={onTogglePause}>{paused ? '다시 시작' : '일시정지'}</button>
      </div>
      <dl>
        <dt>after 커서</dt>
        <dd>{cursor}</dd>
        <dt>요청</dt>
        <dd>{stats.requests}</dd>
        <dt>오류</dt>
        <dd>{stats.errors}</dd>
        <dt>받은 메시지</dt>
        <dd>{stats.received}</dd>
        <dt>마지막 응답</dt>
        <dd>{stats.last ? `${stats.last.status} · ${stats.last.durationMs}ms` : '-'}</dd>
        <dt>X-Request-Id</dt>
        <dd>{stats.last?.requestId ?? '-'}</dd>
        {stats.lastError && (
          <>
            <dt>마지막 오류</dt>
            <dd>{stats.lastError}</dd>
          </>
        )}
      </dl>
    </aside>
  );
}
```
  `ChatRoomPage.tsx` 수정:
  - import 추가: `import { PollingPanel } from '../components/PollingPanel';`
  - 상태 추가 (`cursorRef` 아래):
    ```tsx
    const [cursorView, setCursorView] = useState(0);
    const [intervalMs, setIntervalMs] = useState(DEFAULT_POLL_INTERVAL_MS);
    const [paused, setPaused] = useState(false);
    ```
  - `cursorRef.current = lastId(...)`가 있는 두 곳(`loadLatest`, `poll`) 바로 다음 줄에 `setCursorView(cursorRef.current);`
  - 폴링 호출을 다음으로 바꾼다:
    ```tsx
    const stats = usePolling({ enabled: status === 'ready' && !paused, intervalMs, poll });
    ```
  - `status === 'ready'` 블록의 `<div className="conversation">…</div>` 바로 뒤(같은 조건 안)에:
    ```tsx
    <PollingPanel
      stats={stats}
      cursor={cursorView}
      intervalMs={intervalMs}
      paused={paused}
      onIntervalChange={setIntervalMs}
      onTogglePause={() => setPaused((p) => !p)}
    />
    ```
    (`status === 'ready' && (<div className="conversation">…</div>)`를 `status === 'ready' && (<div className="room-body"><div className="conversation">…</div><PollingPanel … /></div>)`로 감싼다.)
- [x] **Step 4: `src/styles.css`**

```css
:root { font-family: system-ui, sans-serif; color-scheme: light dark; }
body { margin: 0; }
.login, .app { max-width: 960px; margin: 0 auto; padding: 16px; }
.login form, .rooms form, .conversation form { display: flex; gap: 8px; margin: 8px 0; }
.top, .toolbar { display: flex; align-items: center; gap: 12px; justify-content: space-between; }
.rooms ul { list-style: none; padding: 0; }
.rooms li { display: flex; gap: 8px; align-items: baseline; padding: 4px 0; }
.room-body { display: grid; grid-template-columns: 1fr 240px; gap: 16px; }
.messages { list-style: none; padding: 8px; margin: 0; height: 60vh; overflow-y: auto; border: 1px solid #8884; }
.messages li { padding: 2px 0; }
.sender { font-weight: 600; margin-right: 8px; }
.panel { border: 1px solid #8884; padding: 8px; font-size: 13px; }
.panel dl { display: grid; grid-template-columns: auto 1fr; gap: 4px 8px; margin: 8px 0 0; }
.panel dd { margin: 0; word-break: break-all; }
[role='alert'] { color: #c33; }
@media (max-width: 640px) { .room-body { grid-template-columns: 1fr; } }
```
- [x] **Step 5: 통과 확인** — `npm test`, `npm run lint`, `npm run build`. 결과를 보고하고 멈춘다.

---

## 작업 8. Playwright E2E

**Files:**
- Create: `frontend/playwright.config.ts`, `frontend/e2e/chat.spec.ts`
- Modify: `frontend/package.json` (devDependency `@playwright/test` 고정, script `"e2e": "playwright test"`)

**Interfaces:**
- Consumes: 작업 3·4·6·7의 접근 가능한 이름(`닉네임`, `새 사용자로 시작`, `방 이름`, `방 만들기`, `입장`, `메시지`, `보내기`, `나가기`, 목록 `대화`)

- [x] **Step 1: 설치** — `npm install --save-dev --save-exact @playwright/test` 후 `npx playwright install chromium` (브라우저 내려받기. 실행 전에 사용자에게 알린다)
- [x] **Step 2: `playwright.config.ts`**

```ts
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: 'e2e',
  // 같은 로컬 DB를 쓰므로 순서대로 실행한다
  workers: 1,
  use: { baseURL: 'http://localhost:5173', trace: 'retain-on-failure' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  // 계획 4 세부 #16: /api/dev/users가 열리는 local 프로필이어야 사용자를 만들 수 있다. DB compose는 미리 띄운다
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
});
```
- [x] **Step 3: 테스트** `e2e/chat.spec.ts`

```ts
import { expect, test } from '@playwright/test';
import type { Browser, Page } from '@playwright/test';

// 기본 폴링 주기 2초(세부 #7)보다 넉넉하게 기다린다
const POLL_TIMEOUT = 10_000;

async function newUser(browser: Browser, nickname: string): Promise<Page> {
  const page = await (await browser.newContext()).newPage();
  await page.goto('/');
  await page.getByLabel('닉네임').fill(nickname);
  await page.getByRole('button', { name: '새 사용자로 시작' }).click();
  await expect(page.getByRole('button', { name: '방 만들기' })).toBeVisible();
  return page;
}

async function send(page: Page, text: string) {
  await page.getByLabel('메시지').fill(text);
  await page.getByRole('button', { name: '보내기' }).click();
  await expect(page.getByRole('list', { name: '대화' })).toContainText(text);
}

async function enter(page: Page, roomHash: string) {
  await page.goto(`/${roomHash}`);
  await page.getByRole('button', { name: '입장' }).click();
  await expect(page.getByLabel('메시지')).toBeVisible();
}

test('두 사용자가 폴링으로 대화하고, 다시 입장하면 이전 메시지가 보이지 않는다 (R4)', async ({ browser }) => {
  const suffix = Date.now().toString(36);
  const a = await newUser(browser, `a-${suffix}`);
  const b = await newUser(browser, `b-${suffix}`);

  await a.getByLabel('방 이름').fill(`e2e-${suffix}`);
  await a.getByRole('button', { name: '방 만들기' }).click();
  await expect(a).toHaveURL(/#\/rooms\/\d+$/);
  const roomHash = new URL(a.url()).hash;

  await enter(b, roomHash);
  await send(a, '안녕 B');
  await expect(b.getByRole('list', { name: '대화' })).toContainText('안녕 B', { timeout: POLL_TIMEOUT });

  await b.getByRole('button', { name: '나가기' }).click();
  await expect(b.getByRole('button', { name: '방 만들기' })).toBeVisible();
  await send(a, '나간 뒤 메시지');

  await enter(b, roomHash);
  await send(b, '다시 왔어');
  await expect(a.getByRole('list', { name: '대화' })).toContainText('다시 왔어', { timeout: POLL_TIMEOUT });
  await expect(b.getByRole('list', { name: '대화' })).not.toContainText('안녕 B');
  await expect(b.getByRole('list', { name: '대화' })).not.toContainText('나간 뒤 메시지');
});

test('비멤버가 방 링크를 열면 입장 버튼이 보이고 메시지는 보이지 않는다', async ({ browser }) => {
  const suffix = Date.now().toString(36);
  const a = await newUser(browser, `a2-${suffix}`);
  await a.getByLabel('방 이름').fill(`e2e2-${suffix}`);
  await a.getByRole('button', { name: '방 만들기' }).click();
  await expect(a).toHaveURL(/#\/rooms\/\d+$/);
  await send(a, '비밀');

  const c = await newUser(browser, `c-${suffix}`);
  await c.goto(`/${new URL(a.url()).hash}`);
  await expect(c.getByRole('button', { name: '입장' })).toBeVisible();
  await expect(c.getByText('비밀')).toHaveCount(0);
});
```
- [x] **Step 4: 실행** — `docker compose -f infra/compose.db.yml up -d --wait` 후 `cd frontend && npm run e2e`. 두 테스트 PASS. 실패하면 trace를 보고 원인을 보고한다(테스트를 느슨하게 바꿔 통과시키지 않는다).
- [x] **Step 5: 확인** — `npm test`가 `e2e/`를 포함하지 않는지(작업 1의 `include`) 보고, 백엔드 `./gradlew test`도 그대로 통과하는지 확인한다. 결과를 보고하고 멈춘다.

---

## 작업 9. 직접 확인과 문서 반영

- [x] **Step 1: 브라우저로 직접 확인** (DB compose + `bootRun local,mysql` + `npm run dev`, 필요하면 `--profile metrics`, `--profile logs`). 결과는 "측정/관찰"로 적는다.
  1. 창 두 개(일반 + 시크릿, 또는 새 탭 두 개)에서 다른 사용자로 시작 → 방 생성 → 입장 → 대화 → 나가기 → 재입장
  2. 개발자 도구 Network: 폴링 요청에 `OPTIONS`(preflight)가 없는지 본다 (ADR-028의 예상을 실제로 확인)
  3. 패널 주기를 0.5초로 바꾸고 Grafana "chat Step 1"의 요청 수 변화를 본다
  4. 패널의 `X-Request-Id`로 `backend/logs/app.json`(또는 Kibana `app-*`)을 검색해 같은 요청의 `access` 로그를 찾는다. `clientIp`가 무엇으로 찍히는지 적는다(세부 #15)
  5. 방을 만든 뒤 목록으로 돌아가 새 방이 어디에 보이는지 적는다 (README 미결정 "새 방이 맨 아래에 보이는 문제"의 관찰 자료)
  6. 비멤버로 방을 열어 둔 채(입장하지 않음) 다른 탭에서 나가기 → 패널 오류 수와 `audit.json`의 `ACCESS_DENIED`가 늘어나는지 본다 (F28 재현 가능성 관찰. 고치지 않는다)
- [x] **Step 2: `docs/adr/{진행한 날짜}.md`**: ADR-081부터 세부 #1~#16 중 승인·확인된 것, 설치된 정확한 버전(Node, vite, react, typescript, vitest, Playwright). 측정·관찰한 내용과 예상을 구분한다
- [x] **Step 3: `docs/design/architecture.md`**의 "저장소 구조와 같은 주소(origin) 서비스": 프론트 구조(hash 화면 전환, `usePolling`, 커서 규칙 #8), 개발 명령, preflight 확인 결과
- [x] **Step 4: `docs/failure-lab.md`** (상태 요약 표 포함): F26의 측정 시점을 "계획 4에서 닉네임을 표시하지 않기로 해서 이후로 미룸"으로 고친다. 작업 중 발견한 위험을 가설로 추가한다. 지금 예상하는 후보(관찰한 것만 사용자와 의논해 기록):
  - 탭마다 폴링하므로 한 사용자가 탭 N개를 열면 폴링 부하가 N배가 된다 (F1 조건)
  - 오류가 나도 같은 주기로 재시도하므로 백엔드 장애 중 요청이 줄지 않는다 (세부 #10)
  - 백그라운드 탭에서는 브라우저가 타이머를 늦춰 실제 폴링 주기가 설정보다 길어진다
  - id를 JS `Number`로 받으므로 2^53을 넘으면 정밀도를 잃는다 (현재 규모에서는 예상만)
- [x] **Step 5: 설계 문서·README·CLAUDE.md**: 설계 문서 10장 1번(화면 범위)을 해결로 옮긴다. `docs/README.md` 현재 상태(계획 4 완료, ADR 범위, 다음: 계획 5)와 미결정 목록(화면 범위 삭제, 새 방 위치는 관찰 결과 링크). `CLAUDE.md`의 "현재 위치"와 명령어(`cd frontend && npm run dev | npm test | npm run e2e`)
- [x] **Step 6: `docs/journal/{진행한 날짜}.md`**: 한 일, 예상과 다르게 나온 것, 남은 것
- [x] **Step 7: 최종 확인과 보고** — `cd frontend && npm test && npm run lint && npm run build && npm run e2e`, `cd backend && ./gradlew test`. 결과를 보고하고 커밋 여부를 묻는다.

---

## 확인 방법 (끝까지)
1. `cd frontend && npm test`: session, route, api, merge, usePolling, 페이지 3개, 패널 단위 테스트 통과
2. `npm run lint && npm run build`: 경고·타입 오류 없음
3. DB compose 후 `npm run e2e`: 두 사용자 대화·재입장 경계(R4)·비멤버 입장 버튼 E2E 통과
4. `cd backend && ./gradlew test`: 기존 테스트 그대로 통과 (백엔드 변경 없음)
5. 브라우저 직접 확인: preflight 없음, 패널의 `X-Request-Id`로 접근 로그를 찾음, 주기 변경이 Grafana 요청 수에 보임

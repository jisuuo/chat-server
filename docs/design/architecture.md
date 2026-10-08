# 아키텍처

> 누적 문서. Step 1 설계 문서(`../superpowers/specs/2026-10-06-chat-server-step1-design.md`)의 근거가 되는 구조 결정 상세.

## 인증 구조 (ADR-005, 006)

```
             [1. 추출: 통로별]                 [2. 판별: 단 한 곳]           [3. 전달: 사용처별]
HTTP 요청  → AuthFilter (X-User-Id 헤더)    ┐                              → @CurrentUser AuthUser
                                            ├→ Authenticator.authenticate()
WebSocket  → QueryUserIdHandshakeInterceptor┘   지금: HeaderUserIdAuthenticator → WebSocketSession attributes
             (`/ws?userId=`, ADR-130)           나중: JwtAuthenticator

[인가] "이 유저가 이 방의 멤버인가?"는 인증 층이 아니라 도메인(멤버십, R3)이 판단한다. 흐름은 MessageService가 맡는다 (ADR-037).
```

- `Authenticator`는 `HttpServletRequest`에 의존하지 않는다. 문자열 credential만 받는다.
- 반환 타입은 `Long`이 아니라 불변 값 객체 `record AuthUser(long id)`이다.
  - `roomId`와 `userId`가 뒤바뀌는 버그를 컴파일 단계에서 막는다.
  - JWT 확장 시 필드(토큰 ID, 디바이스 ID 등)만 추가하면 되고 컨트롤러 시그니처는 바뀌지 않는다.
  - 닉네임 등 표시용 정보는 넣지 않는다 (토큰 만료 전까지 stale해지는 문제).
- 존재 여부를 DB로 확인하지 않는다. 폴링 요청마다 쿼리가 추가되면 F1(커넥션 풀 고갈)을 앞당긴다. 형식만 검증한다.
- ThreadLocal 대신 파라미터로 명시적으로 넘긴다. 비동기 전송(Step 2, F4 해결)에서 ThreadLocal 값이 사라지는 문제를 피한다.
- 브라우저 WebSocket API는 커스텀 헤더를 보낼 수 없어 `/ws?userId=`로 전달한다(ADR-130). 핸드셰이크에서 `Authenticator`가 한 번 형식을 검사해 `AuthUser`를 세션 속성에 담고, 실패는 401과 인증 실패 감사 이벤트로 남긴다. 프레임마다 다시 인증하지 않으며, 사용자 존재 여부도 조회하지 않는다.

## API 명세 (ADR-017)

모든 요청에 `X-User-Id` 헤더 필요. 없거나 형식이 틀리면 401 (ADR-005). 실제 경로에는 `/api` 접두사가 붙고(ADR-032), `users`에 없는 사용자의 방 생성과 입장은 401이다(ADR-031). 최신 표는 Step 1 설계 문서를 따른다.

| 기능 | Method & URL | 성공 | 주요 실패 |
|---|---|---|---|
| 방 생성 (+ 생성자 자동 입장) | `POST /rooms` `{name}` | 201 | 400 |
| 방 목록 (최근 대화순, ADR-016) | `GET /rooms?cursor=…&size=20` | 200 | |
| 방 입장 | `POST /rooms/{roomId}/members` | 201 | 404 방 없음, 409 이미 멤버 |
| 방 나가기 | `DELETE /rooms/{roomId}/members/me` | 200 (`data: null`, ADR-020) | 403 멤버 아님 |
| 메시지 전송 | `POST /rooms/{roomId}/messages` `{content}` | 201 | 403 멤버 아님 |
| 메시지 조회 | `GET /rooms/{roomId}/messages` + `?after=` 또는 `?before=` (둘 다 없으면 최신) | 200 | 400 둘 다 지정, 403 멤버 아님 |

### 설계 포인트
- **방 생성자 자동 입장**: 방 생성과 생성자 입장을 한 트랜잭션으로 처리한다. "방은 있는데 만든 사람이 멤버가 아닌" 상태를 막는다.
- **입장/나가기 = 멤버 리소스 추가/삭제**: 동사형 URL(`/join`, `/leave`) 대신 쓴다. 입장 = 멤버 가입(ADR-007), 나가기 = 행 삭제(ADR-010)가 URL에 드러난다.
- **메시지 조회는 엔드포인트 하나 + 커서 방향**:
  - 인자 없음: 최신 N개 (방 입장 시)
  - `after={화면 맨 아래, 가장 최신 메시지 id}`: 그보다 새로운 메시지 (폴링)
  - `before={화면 맨 위, 가장 오래된 메시지 id}`: 그보다 과거 메시지 (위로 스크롤)
  - 클라이언트는 자기가 가진 가장 오래된 id와 가장 최신 id 두 개만 기억하면 된다.
  - 세 경우 모두 `(room_id, id)` 인덱스와 재입장 경계 조건(`id > 재입장 경계`, ADR-008, 011)을 공통으로 쓴다.
- **커서 기반 페이지네이션**(페이지 번호 대신 "여기서부터 이어서"를 가리키는 메시지 id를 쓰는 방식)을 쓴다. 보는 중에 새 메시지가 와도 중복/누락이 없고, 뒤로 갈수록 느려지는 offset 방식의 문제(F15)를 피한다.
- **응답 순서는 항상 오래된 것 → 최신 순**으로 통일한다. `before` 결과도 화면 위에 그대로 붙일 수 있다.
- **`hasMore`(더 있음) 표시**를 응답에 포함한다. `before` 결과가 `false`면 방의 처음 또는 재입장 시점에 도달한 것이다.
- 방 목록 커서는 정렬 기준 `(last_message_id, id)` 두 값을 함께 담는다 (메시지 없는 방은 `last_message_id`가 NULL). 인코딩은 구현 시 결정.

### 구현 시 정할 세부사항
- 메시지 조회 페이지 크기 기본값(50)과 최대값
- 응답 JSON 구체 형태와 에러 형식 (다음 결정 항목)

## 공용 응답과 모니터링 구성 (ADR-020 ~ 023, ADR-062 ~ 080)

### 공용 응답 `ApiResponse` (ADR-020)
```json
// 성공 (HTTP 201)
{ "success": true,  "data": { "id": 1001, "content": "안녕" }, "error": null }
// 실패 (HTTP 409)
{ "success": false, "data": null,
  "error": { "code": "ALREADY_MEMBER", "message": "이미 이 채팅방의 멤버입니다." } }
```
- HTTP 상태 코드는 실제 결과대로 유지한다 (201, 403, 409, 500). 실패에 200을 주면 Grafana 에러율과 k6 판정이 실패를 성공으로 센다.
- `success`는 `ApiResponse.ok(data)`, `ApiResponse.fail(code)` 두 생성 방법으로만 만든다. 상태 코드와 `success`가 어긋날 수 없게 한다.
- 본문이 없던 방 나가기는 204 대신 200 + `{success: true, data: null}`로 모양을 통일한다.
- 에러 코드는 ADR-018의 목록에 `NOT_FOUND`(404, 없는 주소)와 `METHOD_NOT_ALLOWED`(405)를 더해 쓰고, 그 밖의 Spring MVC 요청 오류는 `INVALID_REQUEST`(400)로 묶는다 (ADR-044). 문구는 코드마다 하나로 고정한다. 예외 변환은 `@RestControllerAdvice` 한 곳에서 한다.

### 모니터링 구성도 (ADR-021)
```
HTTP 요청 → RequestLogContextFilter (서버 UUID, IP, MDC, 응답 헤더)
          → AuthFilter (인증 성공 시 MDC userId) → API
          → ACCESS 한 줄 + 일반 ECS 로그 → backend/logs/app.json
          → 감사 이벤트 → AuditListener → backend/logs/audit.json
                                        두 파일 → Filebeat → Elasticsearch app-*/audit-* → Kibana
채팅 서버 /actuator/prometheus ← Prometheus (5초마다 수집) → Grafana
채팅 서버 /actuator/health (앱·DB 상태)
```
- 메트릭: Micrometer가 요청 수, 응답 시간 히스토그램, HikariCP, Tomcat 스레드, JVM 힙을 수집한다. `application=chat`, `db=mysql|postgres`, `schema=A|B` 태그로 실험 조건을 구분한다. Prometheus가 계산한 p99는 히스토그램 버킷의 근사값이다.
- 추적 ID: 가장 먼저 실행되는 필터가 요청마다 UUID를 새로 만들고 MDC `requestId`와 응답 `X-Request-Id`에 넣는다. 클라이언트가 보낸 같은 이름의 헤더는 무시한다. `server.forward-headers-strategy: native`가 내부 프록시의 `X-Forwarded-For`를 처리한 뒤 `getRemoteAddr()`를 MDC `clientIp`로 쓴다. 실제 Tomcat 테스트에서 외부에서 직접 보낸 위조 헤더는 반영되지 않았다.
- 로그: 콘솔은 `[requestId]`가 보이는 텍스트, 파일은 ECS JSON이다. `LOG_DIR` 기본값은 `logs`이므로 `bootRun`의 파일은 `backend/logs/`에 있다. 테스트는 `backend/build/test-logs/`를 쓴다. app은 10MB 단위·3일·총 200MB, audit은 10MB 단위·30일·총 500MB로 회전한다.
- 접근 로그: 최상위 필터의 `finally`에서 `ACCESS` 한 줄에 `method`, `path`, `query`, `status`, `durationMs`를 남긴다. 인증 실패 401도 포함하고 `/actuator/**`는 제외한다. 인증 성공 후에는 `AuthFilter`가 MDC에 넣은 `userId`도 함께 기록되며 필터가 요청 끝에 MDC를 지운다. `local`·`prod`에서는 켜고 `bench`에서는 꺼 성능 실험에 미치는 영향을 줄인다.
- WebSocket 접근 로그: 핸드셰이크는 위 HTTP `ACCESS`에 `/ws`·101로 남는다. 그 뒤 접속·프레임·종료는 AOP의 `WS_ACCESS`가 `event`, `sessionId`, `frameType`, `roomId`, `result`, `durationMs`, `closeCode`로 기록한다(해당하는 필드만). 이벤트마다 새 `requestId`를 만들고 `userId`와 함께 MDC에 둔다. `bench`에서는 끈다(ADR-136). 기존 `ACCESS`·`AUDIT`·예외 로그는 각각 필터·커밋 후 리스너·전역 예외 처리에 둔다.
- WebSocket 지표: `chat.ws.sessions`(현재 저장소 세션 수), `chat.ws.frames{type}`(송수신 프레임 수), `chat.delivery.stage{stage,transport}`(`receive`·`save`·`fanout`·세션별 `push`), `chat.delivery.total{transport}`(수신 시작부터 fan-out 정상 종료까지)를 노출한다. 현재 동기 경로의 `receive ⊃ save ⊃ fanout ⊃ push` 단계 시간은 겹치므로 합산하지 않는다(ADR-129·135). Prometheus에서는 `_seconds`와 `_count` 등으로 변환된다.
- 감사 로그: 아래 이벤트 리스너가 업무 결과를 기록한다. 접근 로그와 감사 로그는 같은 `requestId`로 연결된다. 메시지 본문은 기록하지 않는다.
- 모니터링 도구는 `infra/compose.monitoring.yml`의 `metrics`와 `logs` 프로필로 필요할 때만 켠다. 부하 측정에서는 Docker 자원 경쟁을 고려해 켠 프로필을 기록한다.

| 프로필 | 서비스 | 호스트 포트 | 확인 주소 |
|---|---|---|---|
| `metrics` | Prometheus, Grafana | 19090, 13000 | `/targets`, `chat Step 1` 대시보드 |
| `logs` | Elasticsearch, Kibana, Filebeat | 19200, 15601 | `/_cat/indices/app-*,audit-*?v`, Discover |

`docker compose -f infra/compose.monitoring.yml --profile metrics up -d --wait` 또는 `--profile logs`로 켠다. 앱은 Compose 밖의 `backend`에서 실행하고 Prometheus는 `host.docker.internal:8080`을 5초마다 읽는다. Grafana 데이터 소스와 대시보드는 파일로 자동 등록된다. 로컬 Grafana는 익명 관리자, Elasticsearch는 보안이 꺼진 학습용 구성이다. Elastic 이미지는 모두 9.5.4, Prometheus는 v3.14.0, Grafana는 13.2.3으로 고정했다. 작은 Docker 볼륨에서 기본 디스크 임계값 때문에 샤드가 배치되지 않아 Elasticsearch의 여유 공간 기준을 1GB/750MB/500MB로 설정했다. 단일 노드의 복제 샤드는 배치되지 않아 색인이 yellow여도 기본 샤드의 읽기·쓰기는 가능하다.

| `chat Step 1` 패널 | 확인할 지표 |
|---|---|
| 초당 요청 수 (URI별) | API별 요청량 |
| p99 응답 시간 (URI별) | 느린 API |
| p50 / p95 / p99 (전체) | 전체 지연 분포 |
| 에러율 4xx / 5xx | 클라이언트 오류와 서버 오류 |
| HikariCP 커넥션 | active·idle·pending |
| 커넥션 대기와 타임아웃 | 획득 최대 시간·타임아웃 빈도 |
| Tomcat 바쁜 스레드 · JVM 힙 | 요청 처리 스레드·힙 사용량 |

### 환경(profile)과 로그 레벨 (ADR-022)
| 환경 | 우리 코드 | root(외부 전부) | 따로 여는 외부 로그 | 감사 로그 | `/actuator/loggers` |
|---|---|---|---|---|---|
| local | TRACE | INFO | SQL과 바인딩 값, HikariCP(DEBUG), 트랜잭션(DEBUG) | 콘솔에도 출력 | 공개 |
| bench | INFO | WARN | 없음 | 켬 | 공개 |
| prod | INFO | WARN | 없음 | 켬, 별도 보관 | 비공개 |

- bench: 부하 실험할 때 켜는 active profile. 운영처럼 로그를 줄여 측정 왜곡을 막고, 실험용 DB에 연결하고, 메트릭은 켠다.
- 레벨은 패키지마다 따로 적용된다. 우리 코드가 TRACE여도 외부 라이브러리의 DEBUG는 root(INFO)를 따라 보이지 않는다. 필요한 것만 골라 연다.
- `/actuator/loggers`로 재시작 없이 실행 중 레벨을 바꿀 수 있다 (local, bench만 공개).
- SQL 로그 패키지: JDBC는 `org.springframework.jdbc.core` (DEBUG: SQL, TRACE: 바인딩 값), JPA는 `org.hibernate.SQL` (DEBUG: SQL). JPA 전환 비교는 F21에서 측정한다 (ADR-024).

### 감사 로그 (ADR-023)
- 대상: 방 생성, 입장, 나가기, 인증/인가 실패. **메시지 전송은 제외** (본문은 개인정보, 양도 많음).
- 내용: 누가(userId), 언제, 무엇을(동작), 어디서(roomId, IP), 결과(성공/실패 코드).
- 방식: `AUDIT` 전용 로거 → Filebeat → `audit-*` 별도 저장소. 일반 로그와 보관 기간, 접근 권한을 따로 둔다.
- 필드: `action`, `outcome`, `roomId`, `code`, `path`, `credential`(최대 64코드포인트), `occurredAt`; 요청 MDC의 `requestId`, `clientIp`, `userId`도 붙는다. ECS에서 중복 필드 직렬화 오류를 피하도록 `userId`는 MDC 한 곳에만 둔다.
- 방 생성·입장·나가기 성공은 트랜잭션 커밋 후(`AFTER_COMMIT`)에만 기록한다. 인증·인가 실패는 트랜잭션 종료 뒤(`AFTER_COMPLETION`) 기록하며 트랜잭션이 없어도 기록한다. 롤백된 성공은 남기지 않고 실제 거부는 남긴다.
- Filebeat는 `app*.json`, `audit*.json`을 따로 읽어 `app-YYYY.MM.dd`, `audit-YYYY.MM.dd`로 보낸다. 로컬에서는 ILM을 설정하지 않았으므로 파일 회전과 Elasticsearch 색인 보관은 별개의 설정이다.
- 한계: (1) 서버가 갑자기 죽으면 아직 옮겨지지 않은 로그가 유실될 수 있다 (법적 증거 수준이 필요하면 DB 테이블로 바꿔야 함) (2) userId는 JWT 도입 전까지 헤더를 그대로 믿으므로(ADR-005) 신뢰할 수 없는 값이다.

## 저장소 구조와 같은 주소(origin) 서비스 (ADR-027 ~ 029)

### 폴더 구조 (모노레포)
```
chat-server/
 ├─ backend/      Spring Boot 4.1.1, Java 21, Gradle Kotlin DSL, 기본 패키지 jissuo.chat
 ├─ frontend/     Vite + React + TypeScript (/api 요청은 backend로 proxy)
 ├─ infra/        docker compose (DB, 모니터링, 이후 nginx/Redis)
 ├─ db/           다른 환경에서도 쓸 수 있는 테스트용 SQL
 └─ docs/         진행 기록, ERD
```
- Flyway 스키마 스크립트는 서버가 읽어야 하므로 `backend/src/main/resources/db/migration/{mysql,postgresql}`에 둔다.

### 같은 주소(origin)로 서비스하는 이유
- origin = 프로토콜 + 호스트 + 포트. 하나라도 다르면 브라우저는 다른 주소로 본다.
- 화면(5173)과 API(8080)가 다른 주소면 CORS 허용 설정이 없을 때 브라우저가 응답을 막는다. 서버가 허락해도 커스텀 헤더(`X-User-Id`)가 있으면 사전 확인 요청(preflight, `OPTIONS`)이 필요하다.
- 그래서 브라우저가 한 주소하고만 대화하게 하고, 그 주소가 `/api` 요청을 백엔드로 대신 전달(proxy)한다. 서버끼리의 요청은 CORS 대상이 아니다.

```
개발: 브라우저 → Vite 개발 서버(5173) ─┬─ 화면: 직접 응답 (실시간 변환, 저장 즉시 반영 HMR)
                                      ├─ /api: 8080으로 전달
                                      └─ /ws: WebSocket Upgrade를 8080으로 전달
운영: 브라우저 → nginx ─┬─ 화면: 빌드된 정적 파일 (vite build 결과)
                       └─ /api: Spring 백엔드로 전달 (Step 3에서 서버 2대로 분배)
```
- 개발은 Vite: 고친 코드를 바로 보는 것이 중요하다 (작업실).
- 운영은 nginx: 완성 파일을 빠르게 많은 사용자에게 주고, 여러 서버로 분배하고, WebSocket과 HTTPS를 처리한다 (매장). Vite 개발 서버는 공식적으로 운영용이 아니다.
- 한계: proxy를 거치면 백엔드가 보는 요청 IP가 proxy의 IP가 된다. 감사 로그(ADR-023)에 실제 IP를 남기려면 `X-Forwarded-For`를 읽도록 설정한다. `/ws`에는 `ws: true`를 설정했다(ADR-141). 로컬 Chrome·Playwright에서 Vite proxy 뒤 핸드셰이크 101과 메시지 push를 확인했고 Origin 403은 관찰되지 않았다. k6 부하 테스트는 브라우저가 아니라 CORS와 무관하며 백엔드에 직접 요청한다.

### 계획 4 프론트엔드 구현과 직접 확인 (ADR-081 ~ 087)

- `frontend/`는 사용자 선택, 방 목록, 채팅방을 React 상태로 전환한다. `#/rooms`와 `#/rooms/{id}` hash 경로라 새로고침·직접 링크로 방을 다시 열 수 있다. 사용자 ID는 탭별 `sessionStorage`에 둔다. 채팅방에는 폴링 주기, `after` 커서, 요청·오류 수, 마지막 응답 시간과 `X-Request-Id`가 보인다.
- `usePolling`은 응답을 받은 뒤 다음 `setTimeout`을 예약해 한 탭 안에서 요청을 겹치지 않는다. 기본 주기는 2초이고 0.5/1/2/5초를 선택한다. `after`는 **메시지 조회 응답의 마지막 ID로만** 전진한다. 전송 응답은 화면에 바로 합치되 커서는 옮기지 않는다. 조회 결과의 `hasMore=true`면 곧바로 다음 페이지를 조회한다. 오류 뒤에도 같은 주기로 재시도한다.
- 방 목록은 자동 갱신하지 않고 새로고침·`nextCursor` 더 보기를 제공한다. 과거 메시지는 `before` 커서를 쓰는 버튼으로 조회한다. 최신 조회의 403 `NOT_A_MEMBER`는 입장 버튼으로 바뀌고, 409 `ALREADY_MEMBER`는 다시 조회한다.
- 개발 실행: 루트에서 `docker compose -f infra/compose.db.yml up -d --wait`, `backend/`에서 `./gradlew bootRun --args='--spring.profiles.active=local,mysql'`, `frontend/`에서 Node 22.22.2로 `npm ci && npm run dev`. 브라우저는 `http://localhost:5173`을 연다. 단위 테스트는 `npm test`, 브라우저 테스트는 DB 기동 후 `npm run e2e`다.
- **관찰 (2026-10-07, Chrome + local,mysql):** Network에 `/api/rooms/10/messages?after=55` 요청이 반복됐고 관찰 구간에 `OPTIONS`는 보이지 않았다. 폴링 패널의 요청 ID `1742de53-cad2-4d09-8b37-05abae006c67`은 `backend/logs/app.json`의 `ACCESS` 행(`GET`, 200, `clientIp=127.0.0.1`)과 일치했다. 127.0.0.1에서 접속한 로컬 측정이라 다른 proxy 경로의 IP 동작은 아직 확인하지 않았다.

## 테스트와 실행 환경 (ADR-030)

### Docker Compose (용도별 분리)
```
infra/
 ├─ compose.db.yml           MySQL 8.4.11 + PostgreSQL 18.6 (항상)
 └─ compose.monitoring.yml   Elasticsearch, Kibana, Filebeat, Prometheus, Grafana (필요할 때만)
```
- 이미지 버전은 패치 번호까지 고정한다. 측정 중 버전이 바뀌면 결과를 비교할 수 없다.
- 로컬 설치 DB와 포트가 겹치지 않게 한다 (예: MySQL 13306, PostgreSQL 15432).
- 두 DB에 같은 CPU/메모리 제한과 내구성 설정을 준다 (DB 비교 공정성 규칙).
- 2026-10-06 확인: Docker Hub에 mysql 9.7.2, 8.4.11, 8.0.46과 의미를 확인하지 못한 `26.7` 태그가 있다. postgres 최신은 18.6.

### 테스트용 SQL (`db/`, 앱 없이 다른 환경에서도 실행 가능)
```
db/
 ├─ README.md                   다른 환경에서 실행하는 방법
 ├─ seed/                       두 DB 공용 소량 샘플 데이터 (화면 확인용, ADR-061)
 ├─ bulk/{mysql,postgresql}/    대량 데이터 생성 (부하 측정용, 인기 방 쏠림)
 └─ queries/{mysql,postgresql}/ 확인용 쿼리 (실행 계획, 테이블/인덱스 크기, 캐시 적중률)
```
- 테이블 생성 SQL은 Flyway 폴더에 한 벌만 둔다. 다른 환경에서는 README 안내대로 그 파일을 순서대로 실행한다.
- 대량 데이터 생성 문법은 DB별로 다르다 (PostgreSQL `generate_series`, MySQL 숫자 CTE의 cross join, ADR-058).

### 자동 테스트 (3종류)
| 종류 | DB | 언제 실행 | 예시 |
|---|---|---|---|
| 단위 | 없음 | 항상 | `AuthUser` 검증, 커서 파싱 |
| 통합 | Testcontainers (MySQL, PostgreSQL 모두) | 항상 | 저장소 계약 테스트, API 테스트 |
| 실험 | Testcontainers | 따로 실행 (`@Tag("experiment")`, `./gradlew experimentTest`) | 입장 경계(F18), 마지막 나가기와 입장 경쟁(F19) |

- profile은 환경과 DB를 조합한다. 예: `--spring.profiles.active=local,mysql`
- 메모리 DB(H2)는 잠금과 커밋 동작이 실제 DB와 달라 쓰지 않는다.

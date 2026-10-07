# 아키텍처

> 누적 문서. Step 1 설계 문서(`../superpowers/specs/2026-10-06-chat-server-step1-design.md`)의 근거가 되는 구조 결정 상세.

## 인증 구조 (ADR-005, 006)

```
             [1. 추출: 통로별]                 [2. 판별: 단 한 곳]           [3. 전달: 사용처별]
HTTP 요청  → AuthFilter (X-User-Id 헤더)    ┐                              → @CurrentUser AuthUser
                                            ├→ Authenticator.authenticate()
WebSocket  → HandshakeInterceptor           ┘   지금: HeaderUserIdAuthenticator → WebSocketSession attributes
             (query/cookie, Step 2에서 결정)     나중: JwtAuthenticator

[인가] "이 유저가 이 방의 멤버인가?"는 인증 층이 아니라 도메인(멤버십, R3)이 판단한다. 흐름은 MessageService가 맡는다 (ADR-037).
```

- `Authenticator`는 `HttpServletRequest`에 의존하지 않는다. 문자열 credential만 받는다.
- 반환 타입은 `Long`이 아니라 불변 값 객체 `record AuthUser(long id)`이다.
  - `roomId`와 `userId`가 뒤바뀌는 버그를 컴파일 단계에서 막는다.
  - JWT 확장 시 필드(토큰 ID, 디바이스 ID 등)만 추가하면 되고 컨트롤러 시그니처는 바뀌지 않는다.
  - 닉네임 등 표시용 정보는 넣지 않는다 (토큰 만료 전까지 stale해지는 문제).
- 존재 여부를 DB로 확인하지 않는다. 폴링 요청마다 쿼리가 추가되면 F1(커넥션 풀 고갈)을 앞당긴다. 형식만 검증한다.
- ThreadLocal 대신 파라미터로 명시적으로 넘긴다. 비동기 전송(Step 2, F4 해결)에서 ThreadLocal 값이 사라지는 문제를 피한다.
- 브라우저 WebSocket API는 커스텀 헤더를 보낼 수 없다. WebSocket 인증 방식은 Step 2에서 다시 의논한다.

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

## 공용 응답과 모니터링 구성 (ADR-020 ~ 023)

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
          ┌─ 로그(JSON, ECS) ─→ Filebeat ─→ Elasticsearch ─→ Kibana (로그 검색)
          │    ├─ 일반 로그 → app-* 저장소 (짧게 보관)
채팅 서버 ─┤    └─ 감사 로그 → audit-* 저장소 (길게 보관)
          ├─ 메트릭 ←── Prometheus가 /actuator/prometheus 주기 수집 ─→ Grafana (그래프)
          └─ /actuator/health (헬스 체크: 앱, DB, 이후 Redis)
```
- 메트릭: Micrometer가 요청 수, 응답 시간, HikariCP 커넥션 사용량을 자동 수집한다.
- 로그: JSON 구조화 로그(ECS, Spring Boot 3.4 이상). 요청마다 추적 ID를 붙여 한 요청의 로그를 모아 본다.
- 모니터링 도구는 메모리를 많이 쓰므로 Docker Compose를 나눠 필요할 때만 켠다. 부하 측정과 동시에 돌리면 측정값이 흔들릴 수 있다.

### 환경(profile)과 로그 레벨 (ADR-022)
| 환경 | 우리 코드 | root(외부 전부) | 따로 여는 외부 로그 | 감사 로그 | `/actuator/loggers` |
|---|---|---|---|---|---|
| local | TRACE | INFO | SQL과 바인딩 값, HikariCP(DEBUG), 트랜잭션(DEBUG) | 콘솔에도 출력 | 공개 |
| bench | INFO | WARN | 없음 | 켬 | 공개 |
| prod | INFO | WARN | 없음 | 켬, 별도 보관 | 비공개 |

- bench: 부하 실험할 때 켜는 active profile. 운영처럼 로그를 줄여 측정 왜곡을 막고, 실험용 DB에 연결하고, 메트릭은 켠다.
- 레벨은 패키지마다 따로 적용된다. 우리 코드가 TRACE여도 외부 라이브러리의 DEBUG는 root(INFO)를 따라 보이지 않는다. 필요한 것만 골라 연다.
- `/actuator/loggers`로 재시작 없이 실행 중 레벨을 바꿀 수 있다 (local, bench만 공개).
- SQL 로그 패키지: `org.springframework.jdbc.core` (DEBUG: SQL, TRACE: 바인딩 값). JPA 전환 시 Hibernate 로그로 바뀐다 (ADR-024).

### 감사 로그 (ADR-023)
- 대상: 방 생성, 입장, 나가기, 인증/인가 실패. **메시지 전송은 제외** (본문은 개인정보, 양도 많음).
- 내용: 누가(userId), 언제, 무엇을(동작), 어디서(roomId, IP), 결과(성공/실패 코드).
- 방식: `AUDIT` 전용 로거 → Filebeat → `audit-*` 별도 저장소. 일반 로그와 보관 기간, 접근 권한을 따로 둔다.
- 트랜잭션 커밋 후에 기록한다 (`@TransactionalEventListener`). 롤백된 작업이 성공으로 기록되는 것을 막는다.
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
- 화면(5173)과 API(8080)가 다른 주소면 브라우저가 보안 규칙(CORS)으로 요청을 막는다. 서버가 허락(CORS 설정)해도, 커스텀 헤더(`X-User-Id`)가 있으면 요청마다 확인 요청(preflight, `OPTIONS`)이 먼저 나가서 폴링 요청이 사실상 두 배가 된다.
- 그래서 브라우저가 한 주소하고만 대화하게 하고, 그 주소가 `/api` 요청을 백엔드로 대신 전달(proxy)한다. 서버끼리의 요청은 CORS 대상이 아니다.

```
개발: 브라우저 → Vite 개발 서버(5173) ─┬─ 화면: 직접 응답 (실시간 변환, 저장 즉시 반영 HMR)
                                      └─ /api: 8080으로 전달
운영: 브라우저 → nginx ─┬─ 화면: 빌드된 정적 파일 (vite build 결과)
                       └─ /api: Spring 백엔드로 전달 (Step 3에서 서버 2대로 분배)
```
- 개발은 Vite: 고친 코드를 바로 보는 것이 중요하다 (작업실).
- 운영은 nginx: 완성 파일을 빠르게 많은 사용자에게 주고, 여러 서버로 분배하고, WebSocket과 HTTPS를 처리한다 (매장). Vite 개발 서버는 공식적으로 운영용이 아니다.
- 한계: proxy를 거치면 백엔드가 보는 요청 IP가 proxy의 IP가 된다. 감사 로그(ADR-023)에 실제 IP를 남기려면 `X-Forwarded-For`를 읽도록 설정한다. WebSocket 전달은 Step 2에서 `ws: true` 설정을 추가한다. k6 부하 테스트는 브라우저가 아니라 CORS와 무관하며 백엔드에 직접 요청한다.

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
 ├─ seed/{mysql,postgresql}/    소량 샘플 데이터 (화면 확인용)
 ├─ bulk/{mysql,postgresql}/    대량 데이터 생성 (부하 측정용, 인기 방 쏠림)
 └─ queries/{mysql,postgresql}/ 확인용 쿼리 (실행 계획, 테이블/인덱스 크기, 캐시 적중률)
```
- 테이블 생성 SQL은 Flyway 폴더에 한 벌만 둔다. 다른 환경에서는 README 안내대로 그 파일을 순서대로 실행한다.
- 대량 데이터 생성 문법은 DB별로 다르다 (PostgreSQL `generate_series`, MySQL 재귀 CTE).

### 자동 테스트 (3종류)
| 종류 | DB | 언제 실행 | 예시 |
|---|---|---|---|
| 단위 | 없음 | 항상 | `AuthUser` 검증, 커서 파싱 |
| 통합 | Testcontainers (MySQL, PostgreSQL 모두) | 항상 | 저장소 계약 테스트, API 테스트 |
| 실험 | Testcontainers | 따로 실행 (`@Tag("experiment")`, `./gradlew experimentTest`) | 입장 경계(F18), 마지막 나가기와 입장 경쟁(F19) |

- profile은 환경과 DB를 조합한다. 예: `--spring.profiles.active=local,mysql`
- 메모리 DB(H2)는 잠금과 커밋 동작이 실제 DB와 달라 쓰지 않는다.

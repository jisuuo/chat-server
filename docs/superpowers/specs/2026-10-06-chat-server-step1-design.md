# chat-server Step 1 설계 (단일 서버 + REST API + HTTP 폴링)

- 작성일: 2026-10-06
- 근거 기록: [`docs/adr/2026-10-06.md`](../../adr/2026-10-06.md) (ADR-001 ~ ADR-039)
- 상태: 승인됨 (ADR-032)
- ERD: [`docs/design/erd.md`](../../design/erd.md)

이 문서는 Step 1에서 **무엇을 만들 것인가**만 정리한다. 각 결정의 이유와 포기한 것은 `docs/adr/`(날짜별)에, 상세 구조는 `docs/design/`에 있다.

---

## 1. 목표와 범위

### 목표
1. 과제의 채팅방/메시지 API를 단일 서버에서 동작시킨다.
2. 실시간 전달은 HTTP 폴링으로 구현해, 이후 WebSocket 전환(Step 2)과 비교할 **기준선 수치**를 만든다 (ADR-004).
3. Step 1 실험(아래 8장)을 실행할 수 있는 코드, 데이터, 측정 환경을 갖춘다.

### 범위에 포함
- 백엔드 API 6종 (방 생성, 방 목록, 입장, 나가기, 메시지 전송, 메시지 조회) + 개발용 사용자 생성 API
- MySQL과 PostgreSQL 동시 지원 (DB는 측정 후 결정, ADR-003)
- messages 스키마 A/B 두 벌 (ADR-013)
- 공용 응답 `ApiResponse`, 에러 코드, 감사 로그, 헬스 체크, 메트릭, 구조화 로그
- 테스트용 SQL (`db/`), Docker Compose, 자동 테스트 3종류
- 간단한 프론트엔드 (화면 범위는 별도 결정, 9장)

### 범위에서 제외 (이후 Step)
- WebSocket (Step 2), 서버 2대와 nginx (Step 3), Redis (Step 4), 정합성 강화 (Step 5)
- JWT 인증 (ADR-005), JPA 구현체 (Step 1 실험 이후, ADR-025)
- 입장/퇴장 알림(시스템) 메시지 (미결정)

---

## 2. 저장소와 코드 구조

```
chat-server/
 ├─ backend/      Spring Boot 4.1.1, Java 21, Gradle Kotlin DSL, 단일 모듈
 ├─ frontend/     Vite + React + TypeScript
 ├─ infra/        compose.db.yml, compose.monitoring.yml
 ├─ db/           seed / bulk / queries × mysql / postgresql, README.md
 └─ docs/
```

### 백엔드 패키지 (`jissuo.chat`, 기능별 + 4계층, ADR-036 ~ 039)

도메인 용어, 규칙(R1~R7), 애그리거트 경계, 의존 방향은 [`docs/design/domain.md`](../../design/domain.md)를 따른다.

```
jissuo.chat
 ├─ room/       domain/ (Room, RoomName, Membership, JoinBoundary, RoomRepository, MembershipRepository)
 │              application/ (RoomService)  infra/jdbc/  api/ (RoomController, DTO)
 ├─ message/    domain/ (Message, MessageContent, MessageCursor, MessageRepository)
 │              application/ (MessageService)  infra/jdbc/ (스키마 A, B)  api/ (MessageController, DTO)
 ├─ user/       domain/ application/ infra/jdbc/ api/ (DevUserController, @Profile local, bench)
 ├─ auth/       AuthFilter, Authenticator, HeaderUserIdAuthenticator, AuthUser, @CurrentUser, ArgumentResolver
 ├─ audit/      감사 이벤트 수신(커밋 후), AUDIT 로거
 └─ common/     ApiResponse, ErrorCode, 전역 예외 처리(@RestControllerAdvice)
```
- 의존 방향: `api → application → domain ← infra`, 기능 사이는 `message → room`만, 상대의 `domain/`만 호출. ArchUnit 테스트로 검사.
- 서비스는 저장소 **인터페이스**(`domain/`)에만 의존한다. 구현체는 설정 값으로 고른다 (ADR-024, 025).
  - `chat.repository=jdbc` (이후 `jpa` 추가)
  - `chat.message-schema=A|B` (스키마 비교용)
  - `chat.join-boundary=id|time` (입장 경계 비교용, 8장 F18)
- 데이터 접근은 Spring `JdbcClient`.

---

## 3. 데이터 모델

| 테이블 | 컬럼 | 키/인덱스 | FK |
|---|---|---|---|
| `users` | id, nickname, created_at | PK(id) | |
| `rooms` | id, name, created_by, last_message_id(NULL 가능), created_at | PK(id) | 없음 |
| `room_members` | room_id, user_id, joined_message_id, joined_at | PK(room_id, user_id) | room_id → rooms, user_id → users |
| `messages` (A) | id, room_id, sender_id, content, created_at | PK(id), IDX(room_id, id) | 없음 |
| `messages` (B) | room_id, id, sender_id, content, created_at | PK(room_id, id), id 단독 인덱스(MySQL AUTO_INCREMENT 제약) | 없음 |

- 스키마는 Flyway로 관리한다: `backend/src/main/resources/db/migration/{mysql,postgresql}`.
- `joined_message_id`와 `joined_at`은 입장 경계 실험(F18)용으로 둘 다 둔다. 실험 후 하나를 지운다.
- `rooms.last_message_id`는 방 목록 정렬용 비정규화 컬럼이다 (ADR-016).

---

## 4. API

모든 요청에 `X-User-Id` 헤더가 필요하다. 응답은 모두 `ApiResponse`로 감싼다 (ADR-020).

| 기능 | Method & URL | 성공 | 주요 실패 |
|---|---|---|---|
| 방 생성 (+ 생성자 자동 입장, 한 트랜잭션) | `POST /api/rooms` `{name}` | 201 | 400, 401 (없는 사용자) |
| 방 목록 (최근 대화순) | `GET /api/rooms?cursor=…&size=20` | 200 | |
| 방 입장 | `POST /api/rooms/{roomId}/members` | 201 | 401 (없는 사용자), 404 `ROOM_NOT_FOUND`, 409 `ALREADY_MEMBER` |
| 방 나가기 | `DELETE /api/rooms/{roomId}/members/me` | 200 (`data: null`) | 403 `NOT_A_MEMBER` |
| 메시지 전송 | `POST /api/rooms/{roomId}/messages` `{content}` | 201 | 403 `NOT_A_MEMBER` |
| 메시지 조회 | `GET /api/rooms/{roomId}/messages` + `after` 또는 `before` | 200 | 400 `INVALID_REQUEST`, 403 `NOT_A_MEMBER` |
| 개발용 사용자 생성 (local, bench 전용) | `POST /api/dev/users` `{nickname}` | 201 | 400 |

- URL 앞에 `/api`를 붙인다. 개발 중 Vite proxy와 운영 nginx가 `/api`만 백엔드로 전달하기 때문이다 (ADR-028).
- 메시지 조회 응답은 항상 오래된 것 → 최신 순이고 `hasMore`를 포함한다 (ADR-017).
- 방 목록 정렬: `last_message_id DESC`, 메시지 없는 방(NULL)은 맨 뒤, 같은 값이면 `id DESC`. 커서는 두 값을 함께 담는다.

### `ApiResponse`
```json
{ "success": true,  "data": { ... }, "error": null }
{ "success": false, "data": null, "error": { "code": "ALREADY_MEMBER", "message": "..." } }
```
- HTTP 상태 코드는 실제 결과대로 둔다. `success`는 `ApiResponse.ok(...)`, `ApiResponse.fail(...)`로만 만든다.
- 에러 코드: `UNAUTHENTICATED`(401), `NOT_A_MEMBER`(403), `ROOM_NOT_FOUND`(404), `ALREADY_MEMBER`(409), `INVALID_REQUEST`(400), `INTERNAL_ERROR`(500, 상세 숨김).
- DB 제약 위반은 DB를 고르기 전까지: 중복 = 409, 그 밖 = 500 + 로그 (ADR-019).

---

## 5. 핵심 흐름

### 인증과 인가 (ADR-006, 011)
1. `AuthFilter`가 `X-User-Id`를 꺼내 `Authenticator`에 넘긴다. 형식만 검증하고 DB는 조회하지 않는다. 실패하면 401.
2. 컨트롤러는 `@CurrentUser AuthUser`로 받는다 (ThreadLocal 미사용).
3. 메시지 조회/전송/나가기는 먼저 `room_members`에서 `(room_id, user_id)` 행을 조회한다. 행이 없으면 403, 있으면 그 행의 경계값을 쓴다.

### 입장
1. 방 존재 확인. 없으면 404.
2. 경계값 결정: `joined_message_id` = 입장 시점의 `rooms.last_message_id`(NULL이면 0), `joined_at` = 현재 시각.
3. `room_members` INSERT. 중복이면 409. FK 위반이면 사용자 없음으로 보고 401 (방은 1단계에서 확인했으므로).
4. 커밋 후 감사 로그.

### 트랜잭션과 격리 수준 (ADR-034)
- 아래 흐름의 트랜잭션 범위대로 구현한다. 격리 수준은 각 DB 기본값을 쓴다. 중복 방지 키와 전달 보장 원칙은 장애를 재현한 뒤 보완한다.

### 메시지 전송 (한 트랜잭션)
1. 멤버 행 확인 (없으면 403).
2. `messages` INSERT → 새 id.
3. `UPDATE rooms SET last_message_id = :id WHERE id = :roomId AND (last_message_id IS NULL OR last_message_id < :id)`.

### 메시지 조회
- 조건: `room_id = ? AND 경계 조건 AND (id > after | id < before | 최신)`, `(room_id, id)` 인덱스 사용.
- 경계 조건은 `chat.join-boundary`에 따라 `id > joined_message_id` 또는 `created_at > joined_at`.

### 나가기
- `room_members` 행 삭제 (hard delete, ADR-010). 방은 지우지 않는다 (ADR-012). 커밋 후 감사 로그.

---

## 6. 관측 (ADR-021 ~ 023)

- 헬스 체크: `/actuator/health` (앱, DB). 공개 엔드포인트: `health`, `prometheus` (local/bench는 `loggers`도).
- 메트릭: Micrometer → `/actuator/prometheus` → Prometheus → Grafana.
- 로그: JSON(ECS) → Filebeat → Elasticsearch(`app-*`, `audit-*`) → Kibana. 요청마다 추적 ID.
- 감사 로그 대상: 방 생성, 입장, 나가기, 인증/인가 실패. 커밋 후 기록. 메시지 전송은 제외.
- profile: 환경(`local`, `bench`, `prod`) × DB(`mysql`, `postgres`). 로그 레벨은 `docs/design/architecture.md`의 "환경(profile)과 로그 레벨" 표를 따른다.

---

## 7. 실행 환경과 테스트 (ADR-030)

- `infra/compose.db.yml`: `mysql:8.4.11`(포트 13306), `postgres:18.6`(포트 15432), 같은 CPU/메모리 제한, 내구성 설정 일치.
- `infra/compose.monitoring.yml`: Elasticsearch, Kibana, Filebeat, Prometheus, Grafana. 필요할 때만 실행.
- `db/`: seed(샘플), bulk(대량, 인기 방 쏠림), queries(실행 계획, 크기, 캐시 적중률), README(다른 환경 실행법).
- 자동 테스트:
  - 단위: DB 없음
  - 통합: Testcontainers로 MySQL, PostgreSQL 둘 다. 저장소 계약 테스트는 두 DB × 스키마 A/B에서 같은 결과를 보장한다.
  - 실험: `@Tag("experiment")`, `./gradlew experimentTest`로 따로 실행.

---

## 8. Step 1에서 실행할 실험

| 실험 | 내용 | 판정 기준 |
|---|---|---|
| F1 | 폴링 폭주로 DB 커넥션 풀 고갈 | 동시 폴링 수에 따른 p99, 에러율, 커넥션 대기 |
| F2 | 시각 기준 "새 메시지" 조회의 누락/중복 | 누락/중복 건수 (id 기준과 비교) |
| F15, F16 | offset 페이지네이션 저하, 인덱스 부재 풀스캔 / PK 스키마 A와 B 비교 | ADR-014 판정 규칙: 메모리 초과 조건에서 B의 읽기 p99가 30% 이상 개선되고 쓰기 p99 악화가 10% 이내일 때만 B 채택 |
| F18 | 입장 경계: 시각(`joined_at`)과 id(`joined_message_id`) | ADR-009 커밋 순서 기준으로 유출/유실/불일치 건수 |
| F19 | 마지막 나가기와 입장 경쟁 (실험용 방 삭제 구현) | 삭제된 방 입장, 고아 멤버 발생 여부, 해결책별 비교 |
| F22 | 커밋 순서 역전으로 인한 영구 누락 (ADR-034, 장애 선행) | 동시 전송 중 폴링이 영구히 놓친 메시지 수, DB와 격리 수준별 |
| F23 | 나가기와 메시지 전송의 경쟁 (ADR-037, 장애 선행) | 비멤버 메시지가 저장되는 횟수, DB와 격리 수준별 |
| F20 | 방 목록 정렬 컬럼 갱신 경합 | 동시 전송 수에 따른 전송 p99, DB별 데드락 여부, PostgreSQL 인덱스 유무별 테이블 크기 |
| DB 비교 | 워크로드 W1~W5 × 조건 C1~C4 | `docs/design/experiments.md`의 DB 비교 지표, 공정성 규칙 |

---

## 9. 이 문서에서 새로 정한 세부 (확정, ADR-032)

ADR에는 없던 것을 구현을 위해 이 문서에서 정했고, 검토 후 확정했다.

1. **API 경로 앞에 `/api`** 를 붙인다.
2. **입장 시 `joined_message_id`는 그 시점의 `rooms.last_message_id`** 로 정한다 (메시지 테이블 `MAX(id)` 조회 대신). 동시 전송 시 정확성은 F18에서 검증한다.
3. **입력 제한**: 방 이름 1~50자, 메시지 1~1000자, 메시지 조회 `size` 기본 50·최대 100, 방 목록 `size` 기본 20·최대 50.
4. **구현체 선택 설정 값** 3개: `chat.repository`, `chat.message-schema`, `chat.join-boundary`.

## 10. 남은 미결정 (Step 1 진행 중 결정)

1. 프론트 화면 범위.
2. 입장/퇴장 알림 메시지 도입 여부.
3. 방금 만든 방이 목록 맨 아래에 보이는 문제 보완 여부.

(해결됨) 사용자 생성 방식: 개발용 API + seed/bulk SQL, 없는 사용자는 401 (ADR-031).

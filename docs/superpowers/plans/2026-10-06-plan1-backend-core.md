# 계획 1: 백엔드 핵심 (Step 1)

## 한 줄 요약
빈 저장소에 Spring Boot 백엔드를 만들고, 설계 문서의 API 7종(방 4 + 메시지 2 + 개발용 사용자 1)을 MySQL과 PostgreSQL, messages 스키마 A와 B, 입장 경계 id와 time에서 모두 동작하게 한다.

## Context
- 설계는 끝났다 (ADR-001~039, 설계 문서 승인 ADR-032). Step 1 구현은 계획 5개로 나눴고(ADR-033), 이 문서는 첫 번째인 "백엔드 핵심"이다.
- 근거 문서: `docs/superpowers/specs/2026-10-06-chat-server-step1-design.md`, `docs/design/domain.md`, `docs/design/architecture.md`, `docs/design/erd.md`
- 현재 저장소에는 `docs/`만 있고 코드가 없다. 재사용할 기존 코드는 없다.
- 이번에 정한 범위 (2026-10-06 사용자 결정):
  - 감사 로그: 계획 1은 **이벤트 발행까지만** 한다. 이벤트를 받아 AUDIT 로거에 쓰는 코드는 계획 3(관측)에서 만든다.
  - 실험용 구현체: **계획 1에서 모두** 만든다. `chat.message-schema=A|B`와 `chat.join-boundary=id|time`이 처음부터 동작한다.
- 계획 1 범위 밖: 테스트용 SQL `db/`(계획 2), Actuator·로그 형식·로그 레벨·감사 로그 수신(계획 3), 프론트엔드(계획 4), 실험 코드(계획 5), WebSocket, Redis, JPA

## 지켜야 할 조건 (설계 문서에서 그대로)
- Java 21, Spring Boot 4.1.1, Gradle Kotlin DSL, 단일 모듈, 기본 패키지 `jissuo.chat`
- 데이터 접근은 `JdbcClient`. Spring Security와 H2는 쓰지 않는다
- Flyway 위치: `backend/src/main/resources/db/migration/{mysql,postgresql}`
- DB 이미지: `mysql:8.4.11`(포트 13306), `postgres:18.6`(포트 15432). 두 DB에 같은 CPU/메모리 제한을 두고 내구성 설정을 맞춘다 (`innodb_flush_log_at_trx_commit=1`, `synchronous_commit=on`)
- URL 접두사 `/api`, 응답은 모두 `ApiResponse`, HTTP 상태 코드는 실제 결과대로
- 에러 코드: `UNAUTHENTICATED` 401, `NOT_A_MEMBER` 403, `ROOM_NOT_FOUND` 404, `ALREADY_MEMBER` 409, `INVALID_REQUEST` 400, `INTERNAL_ERROR` 500(상세 숨김)
- 입력 제한: 방 이름 1~50자, 메시지 1~1000자, 메시지 조회 size 기본 50·최대 100, 방 목록 size 기본 20·최대 50. 글자 수는 코드 포인트로 센다 (ADR-048). 닉네임과 방 이름은 제어 문자와 짝 없는 서로게이트를 거절한다 (ADR-050, 메시지는 작업 8에서 정함)
- 의존 방향: `api → application → domain ← infra`. 기능 사이에는 `message → room.domain`만 허용한다. `domain`은 Spring을 모른다
- **장애 선행 (ADR-034)**: 격리 수준은 각 DB 기본값, 중복 방지 키 없음. F22(커밋 순서 역전)와 F23(나가기와 전송의 경쟁)은 **고치지 않는다**. 구현 중 새 위험을 발견하면 `failure-lab.md`에 가설로 적고 알리기만 한다

## 이 계획에서 새로 정하는 세부 (검토 필요, 승인되면 작업 9 시점의 다음 ADR 번호로 기록. ADR-044·045는 작업 2, ADR-046·047은 작업 3, ADR-048~050은 작업 4 중에 사용)
설계 문서에 없어서 구현하려면 정해야 하는 것들이다.

| # | 항목 | 제안 | 이유 |
|---|---|---|---|
| 1 | 시각 컬럼 정밀도 | MySQL `DATETIME(6)`, PostgreSQL `TIMESTAMP(6)` | 두 DB의 정밀도를 마이크로초로 맞춘다. 초 단위는 F18에서 조건으로 바꿔 본다 |
| 2 | 시각을 찍는 주체 | 애플리케이션 `Clock`(UTC) 주입, DB에는 UTC `LocalDateTime`으로 저장 | F18의 "서버 간 시계 차이" 조건을 `Clock`으로 만들 수 있다 |
| 3 | 개발용 사용자 생성의 인증 | `/api/dev/**`는 `X-User-Id` 검사에서 제외 | 사용자를 만들기 전에는 보낼 id가 없다 |
| 4 | 닉네임 제한 | 1~50자 | 방 이름과 같은 기준. 개발용이라 단순하게 |
| 5 | 방 목록 커서 형식 | 문자열 `"{last_message_id 또는 -}:{id}"` (예: `120:7`, `-:3`). 형식이 틀리면 400 | 사람이 읽을 수 있고 디버깅이 쉽다. 숨길 이유가 아직 없다 |
| 6 | 방 목록 정렬 SQL | 두 DB 공용 `ORDER BY (last_message_id IS NULL), last_message_id DESC, id DESC` | PostgreSQL은 DESC에서 NULL이 앞에 오고 MySQL은 뒤에 온다. 공용 SQL로 차이를 없앤다. `rooms`에는 설계대로 추가 인덱스를 두지 않는다 (F20에서 측정) |
| 7 | 메시지 조회 트랜잭션 | 트랜잭션 없이 쿼리 2개(멤버십 조회, 메시지 조회)를 따로 실행 | 설계 문서는 전송만 한 트랜잭션으로 정했다. 조회 범위는 F1/F22 실험에서 영향을 본다 |
| 8 | 스키마 A/B 저장소 | 클래스 하나(`JdbcMessageRepository`)에 테이블 이름(`messages` / `messages_b`)만 설정으로 넣는다 | 지금은 두 스키마의 SQL이 테이블 이름 말고는 같다. `domain.md`의 `JdbcMessageRepositoryB` 표기를 고친다 |
| 9 | 입장/생성 성공 응답 모양 | 입장 201 `{roomId, userId}`, 방 생성 201 방 정보 | 설계 문서는 상태 코드만 정했다 |

## 파일 구조
```
infra/compose.db.yml
backend/
 ├─ build.gradle.kts, settings.gradle.kts, gradlew…
 └─ src/main/java/jissuo/chat/
     ├─ ChatApplication.java
     ├─ common/   ApiResponse, ErrorCode, ChatException, GlobalExceptionHandler, ChatProperties, ClockConfig
     ├─ auth/     AuthUser, Authenticator, HeaderUserIdAuthenticator, AuthFilter, CurrentUser,
     │            CurrentUserArgumentResolver, AuthWebConfig, AuthenticationFailedEvent
     ├─ user/     domain/(User, Nickname, UserRepository) infra/jdbc/(JdbcUserRepository)
     │            application/(UserService) api/(DevUserController, DTO)
     ├─ room/     domain/(Room, RoomName, RoomListCursor, Membership, JoinBoundary, RoomRepository,
     │                    MembershipRepository, RoomCreatedEvent, MemberJoinedEvent, MemberLeftEvent, AccessDeniedEvent)
     │            application/(RoomService, RoomPage) infra/jdbc/(JdbcRoomRepository, JdbcMembershipRepository)
     │            api/(RoomController, DTO)
     └─ message/  domain/(Message, MessageContent, MessageCursor, MessageRepository)
                  application/(MessageService, MessagePage) infra/jdbc/(JdbcMessageRepository, JoinBoundaryMode, MessageRepositoryConfig)
                  api/(MessageController, DTO)
 ├─ src/main/resources/ application.yml, application-mysql.yml, application-postgres.yml, application-local.yml
 │                      db/migration/{mysql,postgresql}/V1__init.sql
 └─ src/test/java/jissuo/chat/ (단위, 통합, ArchitectureTest)
```

## 작업 순서
각 작업은 테스트 먼저 → 실패 확인 → 구현 → 통과 확인 → 커밋 순서로 진행한다. 작업이 끝날 때마다 사용자와 함께 확인한다.

### 작업 0. 뼈대와 DB 실행 환경
- start.spring.io에서 Boot 4.1.1 / Java 21 / Kotlin DSL / `jissuo.chat`로 `backend/`를 만든다. 의존성은 Web MVC, JDBC, Validation, Flyway(+ MySQL·PostgreSQL 모듈), 두 DB 드라이버, Testcontainers, ArchUnit이다.
  - Boot 4는 starter 이름이 3.x와 다르다 (예상: `spring-boot-starter-webmvc`, `spring-boot-starter-flyway`. Testcontainers 2.x는 `testcontainers-mysql` 형식). **추측하지 않고 생성된 `build.gradle.kts`의 실제 이름을 쓴다.**
- `build.gradle.kts`: 기본 `test` 작업은 `experiment` 태그를 빼고, `experimentTest` 작업은 그 태그만 실행한다.
- `infra/compose.db.yml`: 두 DB의 버전, 포트, 같은 `cpus`/`mem_limit`, 내구성 설정, 볼륨을 정한다.
- 확인: `./gradlew build -x test` 성공, `docker compose -f infra/compose.db.yml up -d` 후 두 DB 접속.

### 작업 1. 스키마 (Flyway, 두 DB × A/B)
- `V1__init.sql`을 DB별로 하나씩 만든다. `users`, `rooms`(`last_message_id` NULL 허용, FK 없음), `room_members`(PK `(room_id, user_id)`, `rooms`·`users`로 FK), `messages`(A: PK `id`, IDX `(room_id, id)`), `messages_b`(B: PK `(room_id, id)`).
  - MySQL B: `id BIGINT AUTO_INCREMENT`, `PRIMARY KEY (room_id, id)`, `KEY (id)` (AUTO_INCREMENT 제약)
  - PostgreSQL: `BIGINT GENERATED ALWAYS AS IDENTITY`
- `application-mysql.yml`과 `application-postgres.yml`에 `spring.flyway.locations`를 넣고, `application-local.yml`에 compose 포트를 넣는다.
- 테스트 지원 클래스: 컨테이너를 JVM당 한 번만 띄우는 `MySqlContainerSupport`, `PostgresContainerSupport` (`@DynamicPropertySource` 사용)
- 테스트: 두 DB에서 마이그레이션 후 테이블 5개가 있는지 확인한다.

### 작업 2. 공용 응답과 예외 처리 (`common`)
- `ApiResponse<T>(boolean success, T data, ErrorBody error)`: `ok(data)`와 `fail(ErrorCode)`로만 만든다.
- `ErrorCode` enum: `(HttpStatus, code, 기본 메시지)`. `ChatException(ErrorCode)`
- `GlobalExceptionHandler`
  - `ChatException` → 해당 상태
  - 입력 검증 실패, 본문 파싱 실패, 쿼리 파라미터 타입 오류 → 400 `INVALID_REQUEST`
  - `DuplicateKeyException` → 409 `ALREADY_MEMBER` (ADR-019)
  - 그 밖의 `DataIntegrityViolationException`과 `Exception` → 500 `INTERNAL_ERROR` + 로그
- `ChatProperties` (`chat.repository`, `chat.message-schema`, `chat.join-boundary`, 기본값 `jdbc`/`A`/`id`), `ClockConfig`(`Clock.systemUTC()`)
- 테스트(단위): `ok`/`fail`의 모양, 테스트용 컨트롤러로 예외별 상태 코드와 본문 확인

### 작업 3. 인증 (`auth`)
- `record AuthUser(long id)`: id가 1 이상이어야 한다
- `Authenticator.authenticate(String credential) → AuthUser`. 실패하면 `ChatException(UNAUTHENTICATED)`. `HeaderUserIdAuthenticator`는 숫자 형식만 검사하고 DB는 조회하지 않는다
- `AuthFilter`: `/api/**`에 적용하고 `/api/dev/**`는 제외한다. 성공하면 요청 속성에 `AuthUser`를 넣는다. 실패하면 401 `ApiResponse`를 직접 쓰고 `AuthenticationFailedEvent`를 발행한다 (필터 예외는 `@RestControllerAdvice`까지 가지 않기 때문)
- `@CurrentUser AuthUser` + `CurrentUserArgumentResolver`, `AuthWebConfig`에 등록. ThreadLocal은 쓰지 않는다
- 테스트(단위): 헤더 없음, 문자, 0, 음수 → 401. 정상 → 컨트롤러에서 `AuthUser` 수신. `/api/dev/users`는 헤더 없이 통과

### 작업 4. 사용자 (`user`, 개발용)
- `Nickname`(1~50자), `UserRepository.save(Nickname, Instant) → long id`, `JdbcUserRepository`, `UserService`
- `DevUserController` `POST /api/dev/users {nickname}` → 201 `{id, nickname}`. `@Profile({"local","bench"})`. 요청 DTO에 `@NotBlank @CodePointLength(max=50) @Pattern(regexp = Nickname.ALLOWED)`를 달아 400을 먼저 낸다 (ADR-045, ADR-048~050)
- 테스트: 단위(`Nickname` 경계값 0/1/50/51자), 통합(두 DB 저장), API(local 프로필에서 201, 다른 프로필에서는 404)

### 작업 5. 방과 멤버십 도메인 + 저장소 (`room.domain`, `room.infra.jdbc`)
- 값 객체: `RoomName`(1~50자, 공백만 있으면 거부), `JoinBoundary(long messageId, Instant joinedAt)` + `JoinBoundary.at(Long lastMessageId, Instant now)`(NULL이면 0), `RoomListCursor.parse/format`
- `Room(id, name, createdBy, Long lastMessageId, createdAt)`, `Membership(roomId, userId, JoinBoundary)`
- `RoomRepository`: `long save(RoomName, long createdBy, Instant)`, `Optional<Room> findById(long)`, `List<Room> findPage(RoomListCursor nullable, int limit)`, `void advanceLastMessageId(long roomId, long messageId)`
  - `advanceLastMessageId` SQL: `UPDATE rooms SET last_message_id = :id WHERE id = :roomId AND (last_message_id IS NULL OR last_message_id < :id)`
  - `findPage` 커서 조건: 커서 값이 있으면 `(lmid < :lmid) OR (lmid = :lmid AND id < :id) OR lmid IS NULL`, 커서가 `-`이면 `lmid IS NULL AND id < :id`
- `MembershipRepository`: `void save(Membership)`, `Optional<Membership> find(long roomId, long userId)`, `boolean delete(long roomId, long userId)`
  - `save`의 예외 변환: `DuplicateKeyException` → `ALREADY_MEMBER`, 그 밖의 무결성 위반(FK) → `UNAUTHENTICATED` (ADR-031)
- 통합 계약 테스트(두 DB): 저장과 조회, 중복 입장 409, 없는 사용자 401, `advance`는 더 큰 번호일 때만 갱신, 목록 정렬(최근 메시지순 → 메시지 없는 방은 생성 역순) + 커서로 넘겨도 누락과 중복이 없는지
- 단위: `RoomName`, `RoomListCursor` 파싱(정상, `-`, 형식 오류), `JoinBoundary.at`

### 작업 6. 방 서비스 + API (`room.application`, `room.api`)
- `RoomService` (생성자로 `Clock`과 `ApplicationEventPublisher`를 받는다)
  - `create(userId, name)` @Transactional: 방 저장 → 생성자 멤버십 저장(경계 0) → `RoomCreatedEvent` (R1)
  - `list(cursor, size)`: `size+1`개를 읽어 `hasMore`와 `nextCursor`를 만든다
  - `join(userId, roomId)` @Transactional: 방이 없으면 404 → `JoinBoundary.at(room.lastMessageId, now)` → 저장 → `MemberJoinedEvent`
  - `leave(userId, roomId)` @Transactional: 삭제된 행이 없으면 `AccessDeniedEvent` 발행 후 403. 삭제했으면 `MemberLeftEvent` (R5)
- `RoomController`: 설계 문서 4장의 URL과 상태 코드, size 범위(1~50)를 검증한다. 방 이름은 DTO에 `@NotBlank @Size(max=50)`, 커서는 `@Pattern("(\\d+|-):\\d+")`로 먼저 막는다 (ADR-045. 도메인의 `RoomName`, `RoomListCursor.parse` 검사는 안전망이고 실패하면 500)
- API 통합 테스트(두 DB, MockMvc): 생성 201 + 생성자가 멤버, 생성 시 이름 0자/51자 400, 없는 사용자 401, 입장 201/404/409, 나가기 200(`data: null`)/403, 목록의 정렬과 커서

### 작업 7. 메시지 도메인 + 저장소 (`message.domain`, `message.infra.jdbc`)
- `MessageContent`(1~1000자), `Message(id, roomId, senderId, content, createdAt)`, `MessageCursor`(`latest()`/`after(id)`/`before(id)`, `of(Long after, Long before)`: 둘 다 있으면 400)
- `MessageRepository`: `Message save(long roomId, long senderId, MessageContent, Instant)`, `List<Message> find(long roomId, JoinBoundary, MessageCursor, int limit)`. 결과는 항상 오래된 것 → 최신 순이다
  - 최신과 `before`: `ORDER BY id DESC LIMIT n`으로 읽고 뒤집는다. `after`: `ORDER BY id ASC LIMIT n`
  - 경계 조건(`JoinBoundaryMode`): `id`면 `id > :joinedMessageId`, `time`이면 `created_at > :joinedAt`
- `MessageRepositoryConfig`: `chat.message-schema`에 따라 테이블 이름(`messages`/`messages_b`)을, `chat.join-boundary`에 따라 모드를 넣어 빈 하나를 만든다. 저장소 구현은 `@ConditionalOnProperty(chat.repository=jdbc, 값이 없어도 기본)`
- 통합 계약 테스트(**MySQL-A, MySQL-B, PostgreSQL-A, PostgreSQL-B 4조합**, 추상 기반 클래스 + 하위 클래스 4개): 최신/after/before의 순서와 limit, 경계 이전 메시지 제외(id 모드, time 모드 각각), 다른 방 메시지 제외
- 단위: `MessageContent` 경계값, `MessageCursor.of`

### 작업 8. 메시지 서비스 + API (`message.application`, `message.api`)
- `MessageService` (`room.domain`의 `MembershipRepository`, `RoomRepository`만 의존하고 `RoomService`는 부르지 않는다)
  - `send(userId, roomId, content)` @Transactional: 멤버십 조회(없으면 `AccessDeniedEvent` 발행 후 403) → 저장 → `advanceLastMessageId` (R3, R7)
  - `read(userId, roomId, cursor, size)`: 멤버십 조회(없으면 403) → `find(…, size+1)` → `MessagePage(messages, hasMore)` (R3, R4)
- `MessageController`: `POST /api/rooms/{roomId}/messages` 201, `GET …?after=|before=&size=` 200. size 범위 1~100. 내용은 DTO에 `@NotNull @Size(min=1, max=1000)`, `after`와 `before`를 함께 주면 컨트롤러가 `ChatException(INVALID_REQUEST)` (ADR-045. `MessageContent`, `MessageCursor.of` 검사는 안전망)
- API 통합 테스트(두 DB): 전송 201 후 방 목록 맨 위로 이동, 비멤버 전송/조회 403, `after`와 `before`를 함께 주면 400, 재입장 후 이전 메시지가 보이지 않음(R4), 폴링 흐름(`after=마지막 id`)

### 작업 9. 의존 방향 검사 (ArchUnit) + 문서 반영
- `ArchitectureTest`: `domain.md` 5장의 두 규칙과 다음 규칙을 함께 검사한다. `room`은 `message`를 모른다. `message`는 `room.domain`만 쓴다. `domain`은 `infra`·`api`·`application`·`org.springframework`를 모른다. `application`은 `api`·`infra`를 모른다. `room`·`message`는 `user`를 모른다
- 인증 규칙 검사 (ADR-047, F24): `/api/**`(`/api/dev/**` 제외) 핸들러는 모두 `@CurrentUser AuthUser` 파라미터를 받는다. 필터가 우회됐을 때 리졸버가 두 번째로 막아 주려면 이 규칙이 지켜져야 한다
- 문서
  - `docs/design/domain.md`: 규칙 표에 "테스트" 열을 채운다. 저장소 표기를 하나의 클래스로 고친다
  - `docs/adr/{진행한 날짜}.md`: 다음 ADR 번호(이 계획에서 정한 세부. ADR-040~043은 작업 0~1, ADR-044·045는 작업 2, ADR-046·047은 작업 3, ADR-048~050은 작업 4 중에 사용)
  - `docs/README.md`의 현재 상태와 체크리스트 B, C(Redis 제외)를 갱신한다
  - `docs/journal/2026-10-06.md`(또는 진행한 날짜의 일지)
  - 이 계획을 `docs/superpowers/plans/2026-10-06-plan1-backend-core.md`에 저장한다

## 감사 이벤트 (계획 3에 넘기는 약속)
| 이벤트 | 발행 위치 | 필드 | 비고 |
|---|---|---|---|
| `RoomCreatedEvent` | `RoomService.create` | roomId, userId, at | 트랜잭션 안에서 발행 |
| `MemberJoinedEvent` | `RoomService.join` | roomId, userId, at | 〃 |
| `MemberLeftEvent` | `RoomService.leave` | roomId, userId, at | 〃 |
| `AccessDeniedEvent` | 서비스의 403 직전 | roomId, userId, code, at | 롤백 트랜잭션 안에서 발행. 계획 3에서 수신 시점(커밋 후는 오지 않음)을 정해야 함 |
| `AuthenticationFailedEvent` | `AuthFilter` | 원래 헤더 값, path, at | 트랜잭션 밖 |
- 계획 1에서는 수신하는 쪽이 없다. 발행 여부는 `RecordApplicationEvents`로 테스트한다.
- IP(`X-Forwarded-For`)는 계획 3에서 수신하는 쪽이 채운다.

## 확인 방법 (끝까지 동작하는지)
1. `cd backend && ./gradlew test`: 단위 + 통합(Testcontainers 두 DB) + ArchUnit 모두 통과. Docker가 필요하다
2. `docker compose -f infra/compose.db.yml up -d`
3. `./gradlew bootRun --args='--spring.profiles.active=local,mysql'` 후 curl 시나리오:
   사용자 2명 생성 → A가 방 생성 → B 입장 → A 전송 3건 → B가 `after=0`으로 폴링 → B 나가기 → A 전송 → B 재입장 → B 조회 시 재입장 이후만 보임 → 방 목록 순서 확인
4. 같은 시나리오를 `local,postgres`, `--chat.message-schema=B`, `--chat.join-boundary=time`으로 반복한다
5. 실패 응답 확인: 헤더 없음 401, 비멤버 403, 중복 입장 409, 이름 51자 400. 모두 `ApiResponse` 모양인지 본다

## 진행 방식
- 승인되면 이 문서를 `docs/superpowers/plans/`에 저장하고, 작업마다 테스트 코드까지 담은 상세 단계로 펼친다 (writing-plans 형식)
- 실행은 이 세션에서 작업 단위로 하고, 작업이 끝날 때마다 결과를 보여 드린 뒤 다음 작업으로 넘어간다 (협업 규칙: 결정 하나씩 의논)

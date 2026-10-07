# 계획 5a: 정합성·경쟁 실험 (F22, F2, F18, F23, F19) 구현 계획

> **실행하는 에이전트에게**: 작업은 하나씩 사용자 승인을 받고 시작한다. 작업이 끝나면 결과(테스트 출력, 결과 CSV 포함)를 보고하고 멈춘다. **커밋하지 않는다** (사용자가 요청할 때만). 단계는 체크박스(`- [ ]`)로 추적한다.

> 2026-10-07 실행 기록: 사용자가 모든 작업을 한 번에 진행하고 결과 보고서만 요청했으므로 단계별 승인·중단 및 커밋 질문은 이번 실행에 적용하지 않았다. 커밋은 하지 않았다.

**목표:** 동시 실행에서만 드러나는 다섯 가지 예상 장애를 MySQL과 PostgreSQL에서 실제로 재현하고 빈도를 센다. 다섯 가지는 커밋 순서 역전으로 인한 영구 누락(F22), 시각 기준 조회의 누락·중복(F2), 입장 경계 오판(F18), 나가기와 전송의 경쟁(F23), 마지막 나가기와 입장의 경쟁(F19)이다. 그 결과로 입장 경계 기준(`joined_message_id`와 `joined_at` 중 무엇을 쓸지)을 결정하고, DB별 경쟁 동작을 계획 5b(부하·DB 비교)의 근거로 넘긴다.

**구조:** 모든 실험은 `backend/src/test/java/jissuo/chat/experiment/`의 `@Tag("experiment")` 테스트이고, `./gradlew experimentTest`로만 실행된다. 기존 통합 테스트처럼 Spring 컨텍스트와 Testcontainers를 쓰며, HTTP를 거치지 않고 서비스 계층(`MessageService`, `RoomService`)을 직접 부른다. 실험마다 두 가지 형태를 둔다.
- **결정적 재현**: `@MockitoSpyBean`으로 저장소 호출 직후 한 지점에서 멈추고, 그 사이에 경쟁 상대를 끝까지 실행해 가설의 순서를 강제로 만든다. 결과를 assert한다.
- **빈도 측정**: 여러 스레드를 자연스럽게 동시에 돌리고 건수만 CSV로 남긴다. assert는 실험 도구가 올바로 동작했는지만 확인한다.

**기술:** 기존 의존성만 쓴다(JUnit 5, AssertJ, Spring Boot Test, Testcontainers `mysql:8.4.11`·`postgres:18.6`, Mockito). 새 라이브러리는 없다.

## Context
- 계획 4(프론트엔드)가 끝났다. ADR-033은 Step 1을 계획 5개로 나눴고, 남은 것은 계획 5(실험)다.
- 계획 5의 범위(설계 문서 8장)가 커서 두 개로 나눈다 (사용자 결정, 2026-10-07).
  - **5a 정합성·경쟁 실험(이 문서)**: JUnit 실험으로 누락·유출 건수를 센다.
  - **5b 부하·DB 비교**: k6로 p99를 재고 DB 선택 ADR을 쓴다.
  - 5a를 먼저 하는 이유: 5a 결과로 입장 경계가 정해지고(F18), DB별 경쟁 동작이 DB 선택의 근거가 된다.
- 부하 도구는 **k6**로 정했다 (사용자 결정, 2026-10-07). 5b에서 쓴다.
- 이미 결정된 것 (그대로 따른다)
  - ADR-034: 장애 선행. 격리 수준은 각 DB 기본값이다.
  - ADR-009: 입장 경계의 판정은 커밋 순서 기준이다.
  - ADR-012: 실제 기능은 빈 방을 유지한다. 방 삭제는 F19 실험에서만 쓴다.
  - ADR-037: F23(나가기와 전송의 경쟁)은 일부러 열어 둔 문제다.
  - 설계 문서 8장의 판정 기준을 따른다.
- 확인한 코드 사실 (커밋 `34ef1ab` 기준)
  - `MessageService.send`(`backend/src/main/java/jissuo/chat/message/application/MessageService.java:37`)는 `@Transactional` 하나로 묶여 있고 순서는 다음과 같다. 잠금은 없다.
    1. 멤버 행 SELECT
    2. `messages` INSERT
    3. `rooms.last_message_id` 조건부 UPDATE
  - `MessageService.read`에는 트랜잭션이 없다. 멤버 조회와 메시지 조회가 따로 실행된다.
  - `RoomService.join`(`.../room/application/RoomService.java:64`)은 `rooms.findById`(잠금 없음) → `JoinBoundary.at(room.lastMessageId(), clock.instant())` → `room_members` INSERT 순서다.
  - `leave`는 `room_members` DELETE이고 지운 행이 없으면 `NOT_A_MEMBER`다.
  - `JdbcMessageRepository.find`의 커서는 항상 id 기준이다. 경계 컬럼만 `chat.join-boundary=id|time`으로 바뀐다. **시각 기준 커서는 없다** (F2는 테스트 코드 SQL로 재현한다).
  - `room_members`에는 `rooms`, `users`를 가리키는 FK가 있다. `messages`에는 FK가 없다. 방 삭제 기능은 없다.
  - `JdbcMembershipRepository.save`는 FK 위반을 401(`UNAUTHENTICATED`)로 바꾼다(L42-45, F19도 여기로 온다). ADR-019 문구("그 밖 = 500")와 다르니 F19 결과에 함께 적는다.
  - HikariCP 풀 크기는 설정이 없어 기본 10이다. 테스트 태스크는 모두 `minimum-idle=0`이다(`backend/build.gradle.kts:44-49`).
  - `experimentTest` 태스크는 이미 있다(`build.gradle.kts:57-65`). `@Tag("experiment")` 테스트는 아직 하나도 없다.
  - 컨테이너 지원: `jissuo.chat.support.MySqlContainerSupport` / `PostgresContainerSupport`의 `register(registry)`.
  - 패턴: 추상 Contract 클래스 + DB별 구체 클래스(`MySqlAMessageRepositoryTest` 등).
  - ArchUnit은 테스트 클래스를 검사하지 않는다(`ImportOption.DoNotIncludeTests`).

## 지켜야 할 조건
- **장애 선행 (ADR-034)**: 운영 코드(`backend/src/main/**`)를 바꾸지 않는다. 실험이 장애를 재현해도 고치지 않는다. 해결책 비교는 재현 결과를 사용자와 함께 본 뒤 따로 정한다. F19의 방 삭제 모드와 F2의 시각 커서는 **테스트 코드에만** 둔다.
- 기존 `./gradlew test`가 그대로 통과해야 한다. 실험 클래스는 모두 `@Tag("experiment")`라서 `test`에서 빠진다.
- **결정적 재현의 결과가 가설과 다르면 assertion을 고치지 않는다.** 멈추고 실제 결과를 보고한다. 사용자와 의논한 뒤 failure-lab에 "재현 안 됨"으로 기록한다.
- 결과를 적을 때 "예상"과 "측정"을 구분하고, DB와 격리 수준 조건을 함께 적는다. 이 계획에 적힌 "예상"은 모두 추론이고 미검증이다.
- 코드 주석은 "왜"만 쓴다. 작업 중에는 근거를 `계획 5a 세부 #n`으로 적고, 작업 7에서 ADR 번호로 바꾼다.
- Mac Docker의 수치는 상대 비교용이다. 건수는 시도 횟수와 함께 적는다.

## 이 계획에서 새로 정하는 세부 (검토 필요, 승인되면 작업 7에서 ADR-088부터 기록)
| # | 항목 | 제안 | 이유 |
|---|---|---|---|
| 1 | 5a 범위 | F22, F2, F18, F23, F19의 재현과 빈도 측정. 해결책 비교와 F19의 대량 삭제 부하(undo, dead tuple)는 넣지 않는다 | 해결책은 재현 후 함께 정한다(ADR-034). 대량 삭제는 bulk 데이터와 부하가 필요해 5b에서 한다 |
| 2 | 실행 계층 | 서비스 계층을 직접 부른다. HTTP(MockMvc, k6)는 쓰지 않는다 | 경쟁은 트랜잭션 안에서 생긴다. HTTP 계층은 타이밍 잡음만 더한다. HTTP 부하는 5b에서 잰다 |
| 3 | 실험 형태 | 실험마다 결정적 재현(Spy로 한 지점에서 멈춤, assert) 1개 이상과 빈도 측정(자연 동시 실행, CSV) 1개 | 자연 동시 실행만 하면 0건이 나와도 "불가능"인지 "드물다"인지 구분할 수 없다. 결정적 재현으로 가능한지 먼저 확인하고, 빈도는 따로 센다 |
| 4 | 순서 판정 | 전역 단조 증가 번호(`Ticks`)를 호출 직전과 반환 직후에 찍어 구간을 만든다. 두 구간이 겹치면 "동시 진행"으로 보고 판정하지 않는다 | ADR-009의 "커밋 순서 기준"을 테스트 밖에서 관찰할 수 있는 방법이다. 반환 직후는 커밋 뒤이고, 호출 직전은 트랜잭션 시작 전이다. 겹치지 않으면 순서가 확실하다. 벽시계는 같은 값이 나올 수 있어서 쓰지 않는다 |
| 5 | 조건 | 3개다: MySQL 기본(REPEATABLE READ), MySQL READ COMMITTED, PostgreSQL 기본(READ COMMITTED). 격리 수준은 실험 클래스의 `spring.datasource.hikari.transaction-isolation`으로만 바꾼다 | failure-lab F22·F23이 "DB와 격리 수준별" 측정을 요구한다. PostgreSQL은 기본값이 이미 READ COMMITTED라 조건이 하나다. 운영 설정은 바꾸지 않는다(ADR-034) |
| 6 | 스키마 | A(`messages`)만 쓴다 | 경쟁 동작은 PK 구조와 상관없을 것으로 예상한다(미검증). A/B 비교는 5b(F15·F16)에서 한다 |
| 7 | 풀 크기 | 실험 클래스에서만 `maximum-pool-size=64`. 클래스마다 `@DirtiesContext`로 컨텍스트를 닫는다 | 기본 10이면 50개 스레드가 풀을 기다리며 줄을 서서 경쟁이 줄어든다(풀 고갈 F1은 5b). 컨텍스트를 닫지 않으면 MySQL 기본 접속 한도 151을 넘을 수 있다(계획 3에서 `Too many connections` 겪음) |
| 8 | 규모 | 동시 쓰기 1/10/50. F22·F2는 각 5초 실행. F18은 조건마다 입장 300회 × 시계 차이 −50/0/+50ms. F23·F19는 각 1000회 | `experimentTest` 한 번이 30분 안에 끝나는 규모로 예상한다(미측정). 작업 2에서 실제 시간을 재고 다시 정한다 |
| 9 | F18 입장자 | 매번 새 사용자가 첫 입장한다. 같은 사용자의 나가기·재입장은 하지 않는다 | 경계 계산 코드(`RoomService.join`)가 첫 입장과 재입장에서 같다. 같은 사용자를 쓰면 다음 재입장이 이전 경계를 덮어써서 나중에 판정할 수 없다 |
| 10 | F18 판정 범위 | 입장 구간 앞뒤 500개 id 안의 메시지만 판정한다 | 방 전체를 매번 다시 읽으면 입장 300회 × 수만 건이 된다. 경계에서 먼 메시지는 두 기준 모두 결과가 분명하다 |
| 11 | 결과 기록 | `backend/build/experiment-results/{실험}.csv`(조건, 시도 수, 건수)와 표준 출력. 해석은 `docs/reports/{날짜}-plan5a-consistency.md` | 측정값을 재실행 없이 다시 보기 위해서다. 보고서는 계획 2·3처럼 남긴다(승인이 필요하다) |
| 12 | 제외 조건 | F18의 시각 정밀도 변형(초 단위 `DATETIME`)과 DB `NOW()` 변형은 5a에서 하지 않는다 | 마이그레이션(운영 코드)을 바꿔야 한다. 결과를 본 뒤 필요하면 사용자와 정한다 |

---

## 파일 구조
```
backend/src/test/java/jissuo/chat/experiment/
 ├─ support/
 │   ├─ Ticks.java                 전역 단조 증가 번호와 Span
 │   ├─ ExperimentResults.java     CSV 기록
 │   ├─ ExperimentFixtures.java    사용자·방 생성, 메시지 id 조회
 │   └─ Concurrently.java          N개 스레드를 동시에 시작해 기한까지 반복
 ├─ SmokeExperiment.java           (작업 1) experimentTest 동작 확인
 ├─ commitorder/   CommitOrderExperiment(추상) + MySql/MySqlReadCommitted/Postgres 구체 클래스   (F22)
 ├─ timecursor/    TimeCursorExperiment(추상) + 구체 3개, TimeCursorPoller                    (F2)
 ├─ joinboundary/  JoinBoundaryExperiment(추상) + 구체 6개 (조건 3 × id|time)                 (F18)
 ├─ leavesend/     LeaveSendExperiment(추상) + 구체 3개                                         (F23)
 └─ lastleave/     LastLeaveExperiment(추상) + 구체 3개, LeaveAndDeleteRoom                     (F19)
```
구체 클래스의 형식은 모두 같다. 아래 예시에서 클래스 이름, 부모, 조건 문자열, 컨테이너, 격리 수준 속성만 바꾼다.
```java
@SpringBootTest(properties = {"chat.message-schema=A", "spring.datasource.hikari.maximum-pool-size=64"})
@ActiveProfiles("mysql")
@DirtiesContext
class MySqlCommitOrderExperiment extends CommitOrderExperiment {
    @Override String condition() { return "mysql-rr"; }
    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) { MySqlContainerSupport.register(registry); }
}
```
- READ COMMITTED 클래스는 `properties`에 `"spring.datasource.hikari.transaction-isolation=TRANSACTION_READ_COMMITTED"`를 더하고, 조건 문자열은 `"mysql-rc"`다.
- PostgreSQL 클래스는 `@ActiveProfiles("postgres")`와 `PostgresContainerSupport`를 쓰고, 조건 문자열은 `"pg-rc"`다.
- `@Tag("experiment")`는 추상 클래스에 붙인다. JUnit의 `@Tag`는 `@Inherited`라서 구체 클래스에 상속된다.

---

## 작업 0. 계획 저장
- [x] 이 문서를 `docs/superpowers/plans/2026-10-07-plan5a-consistency-experiments.md`에 저장한다. 계획 4 기록의 마지막 ADR은 087이므로 이 계획은 ADR-088부터 쓴다.

---

## 작업 1. 실험 공통 도구와 `experimentTest` 확인

**Files:**
- Create: `experiment/support/{Ticks,ExperimentResults,ExperimentFixtures,Concurrently}.java`, `experiment/SmokeExperiment.java`

**Interfaces:**
- Produces:
  - `Ticks.next(): long`
  - `record Span(long start, long end)`와 `boolean before(Span other)`(`end < other.start`)
  - `ExperimentResults.record(String experiment, String header, String row)`
  - `ExperimentFixtures(JdbcClient)`: `long user(String nickname)`, `List<Long> messageIds(long roomId)`
  - `Concurrently.run(int threads, Duration duration, ThrowingRunnable body): List<Throwable>`

- [x] **Step 1: 도구 작성**
```java
// Ticks.java
package jissuo.chat.experiment.support;

/** 계획 5a 세부 #4: 벽시계는 같은 값이 나올 수 있어서 호출 순서를 전역 번호로 비교한다. */
public final class Ticks {
    private static final AtomicLong NEXT = new AtomicLong();
    private Ticks() {}
    public static long next() { return NEXT.incrementAndGet(); }

    /** start는 호출 직전(트랜잭션 시작 전), end는 반환 직후(커밋 뒤)에 찍는다. */
    public record Span(long start, long end) {
        public boolean before(Span other) { return end < other.start; }
    }
}

// ExperimentResults.java
public final class ExperimentResults {
    private static final Path DIR = Path.of("build", "experiment-results");
    private ExperimentResults() {}
    public static synchronized void record(String experiment, String header, String row) {
        try {
            Files.createDirectories(DIR);
            Path file = DIR.resolve(experiment + ".csv");
            if (Files.notExists(file)) {
                Files.writeString(file, header + "\n");
            }
            Files.writeString(file, row + "\n", StandardOpenOption.APPEND);
            System.out.println("[experiment] " + experiment + " " + header + " = " + row);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

// ExperimentFixtures.java
public class ExperimentFixtures {
    private final JdbcClient jdbc;
    public ExperimentFixtures(JdbcClient jdbc) { this.jdbc = jdbc; }

    public long user(String nickname) {
        var key = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO users (nickname, created_at) VALUES (:n, :t)")
                .param("n", nickname).param("t", LocalDateTime.now(ZoneOffset.UTC))
                .update(key, "id");
        return key.getKey().longValue();
    }

    public List<Long> messageIds(long roomId) {
        return jdbc.sql("SELECT id FROM messages WHERE room_id = :r ORDER BY id")
                .param("r", roomId).query(Long.class).list();
    }
}

// Concurrently.java
public final class Concurrently {
    @FunctionalInterface public interface ThrowingRunnable { void run() throws Exception; }
    private Concurrently() {}

    /** 모든 스레드를 같은 순간에 출발시키고, 기한까지 body를 반복한다. 예외는 모아서 돌려준다. */
    public static List<Throwable> run(int threads, Duration duration, ThrowingRunnable body)
            throws InterruptedException {
        var errors = new ConcurrentLinkedQueue<Throwable>();
        var start = new CountDownLatch(1);
        long deadline = System.nanoTime() + duration.toNanos();
        try (var pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    start.await();
                    while (System.nanoTime() < deadline) {
                        try { body.run(); } catch (Throwable t) { errors.add(t); }
                    }
                    return null;
                });
            }
            start.countDown();
        } // close()가 모든 작업이 끝날 때까지 기다린다
        return List.copyOf(errors);
    }
}
```
- [x] **Step 2: 스모크 실험**: `SmokeExperiment`(`@Tag("experiment")`, MySQL)를 만든다. 사용자 1명이 방을 만들고 `Concurrently.run(4, 1초)`로 메시지를 보낸다. `ExperimentFixtures.messageIds`의 건수가 성공한 전송 수와 같고 예외가 0인지 assert한다. 결과는 `ExperimentResults.record("smoke", "sent", n)`로 남긴다.
- [x] **Step 3: 실행 확인**
  - `./gradlew experimentTest --tests '*SmokeExperiment'`: 통과하고 `build/experiment-results/smoke.csv`가 생겨야 한다.
  - `./gradlew test`: 통과해야 하고, 출력에 `SmokeExperiment`가 없어야 한다(태그 제외 확인).
- [x] **Step 4**: 결과를 보고하고 멈춘다.

---

## 작업 2. F22 커밋 순서 역전으로 인한 영구 누락

**가설(failure-lab F22):** 번호를 먼저 받은 메시지가 늦게 커밋되면, `after` 폴링이 그 메시지를 영원히 받지 못한다. 동시 전송만으로 생긴다.

**Files:** Create `experiment/commitorder/CommitOrderExperiment.java`와 구체 클래스 3개 (`MySql…`, `MySqlReadCommitted…`, `Postgres…`)

- [x] **Step 1: 결정적 재현.** 첫 전송의 `messages.save`가 반환된 직후(번호는 받았고 커밋 전)에 멈추고, 그 사이에 두 번째 전송을 끝까지 실행하고 폴링한다.
```java
@Tag("experiment")
abstract class CommitOrderExperiment {
    @Autowired MessageService messageService;
    @Autowired RoomService roomService;
    @Autowired JdbcClient jdbc;
    @MockitoSpyBean MessageRepository messages;
    abstract String condition();

    @Test
    void 먼저_번호를_받은_메시지가_늦게_커밋되면_after_폴링이_영구히_놓친다() throws Exception {
        var fixtures = new ExperimentFixtures(jdbc);
        long owner = fixtures.user("owner");
        long roomId = roomService.create(owner, "f22").id();
        var polledBeforeFirstCommit = new AtomicReference<List<Long>>();
        var once = new AtomicBoolean();
        doAnswer(invocation -> {
            Message saved = (Message) invocation.callRealMethod();
            if (once.compareAndSet(false, true)) {
                // 이 스레드의 트랜잭션은 아직 열려 있다. 다른 스레드에서 두 번째 전송과 폴링을 끝낸다.
                CompletableFuture.runAsync(() -> {
                    messageService.send(owner, roomId, "second");
                    polledBeforeFirstCommit.set(ids(messageService.read(owner, roomId, MessageCursor.after(0), 50)));
                }).get();
            }
            return saved;
        }).when(messages).save(anyLong(), anyLong(), any(), any());

        long first = messageService.send(owner, roomId, "first").id();
        long cursor = polledBeforeFirstCommit.get().getLast();
        List<Long> next = ids(messageService.read(owner, roomId, MessageCursor.after(cursor), 50));

        ExperimentResults.record("f22-deterministic", "condition,first,polled,next",
                condition() + "," + first + "," + polledBeforeFirstCommit.get() + "," + next);
        assertThat(polledBeforeFirstCommit.get()).containsExactly(first + 1); // 예상: 두 번째만 보인다
        assertThat(next).isEmpty();                                             // 예상: first는 다시 오지 않는다
        assertThat(new ExperimentFixtures(jdbc).messageIds(roomId)).containsExactly(first, first + 1);
    }

    static List<Long> ids(MessagePage page) { return page.messages().stream().map(Message::id).toList(); }
}
```
  - 두 번째 메시지의 id가 `first + 1`이라는 것은 다른 트랜잭션이 끼지 않는다는 가정이다.
  - 결과가 다르면(예: MySQL에서 INSERT가 서로 막힘) assert를 고치지 않고 멈춘다.
- [x] **Step 2: 실행.** `./gradlew experimentTest --tests '*CommitOrderExperiment'`로 실행한다. 조건 3개의 결과를 확인한다.
- [x] **Step 3: 빈도 측정**(`@ParameterizedTest @ValueSource(ints = {1, 10, 50})`)
  - 작성자 `writers`명이 모두 방에 입장한 뒤 `Concurrently.run(writers, 5초)`로 `send`한다.
  - 같은 시간에 폴링 스레드 1개가 `after` 커서로 `read(size 100)`를 쉬지 않고 반복해 받은 id를 모은다. 커서는 받은 마지막 id다.
  - 작성자가 끝나면 폴링이 빈 응답을 받을 때까지 한 번 더 읽고 멈춘다.
  - 영구 누락은 `messageIds(roomId)`에서 받은 id를 뺀 것이다.
  - 기록: `condition,writers,sent,polls,missed`.
  - assert는 실험 도구 확인만 한다: 전송 예외 0, `messageIds` 건수 == 성공한 전송 수, 받은 id에 중복 없음.
  - 예상(미검증): writers=1이면 0이고, 10·50에서는 0보다 크다.
- [x] **Step 4**: 실행 시간과 CSV를 보고하고 멈춘다. 세부 #8의 규모가 너무 크거나 작으면 이때 조정을 제안한다.

---

## 작업 3. F2 시각 기준 조회의 누락·중복

**가설(failure-lab F2):** `created_at > 마지막 시각`으로 폴링하면 같은 시각의 메시지가 누락되고, `>=`로 바꾸면 중복된다. 또 `created_at`은 INSERT 전에 찍히므로 커밋 순서와 달라 누락이 더 생긴다.

**Files:** Create `experiment/timecursor/TimeCursorPoller.java`, `TimeCursorExperiment.java`와 구체 클래스 3개

**Interfaces:** Produces `TimeCursorPoller(JdbcClient, long roomId, boolean inclusive, int limit)`와 `List<Long> poll()`. 마지막으로 받은 `created_at`을 내부 커서로 가진다.

- [x] **Step 1: 시각 커서(테스트 전용, 세부 #1·#10)**
```java
/** F2 재현용. 운영 코드는 id 커서만 쓴다. 채택하지 않은 시각 커서를 테스트에서만 흉내 낸다. */
class TimeCursorPoller {
    private final JdbcClient jdbc; private final long roomId; private final boolean inclusive; private final int limit;
    private LocalDateTime cursor = LocalDateTime.of(1970, 1, 1, 0, 0);
    TimeCursorPoller(JdbcClient jdbc, long roomId, boolean inclusive, int limit) { ... }

    List<Long> poll() {
        record Row(long id, LocalDateTime createdAt) {}
        List<Row> rows = jdbc.sql("SELECT id, created_at FROM messages WHERE room_id = :r AND created_at "
                        + (inclusive ? ">=" : ">") + " :t ORDER BY created_at, id LIMIT :n")
                .param("r", roomId).param("t", cursor).param("n", limit)
                .query((rs, i) -> new Row(rs.getLong(1), rs.getObject(2, LocalDateTime.class))).list();
        if (!rows.isEmpty()) cursor = rows.getLast().createdAt();
        return rows.stream().map(Row::id).toList();
    }
}
```
- [x] **Step 2: 결정적 재현 2개**
  - (a) 페이지 경계: `MessageRepository.save`로 같은 시각 `T`인 메시지 3개를 저장한다.
    - `>` 커서, limit 2: 두 번 폴링한 결과는 `[m1,m2]`, `[]`이다. m3가 누락된다.
    - `>=` 커서, limit 2: 두 번 폴링한 결과는 `[m1,m2]`, `[m1,m2]`이다. 같은 페이지만 반복되고 m3에 영영 닿지 않는다.
  - (b) 늦게 온 같은 시각: m1을 `T`로 저장하고 `>`로 폴링한다(`[m1]`). 그다음 m2를 같은 `T`로 저장하고 다시 폴링한다(`[]`). 서버 2대의 시계가 같은 값을 낸 상황이다.
  - 같은 데이터에 id 커서(`MessageService.read(after)`)는 누락이 없는 것도 함께 assert한다(비교 기준).
- [x] **Step 3: 빈도 측정**(writers 1/10/50, 각 5초). 작업 2 Step 3과 같은 전송 부하를 건다. 폴링 스레드 하나가 매 회차마다 아래 세 커서를 차례로 부른다.
  - id 커서(`MessageService.read`)
  - 시각 `>` 커서
  - 시각 `>=` 커서. 이미 받은 id는 중복으로 세고 버린다.
  - 기록: `condition,writers,sent,id_missed,time_gt_missed,time_ge_missed,time_ge_duplicates`
  - 예상(미검증): 누락은 시각 `>` ≥ id이다. 시각 쪽은 F22의 원인(커밋 순서)에 시각을 INSERT 전에 찍는 문제가 더해진다.
- [x] **Step 4**: 실행 후 보고하고 멈춘다.

---

## 작업 4. F18 입장 경계 (`joined_message_id`와 `joined_at`)

**판정(ADR-009, experiments.md "입장 경계 실험 설계"):** 입장 구간과 메시지 구간을 비교한다.
- 메시지가 입장 시작 전에 끝났는데(`msg.before(join)`) 보이면 **유출**이다.
- 메시지가 입장 커밋 뒤에 시작했는데(`join.before(msg)`) 안 보이면 **유실**이다.
- 두 구간이 겹치면 판정하지 않는다.
- 입장 직후의 실시간 폴링 결과와 끝난 뒤의 목록 조회 결과가 다르면 **불일치**다.

**Files:** Create `experiment/joinboundary/JoinBoundaryExperiment.java`와 구체 클래스 6개
- 조건 3개 × `chat.join-boundary=id|time`
- 이름 예: `MySqlIdJoinBoundaryExperiment`, `MySqlTimeJoinBoundaryExperiment`, `MySqlReadCommittedIdJoinBoundaryExperiment`, `PostgresTimeJoinBoundaryExperiment`
- 조건 문자열에 경계 방식을 붙인다(예: `"mysql-rr-time"`).

- [x] **Step 1: 결정적 재현 (시각 경계의 시계 차이와 같은 마이크로초)**
  - 입장자용 `RoomService`를 `new RoomService(rooms, memberships, Clock.offset(Clock.systemUTC(), skew), events)`로 직접 만든다. 운영 빈은 바꾸지 않는다.
  - (a) skew = −50ms: 메시지를 커밋한 다음 입장한다. time 클래스에서는 그 메시지가 보이고(유출), id 클래스에서는 안 보인다.
  - (b) skew = +50ms: 입장한 다음 메시지를 보낸다. time 클래스에서는 안 보이고(유실), id 클래스에서는 보인다.
  - (c) 같은 시각: 고정 `Clock`으로 입장과 메시지 저장을 같은 `T`로 맞춘다(메시지는 `MessageRepository.save`). time 클래스에서 `created_at > joined_at`이 거짓이 되어 유실된다.
  - assert는 클래스의 경계 방식(`@Value("${chat.join-boundary}")`)에 따라 갈린다.
- [x] **Step 2: 빈도 측정**(`@ParameterizedTest`, writers 1/10/50 × skew −50/0/+50ms. 조합마다 입장 300회. 세부 #8·#9)
  - 작성자들은 방에 입장한 뒤 실험이 끝날 때까지 `send`를 반복한다. 메시지마다 `id → Span`을 `ConcurrentHashMap`에 남긴다. `Span`은 `send` 호출 직전과 반환 직후의 `Ticks`다.
  - 입장 회차마다 다음을 한다.
    1. 새 사용자를 만든다.
    2. `Span join = [Ticks.next(), skewedRoomService.join(u, room), Ticks.next()]`
    3. `read(latest, 100)` 후 `after` 커서로 10회 폴링한다(2ms 간격). 받은 id 집합 `P(u)`와 `join`을 저장한다.
  - 작성자를 멈춘 뒤 사용자마다 판정 창 `[lo, hi]`를 정한다(세부 #10).
    - `lo` = `join.start` 이후에 끝난 메시지 중 가장 작은 id − 500
    - `hi` = `join.end` 이전에 시작한 메시지 중 가장 큰 id + 500
    - `read(after(lo-1), 100)`를 `hi`를 넘을 때까지 넘겨서 보이는 집합 `L(u)`를 얻는다.
  - 판정
    - 창 안의 각 메시지 m에 대해, `m.before(join)` && m ∈ L이면 유출, `join.before(m)` && m ∉ L이면 유실이다.
    - 불일치 = `(L ∩ [min(P), max(P)]) Δ P`가 비지 않은 사용자 수와 그 id 수.
  - 기록: `condition,writers,skew_ms,joins,judged,concurrent,leaks,losses,inconsistent_users,inconsistent_ids`
  - assert는 실험 도구 확인만 한다: 입장 예외 0, 판정 대상 수 > 0.
  - 예상(미검증)
    - id 경계는 유출·유실이 0이다. 커밋된 메시지는 `last_message_id`를 이미 전진시켰고, 입장 커밋 뒤에 시작한 메시지는 더 큰 번호를 받기 때문이다.
    - 시각 경계는 skew ≠ 0에서 유출 또는 유실이 생긴다.
    - 불일치는 두 경계 모두 F22 때문에 생길 수 있다.
- [x] **Step 3**: 6개 클래스를 실행하고 결과 표를 보고하고 멈춘다. **입장 경계를 결정하는 일은 작업 7에서 사용자와 함께 한다.**

---

## 작업 5. F23 나가기와 메시지 전송의 경쟁

**가설(failure-lab F23, ADR-037):** 전송의 멤버 확인과 메시지 저장 사이에 나가기가 커밋되면 비멤버의 메시지가 저장된다.

**Files:** Create `experiment/leavesend/LeaveSendExperiment.java`와 구체 클래스 3개

- [x] **Step 1: 결정적 재현.** `@MockitoSpyBean MembershipRepository memberships`를 쓴다. 전송 스레드의 `find`가 반환된 직후에 한 번만 다른 스레드에서 `roomService.leave(u, room)`를 끝까지 실행하고 기다린다(`CompletableFuture.runAsync(...).get()`).
  - assert(예상)
    - `send`가 성공한다.
    - `room_members`에 (room, u) 행이 없다.
    - `messages`에 u가 보낸 메시지가 1건 있다(비멤버 메시지).
  - MySQL REPEATABLE READ에서는 일반 SELECT가 잠금을 잡지 않으므로 나가기가 막히지 않을 것으로 예상한다(미검증).
- [x] **Step 2: 빈도 측정**(1000회)
  - 매 회차: 새 사용자가 입장한다(준비 단계, 타이밍 밖).
  - `CyclicBarrier(2)`로 두 작업을 동시에 출발시킨다.
    - (A) `TransactionTemplate` 안에서 `send`를 부른다. `TransactionSynchronization.beforeCommit`에서 `Ticks`를 찍는다(`sendCommitStart`). `send`는 바깥 트랜잭션에 합류한다(REQUIRED).
    - (B) `leave`를 부르고, 반환 직후 `Ticks`를 찍는다(`leaveEnd`).
  - 분류
    - 전송 성공/403/기타 × 나가기 성공/403
    - **확실한 위반**: 둘 다 성공 && `leaveEnd < sendCommitStart` (나가기가 커밋된 뒤에 전송이 커밋을 시작했다)
    - **모호**: 둘 다 성공했지만 구간이 겹침
  - 기록: `condition,tries,send_ok_leave_ok,send_403,violations,ambiguous,other_errors`
  - 끝난 뒤 DB 검사(측정값): 보낸 사람이 지금 멤버가 아닌 메시지 중 보낸 사람의 나가기가 기록된 회차의 수. 이 값은 정의상 위반과 정상(나가기 전에 보낸 메시지)을 모두 포함하므로 참고값으로만 적는다.
- [x] **Step 3**: 실행 후 보고하고 멈춘다.

---

## 작업 6. F19 마지막 나가기와 입장의 경쟁 (실험용 방 삭제)

**가설(failure-lab F19):** "마지막 멤버가 나가면 방 삭제"를 넣으면, 삭제와 입장이 겹칠 때 삭제된 방에 입장하거나(FK 에러) 없는 방의 멤버가 생긴다. 방 삭제 기능은 실험 코드에만 있다(ADR-012).

**Files:** Create `experiment/lastleave/LeaveAndDeleteRoom.java`, `LastLeaveExperiment.java`와 구체 클래스 3개

**Interfaces:** Produces `LeaveAndDeleteRoom(JdbcClient, TransactionTemplate)`과 `Result leave(long roomId, long userId, Runnable afterCount)`. `Result`는 `record Result(boolean roomDeleted, String error)`다.

- [x] **Step 1: 실험용 삭제**(테스트 코드)
```java
/** F19 재현 전용. 실제 기능은 빈 방을 유지한다(ADR-012). 확인과 삭제 사이에 잠금이 없는 단순한 방식을 일부러 쓴다. */
class LeaveAndDeleteRoom {
    Result leave(long roomId, long userId, Runnable afterCount) {
        try {
            return tx.execute(status -> {
                jdbc.sql("DELETE FROM room_members WHERE room_id = :r AND user_id = :u")
                        .param("r", roomId).param("u", userId).update();
                long left = jdbc.sql("SELECT COUNT(*) FROM room_members WHERE room_id = :r")
                        .param("r", roomId).query(Long.class).single();
                afterCount.run();
                if (left > 0) return new Result(false, null);
                jdbc.sql("DELETE FROM rooms WHERE id = :r").param("r", roomId).update();
                return new Result(true, null);
            });
        } catch (DataAccessException e) {
            return new Result(false, e.getClass().getSimpleName());
        }
    }
}
```
- [x] **Step 2: 결정적 재현 2개**
  - (a) 입장이 방을 확인한 뒤 삭제
    - `@MockitoSpyBean RoomRepository rooms`로 `join`의 `findById`가 반환된 직후에 멈춘다.
    - 다른 스레드에서 마지막 멤버의 `LeaveAndDeleteRoom.leave`를 끝까지 실행한다.
    - 그다음 입장의 INSERT가 FK 위반을 일으키고 `ChatException(UNAUTHENTICATED)`가 된다고 예상한다. 현재 코드의 변환 결과이고, ADR-019 문구와 다르다.
    - 방은 없고 멤버 행은 0이다.
  - (b) 삭제가 남은 멤버 수를 센 뒤 입장
    - `afterCount`에서 다른 스레드의 `roomService.join(B)`를 끝까지 실행한다.
    - 그다음 `DELETE FROM rooms`가 B의 멤버 행 때문에 FK 위반으로 실패하고, 삭제 트랜잭션이 롤백된다고 예상한다. A의 나가기도 함께 롤백된다.
    - 결과로 방과 A, B가 모두 남는다.
  - 두 시나리오 모두 고아 멤버(방이 없는 `room_members` 행)는 0이라고 예상한다. FK가 막기 때문이다.
- [x] **Step 3: 빈도 측정**(1000회)
  - 매 회차: A가 방을 만든다(A만 멤버). 그다음 `CyclicBarrier(2)`로 A의 `LeaveAndDeleteRoom.leave`와 B의 `roomService.join`을 동시에 출발시킨다.
  - 분류
    - 입장 결과: 성공 / `ROOM_NOT_FOUND` / `UNAUTHENTICATED` / 기타 예외 이름
    - 삭제 결과: 삭제됨 / 남김 / 예외 이름 (데드락 포함)
    - 최종 상태: 방 존재 여부, 멤버 수
    - 고아 멤버 수: `SELECT COUNT(*) FROM room_members m LEFT JOIN rooms r ON r.id = m.room_id WHERE r.id IS NULL`
  - 기록: 조합별 건수를 한 줄씩 남긴다.
- [x] **Step 4**: 실행 후 보고하고 멈춘다.

---

## 작업 7. 결과 해석, 결정, 기록
- [x] **Step 1: 전체 실행.** `./gradlew experimentTest`를 실행하고 `build/experiment-results/*.csv`를 모은다. 실행 시간도 적는다.
- [x] **Step 2: 사용자와 해석**(7단계 형식, 예상과 측정을 구분)
  - **입장 경계 최종 기준**(F18): 결정하면 쓰지 않는 컬럼을 지울지, 언제 지울지도 정한다. 컬럼 삭제는 운영 코드·마이그레이션 변경이므로 별도 작업으로 승인받는다.
  - F22·F23·F19 해결책을 다룰 시점: 지금 할지, Step 5(정합성 강화)로 미룰지.
  - 5b에 넘길 DB별 차이.
- [x] **Step 3: `docs/adr/{진행한 날짜}.md`**: 세부 #1~#12 중 승인된 것, 입장 경계 결정. 번호는 ADR-088부터 매긴다. 코드 주석의 `계획 5a 세부 #n`을 ADR 번호로 바꾼다.
- [x] **Step 4: `docs/failure-lab.md`**: F2, F18, F19, F22, F23의 상태(재현됨 / 재현 안 됨)와 상태 요약 표를 고친다. 각 항목에 재현 방법, 관찰한 현상, 조건별 건수를 템플릿(가설 → 재현 방법 → 관찰 → 근본 원인 → 해결책 → 트레이드오프)대로 채운다. 실험 중 발견한 새 위험은 가설로 추가한다.
- [x] **Step 5: 보고서**(세부 #11 승인 시): `docs/reports/{날짜}-plan5a-consistency.md`에 조건별 표, 실행 시간, Testcontainers 환경 한계(CPU·메모리 제한 없음)를 적는다.
- [x] **Step 6: `docs/design/experiments.md`**(입장 경계 실험의 실제 방법과 판정 창), 설계 문서 8장 상태, `docs/README.md` 현재 상태(5a 완료, ADR 범위, 다음: 계획 5b), `CLAUDE.md` 현재 위치, `docs/journal/{날짜}.md`
- [x] **Step 7**: `./gradlew test`와 `./gradlew experimentTest`를 다시 실행해 확인한다. 결과를 보고하고 커밋 여부를 묻는다.
  - 실행 기록: 전체 `experimentTest` 1회 통과(21분 19초). 이후 테스트 코드의 CSV 표기와 분류를 보강해 해당 6개 실험 테스트 재실행 통과(39초). 일반 `test` 통과(42초). 사용자의 이번 요청에 따라 커밋 여부 질문 없이 결과 보고만 한다.

---

## 계획 5b 개요 (5a가 끝나면 자세히 쓴다)
| 범위 | 시작 전에 정할 것 |
|---|---|
| k6 스크립트(`load/`), W1~W5 × C1~C4, F1(폴링 폭주와 풀 고갈, 폴링 주기 변수), F15·F16(offset·인덱스, 스키마 A/B, ADR-014 판정 규칙), F20(정렬 컬럼 갱신 경합, PostgreSQL 인덱스 유무), F19 대량 삭제 부하, 워밍업 후 3회 중앙값, **DB 선택 ADR** | ① **MySQL과 PostgreSQL을 고르는 정량 판정 규칙.** ADR-014에 따라 측정 전에 확정한다 ② `room_members.user_id` 인덱스 차이를 맞출지, 조건 차이로 기록만 할지 ③ k6 버전과 실행 방식(Docker 또는 로컬 설치) ④ 앱 프로세스의 CPU 제한 여부(DB만 2 CPU로 제한되어 있음) ⑤ 5a에서 넘어온 DB별 경쟁 결과를 판정 규칙에 넣을지 |

## 확인 방법 (끝까지)
1. `./gradlew test`가 통과하고 실험 클래스가 실행되지 않는다.
2. `./gradlew experimentTest`가 통과한다. 결정적 재현의 assert는 모두 가설대로 나와야 하며, 다르면 해당 작업에서 이미 멈추고 보고했어야 한다.
3. `build/experiment-results/`에 smoke, f22, f2, f18, f23, f19 CSV가 조건 3개(F18은 6개)씩 있다.
4. `git diff --stat backend/src/main`이 비어 있다(운영 코드 무변경).
5. failure-lab의 다섯 항목에 측정 건수와 조건이 적혀 있고, 입장 경계 결정이 ADR에 있다.

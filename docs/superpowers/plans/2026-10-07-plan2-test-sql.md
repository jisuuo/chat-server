# 계획 2: 테스트용 SQL (`db/`) 구현 계획

> **실행하는 에이전트에게**: 작업은 하나씩 사용자 승인을 받고 시작한다. 작업이 끝나면 결과(테스트 출력 포함)를 보고하고 멈춘다. **커밋하지 않는다** (사용자가 요청할 때만). 단계는 체크박스(`- [ ]`)로 추적한다.

**목표:** 앱 없이도 실행할 수 있는 샘플(seed), 대량(bulk), 확인용(queries) SQL을 MySQL과 PostgreSQL용으로 만든다. 넣은 데이터가 앱 규칙을 지키는지 자동 테스트로 검증한다.

**구조:** SQL 파일은 저장소 루트의 `db/`에 둔다. 테스트는 Testcontainers 컨테이너 **안의** `mysql`/`psql` 클라이언트로 이 파일을 실행한다. README와 같은 실행 방식이라 README 명령도 함께 검증된다. 그다음 `check_invariants.sql`이 돌려주는 위반 건수가 모두 0인지 확인한다.

**기술:** MySQL 8.4.11, PostgreSQL 18.6, Testcontainers 2.x, Spring Boot 4.1.1 테스트(MockMvc), JUnit 5, AssertJ

## Context
- 계획 1(백엔드 핵심, 작업 0~9)은 끝났다. **DB 성능 측정은 아직 0건이다.** ADR-033(Step 1을 계획 5개로 나눔)에 따라 측정은 계획 5에서 하고, 그 전에 대량 데이터(계획 2)와 메트릭(계획 3)이 있어야 한다.
- 사용자 결정 (2026-10-07)
  - 순서: 2 SQL → 3 관측 → 4 프론트 → 5 실험 (ADR-033을 그대로 따름)
  - 계획 2 범위: SQL(seed/bulk/queries/README)과 정합성 검사. k6와 실험 코드는 계획 5에서 한다
  - bulk 메시지 수는 변수로 둔다. 작은 크기와 큰 크기로 각각 넣고, 크기를 재서 "메모리 안/밖"이 실제로 나뉘는지 확인한다
  - 인기 방 쏠림은 구간을 나눠 만든다 (방 1%에 메시지 50%, 9%에 30%, 90%에 20%)
  - 작업 2가 끝나면 결과 보고서를 `docs/reports/`에 남긴다
- 근거 문서: `docs/design/architecture.md:144-153`(`db/` 구조), `docs/design/experiments.md`(W1~W5, C1~C4, ADR-041 전환 절차), ADR-030(테스트와 실행 환경), ADR-031(사용자는 SQL로도 만든다)

## 지켜야 할 조건
- 스키마(`backend/src/main/resources/db/migration/*/V1__init.sql`)와 `src/main` 앱 코드는 **바꾸지 않는다**. 계획 2는 `db/`, 테스트, 문서만 다룬다.
- **장애 선행 (ADR-034)**: 예상되는 문제(인덱스를 타지 않음, 크기, 느린 적재 등)는 `docs/failure-lab.md`에 가설로만 적는다. 인덱스 추가나 설정 변경으로 미리 고치지 않는다.
- 두 DB에 같은 입력을 주면 **같은 데이터**가 나와야 한다. 난수 함수(`RAND()`, `random()`)는 쓰지 않는다.
- 측정 결과를 적을 때 "예상"과 "측정"을 구분한다.
- SQL 주석은 "왜"만 쓴다. 결정과 관련되면 ADR이나 F 번호를 적는다.
- DB 접속 정보: compose는 MySQL `localhost:13306`, PostgreSQL `localhost:15432`, 계정 `chat/chat`, DB `chat`. Testcontainers는 `container.getUsername()`/`getPassword()`/`getDatabaseName()`를 쓴다.

## 이 계획에서 새로 정하는 세부 (검토 필요, 승인되면 작업 6에서 다음 ADR 번호로 기록. 마지막 번호는 ADR-053)
| # | 항목 | 제안 | 이유 |
|---|---|---|---|
| 1 | seed의 id 지정 | id를 직접 넣지 않는다. 빈 DB를 전제로 이름(닉네임, 방 이름, 내용)으로 참조한다 | PostgreSQL `GENERATED ALWAYS`는 id를 직접 넣으면 거부한다 (2026-10-07 일지의 미결정 항목). `OVERRIDING SYSTEM VALUE`와 `setval`을 쓰면 DB별 SQL이 더 달라진다 |
| 2 | 스키마 만드는 방법 | compose에서는 앱을 한 번 띄워 Flyway로 만든다. 앱이 없는 환경에서는 `V1__init.sql`을 직접 실행한다 | 테이블을 직접 만든 뒤 앱을 띄우면 Flyway 이력이 없어 기동이 실패할 것으로 **예상**한다. 작업 2에서 확인한다 |
| 3 | 메시지 수 넘기기 | PostgreSQL은 `psql -v messages=N`, MySQL은 `--init-command="SET @messages=N"` | 파일을 고치지 않고 크기만 바꾼다 |
| 4 | 스키마 A/B | 공통 파일(`base.sql`)과 메시지 파일(`messages_a.sql`, `messages_b.sql`)로 나눈다. `explain`도 `_a`/`_b`로 나눈다 | MySQL에서 테이블 이름을 동적으로 바꾸려면 PREPARE가 필요하다. 한 DB에는 한 스키마의 데이터만 둔다 (ADR-041) |
| 5 | MySQL 대량 행 생성 | 재귀 CTE 대신 숫자 0~9 CTE를 자릿수만큼 cross join하고, 자릿수마다 범위를 잘라 낸다. 최대 9,999,999건 | 재귀 CTE는 `cte_max_recursion_depth`(기본 1000)를 넘는다 |
| 6 | bulk 형태 | 사용자 1만, 방 1만. 방 id 1~100은 인기(멤버 50), 101~1000은 중간(멤버 20), 1001~10000은 나머지(멤버 5). 멤버 j번(0부터)은 사용자 `((방id-1 + j*97) mod 10000) + 1`이고, 0번은 생성자다. 메시지 k의 구간은 `k mod 100`으로 정한다(0~49 인기, 50~79 중간, 80~99 나머지). 구간 안에서는 방을 차례대로 돌아가며 고른다. 보낸 사람은 그 방의 멤버 `k mod 멤버 수`번이다. 내용은 `RPAD('m{k} ', 20 + k mod 181, 'x')`(20~200자)다. `created_at`은 2026-09-08부터 30일 동안 k 순서대로 늘어난다 | 같은 식이면 두 DB의 결과가 같다. 비율이 정확히 50/30/20이 되고, 보낸 사람은 항상 멤버다(R3) |
| 7 | 메시지 수 후보 | 작은 크기 50만, 큰 크기 500만 (**예상값**) | 작업 4에서 크기를 잰 뒤 사용자와 함께 확정한다 |
| 8 | seed 파일 위치 | seed SQL은 두 DB에서 같은 문법이므로 `db/seed/`에 한 벌만 둔다 (`architecture.md`의 `seed/{mysql,postgresql}`를 고친다) | 타입 지정 리터럴(`TIMESTAMP '…'`)과 표준 SQL만 쓰면 두 DB에서 그대로 실행된다. 같은 파일을 두 벌 두면 서로 어긋날 수 있다 |

## 정합성 규칙 (`check_invariants.sql`이 규칙마다 `규칙\t위반 건수` 한 행을 돌려준다)
- **I1**: `rooms.last_message_id` = 그 방 메시지의 최대 id. 메시지가 없으면 NULL이다 (ADR-016)
- **I2**: 모든 메시지의 `sender_id`는 그 방의 멤버다 (R3)
- **I3**: `joined_message_id` ≤ 방의 `last_message_id`(없으면 0)이고, `joined_at` ≥ 방의 `created_at`이다
- **I4**: 같은 방 안에서 id가 커지면 `created_at`도 작아지지 않는다
- **I5**: 사용자 수, 방 수, 멤버 수가 0보다 크다 (빈 실행을 잡는다)
- **I6**: 닉네임과 방 이름은 1~50자이고 제어 문자가 없다. 내용은 1~1000자이고 NUL이 없다 (ADR-048, ADR-050)
- **I7**: 재입장 멤버(`joined_message_id > 0`)는 id 기준과 시각 기준의 경계 판정이 같다 (`chat.join-boundary=id|time`의 결과가 같아야 한다)
- 구간 분포(50/30/20)는 bulk에만 해당하므로 `check_distribution.sql`로 따로 확인한다

두 메시지 테이블은 `UNION ALL`로 합쳐 검사한다. ADR-041(스키마 A/B 전환 시 DB 초기화)에 따라 한쪽은 항상 비어 있다.

## 파일 구조
```
db/
 ├─ README.md
 ├─ seed/                     base.sql, messages_a.sql, messages_b.sql   (두 DB 공용, 세부 #8)
 ├─ bulk/{mysql,postgresql}/  base.sql, messages_a.sql, messages_b.sql
 └─ queries/{mysql,postgresql}/
       check_invariants.sql, check_distribution.sql,
       explain_a.sql, explain_b.sql, sizes.sql, cache_hit.sql
backend/src/test/java/jissuo/chat/
 ├─ support/MySqlContainerSupport.java, PostgresContainerSupport.java   (수정: container() 접근자)
 └─ sql/ SqlCli.java, MySqlCli.java, PostgresCli.java, DbScriptsContract.java,
         MySqlADbScriptsTest.java, MySqlBDbScriptsTest.java, PostgresADbScriptsTest.java, PostgresBDbScriptsTest.java
docs/reports/2026-10-07-plan2-task2-seed.md   (작업 2 결과 보고서)
```

---

## 작업 0. 계획 저장 (이 문서)
- [x] 이 문서를 `docs/superpowers/plans/2026-10-07-plan2-test-sql.md`에 저장한다

---

## 작업 1. 검사 쿼리 + SQL 실행 도구 + 테스트 틀

**Files:**
- Modify: `backend/src/test/java/jissuo/chat/support/MySqlContainerSupport.java`, `PostgresContainerSupport.java`
- Create: `backend/src/test/java/jissuo/chat/sql/{SqlCli,MySqlCli,PostgresCli,DbScriptsContract}.java`와 4조합 하위 클래스
- Create: `db/queries/{mysql,postgresql}/check_invariants.sql`

**Interfaces:**
- Produces: `SqlCli.runFile(String repoPath, Map<String,String> variables) → List<List<String>>`, `SqlCli.runSql(String sql) → List<List<String>>`. `repoPath`는 저장소 루트 기준 경로다(예: `db/seed/base.sql`). 반환값은 결과 행마다 칸 목록이다. 클라이언트가 0이 아닌 종료 코드를 돌려주면 `IllegalStateException(stderr)`를 던진다.
- Produces: `DbScriptsContract`의 추상 메서드 `cli()`, `dbDir()`(`"mysql"`/`"postgresql"`), `schema()`(`"a"`/`"b"`), `resetSql()`

- [x] **Step 1: 컨테이너 접근자 추가**

`MySqlContainerSupport`에 추가한다 (`PostgresContainerSupport`도 같은 모양으로 `PostgreSQLContainer`를 돌려준다):
```java
    /** db/ 폴더의 SQL을 컨테이너 안의 클라이언트로 실행할 때 쓴다 (계획 2) */
    public static MySQLContainer container() {
        return CONTAINER;
    }
```

- [x] **Step 2: `SqlCli`와 구현 두 개**

```java
package jissuo.chat.sql;

import java.util.List;
import java.util.Map;

/**
 * db/ 폴더의 SQL을 README와 같은 방식(컨테이너 안의 DB 클라이언트)으로 실행한다.
 * JDBC로 실행하면 클라이언트 전용 문법(psql 변수, --init-command)을 검증하지 못하기 때문이다.
 */
interface SqlCli {

    List<List<String>> runFile(String repoPath, Map<String, String> variables);

    List<List<String>> runSql(String sql);
}
```

```java
package jissuo.chat.sql;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;

final class MySqlCli implements SqlCli {

    private final MySQLContainer container;

    MySqlCli(MySQLContainer container) {
        this.container = container;
    }

    @Override
    public List<List<String>> runFile(String repoPath, Map<String, String> variables) {
        String target = "/tmp/" + repoPath.replace('/', '_');
        // Gradle 테스트의 작업 디렉터리는 backend/ 이므로 저장소 루트는 한 단계 위다
        container.copyFileToContainer(MountableFile.forHostPath(Path.of("..", repoPath)), target);
        String init = variables.entrySet().stream()
                .map(e -> "SET @" + e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(";"));
        return exec(client() + (init.isEmpty() ? "" : " --init-command='" + init + "'") + " < " + target);
    }

    @Override
    public List<List<String>> runSql(String sql) {
        return exec(client() + " -e \"" + sql + "\"");
    }

    private String client() {
        return "mysql --default-character-set=utf8mb4 -N -B -u" + container.getUsername()
                + " -p" + container.getPassword() + " " + container.getDatabaseName();
    }

    private List<List<String>> exec(String command) {
        try {
            var result = container.execInContainer("sh", "-c", command);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException(result.getStderr());
            }
            return result.getStdout().lines()
                    .filter(line -> !line.isBlank())
                    .map(line -> Arrays.asList(line.split("\t", -1)))
                    .toList();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

```java
package jissuo.chat.sql;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

final class PostgresCli implements SqlCli {

    private final PostgreSQLContainer container;

    PostgresCli(PostgreSQLContainer container) {
        this.container = container;
    }

    @Override
    public List<List<String>> runFile(String repoPath, Map<String, String> variables) {
        String target = "/tmp/" + repoPath.replace('/', '_');
        container.copyFileToContainer(MountableFile.forHostPath(Path.of("..", repoPath)), target);
        String vars = variables.entrySet().stream()
                .map(e -> " -v " + e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining());
        return exec(client() + vars + " -f " + target);
    }

    @Override
    public List<List<String>> runSql(String sql) {
        return exec(client() + " -c \"" + sql + "\"");
    }

    private String client() {
        // ON_ERROR_STOP: 오류가 나도 다음 문장을 계속 실행하고 종료 코드 0을 주는 기본 동작을 막는다
        return "psql -X -q -A -t -F '|' -v ON_ERROR_STOP=1 -U " + container.getUsername()
                + " -d " + container.getDatabaseName();
    }

    private List<List<String>> exec(String command) {
        try {
            var result = container.execInContainer("sh", "-c", command);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException(result.getStderr());
            }
            return result.getStdout().lines()
                    .filter(line -> !line.isBlank())
                    .map(line -> Arrays.asList(line.split("\\|", -1)))
                    .toList();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [x] **Step 3: `check_invariants.sql` (MySQL)** — `db/queries/mysql/check_invariants.sql`

```sql
-- 넣은 데이터가 앱 규칙을 지키는지 규칙별 위반 건수를 돌려준다. 모두 0이어야 한다.
-- 한 DB에는 messages / messages_b 중 한쪽만 채우므로(ADR-041) 둘을 합쳐 검사한다.
WITH all_messages AS (
    SELECT id, room_id, sender_id, content, created_at FROM messages
    UNION ALL
    SELECT id, room_id, sender_id, content, created_at FROM messages_b
)
SELECT 'I1', COUNT(*) FROM rooms r
    LEFT JOIN (SELECT room_id, MAX(id) AS max_id FROM all_messages GROUP BY room_id) m ON m.room_id = r.id
    WHERE (r.last_message_id IS NULL AND m.max_id IS NOT NULL)
       OR (r.last_message_id IS NOT NULL AND (m.max_id IS NULL OR r.last_message_id <> m.max_id))
UNION ALL
SELECT 'I2', COUNT(*) FROM all_messages m
    LEFT JOIN room_members rm ON rm.room_id = m.room_id AND rm.user_id = m.sender_id
    WHERE rm.user_id IS NULL
UNION ALL
SELECT 'I3', COUNT(*) FROM room_members rm JOIN rooms r ON r.id = rm.room_id
    WHERE rm.joined_message_id > COALESCE(r.last_message_id, 0) OR rm.joined_at < r.created_at
UNION ALL
SELECT 'I4', COUNT(*) FROM (
        SELECT created_at, LAG(created_at) OVER (PARTITION BY room_id ORDER BY id) AS prev FROM all_messages
    ) t WHERE t.prev > t.created_at
UNION ALL
SELECT 'I5', (SELECT COUNT(*) = 0 FROM users) + (SELECT COUNT(*) = 0 FROM rooms) + (SELECT COUNT(*) = 0 FROM room_members)
UNION ALL
SELECT 'I6', (SELECT COUNT(*) FROM users WHERE CHAR_LENGTH(nickname) NOT BETWEEN 1 AND 50 OR nickname REGEXP '[[:cntrl:]]')
           + (SELECT COUNT(*) FROM rooms WHERE CHAR_LENGTH(name) NOT BETWEEN 1 AND 50 OR name REGEXP '[[:cntrl:]]')
           + (SELECT COUNT(*) FROM all_messages WHERE CHAR_LENGTH(content) NOT BETWEEN 1 AND 1000 OR INSTR(content, CHAR(0)) > 0)
UNION ALL
-- 재입장 멤버만 본다. bulk는 모두 경계 0이라 이 조인이 비어 대량 데이터에서도 빠르다
SELECT 'I7', COUNT(*) FROM room_members rm JOIN all_messages m ON m.room_id = rm.room_id
    WHERE rm.joined_message_id > 0
      AND (m.id > rm.joined_message_id) <> (m.created_at > rm.joined_at);
```

- [x] **Step 4: `check_invariants.sql` (PostgreSQL)** — `db/queries/postgresql/check_invariants.sql`

MySQL 판과 다른 점은 세 가지다. `COUNT(*) = 0`을 정수로 바꾸는 방법(`::int`), 정규식 연산자(`~`), NUL 검사 생략이다.
```sql
-- 넣은 데이터가 앱 규칙을 지키는지 규칙별 위반 건수를 돌려준다. 모두 0이어야 한다.
-- 한 DB에는 messages / messages_b 중 한쪽만 채우므로(ADR-041) 둘을 합쳐 검사한다.
WITH all_messages AS (
    SELECT id, room_id, sender_id, content, created_at FROM messages
    UNION ALL
    SELECT id, room_id, sender_id, content, created_at FROM messages_b
)
SELECT 'I1', COUNT(*) FROM rooms r
    LEFT JOIN (SELECT room_id, MAX(id) AS max_id FROM all_messages GROUP BY room_id) m ON m.room_id = r.id
    WHERE (r.last_message_id IS NULL AND m.max_id IS NOT NULL)
       OR (r.last_message_id IS NOT NULL AND (m.max_id IS NULL OR r.last_message_id <> m.max_id))
UNION ALL
SELECT 'I2', COUNT(*) FROM all_messages m
    LEFT JOIN room_members rm ON rm.room_id = m.room_id AND rm.user_id = m.sender_id
    WHERE rm.user_id IS NULL
UNION ALL
SELECT 'I3', COUNT(*) FROM room_members rm JOIN rooms r ON r.id = rm.room_id
    WHERE rm.joined_message_id > COALESCE(r.last_message_id, 0) OR rm.joined_at < r.created_at
UNION ALL
SELECT 'I4', COUNT(*) FROM (
        SELECT created_at, LAG(created_at) OVER (PARTITION BY room_id ORDER BY id) AS prev FROM all_messages
    ) t WHERE t.prev > t.created_at
UNION ALL
SELECT 'I5', (SELECT COUNT(*) = 0 FROM users)::int + (SELECT COUNT(*) = 0 FROM rooms)::int
           + (SELECT COUNT(*) = 0 FROM room_members)::int
UNION ALL
-- PostgreSQL은 NUL을 저장할 수 없어(F25) 내용의 NUL 검사는 하지 않는다
SELECT 'I6', (SELECT COUNT(*) FROM users WHERE char_length(nickname) NOT BETWEEN 1 AND 50 OR nickname ~ '[[:cntrl:]]')
           + (SELECT COUNT(*) FROM rooms WHERE char_length(name) NOT BETWEEN 1 AND 50 OR name ~ '[[:cntrl:]]')
           + (SELECT COUNT(*) FROM all_messages WHERE char_length(content) NOT BETWEEN 1 AND 1000)
UNION ALL
-- 재입장 멤버만 본다. bulk는 모두 경계 0이라 이 조인이 비어 대량 데이터에서도 빠르다
SELECT 'I7', COUNT(*) FROM room_members rm JOIN all_messages m ON m.room_id = rm.room_id
    WHERE rm.joined_message_id > 0
      AND (m.id > rm.joined_message_id) <> (m.created_at > rm.joined_at);
```

- [x] **Step 5: `DbScriptsContract`와 4조합 하위 클래스**

```java
package jissuo.chat.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * db/ 폴더의 SQL이 두 DB × 스키마 A/B에서 실행되고, 넣은 데이터가 앱 규칙(I1~I7)을 지키는지 확인한다.
 */
abstract class DbScriptsContract {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    abstract SqlCli cli();

    abstract String dbDir();

    abstract String schema();

    /** id 번호를 1부터 다시 시작하게 비운다. README의 "빈 DB"(ADR-041의 down -v)와 같은 상태를 만든다 */
    abstract String resetSql();

    String messageTable() {
        return schema().equals("a") ? "messages" : "messages_b";
    }

    @BeforeEach
    void reset() {
        cli().runSql(resetSql());
    }

    @Test
    void seed는_정합성_규칙을_지킨다() {
        loadSeed();

        assertNoViolations();
        assertThat(count("users")).isEqualTo(3);
        assertThat(count("rooms")).isEqualTo(3);
        assertThat(count("room_members")).isEqualTo(6);
        assertThat(count(messageTable())).isEqualTo(7);
    }

    void loadSeed() {
        cli().runFile("db/seed/base.sql", Map.of());
        cli().runFile("db/seed/messages_" + schema() + ".sql", Map.of());
    }

    void assertNoViolations() {
        List<List<String>> rows = cli().runFile("db/queries/" + dbDir() + "/check_invariants.sql", Map.of());
        assertThat(rows).extracting(row -> row.get(0)).containsExactly("I1", "I2", "I3", "I4", "I5", "I6", "I7");
        assertThat(rows).allSatisfy(row -> assertThat(row.get(1)).as(row.get(0)).isEqualTo("0"));
    }

    long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    long userId(String nickname) {
        return jdbc.sql("SELECT id FROM users WHERE nickname = :n").param("n", nickname).query(Long.class).single();
    }

    long roomId(String name) {
        return jdbc.sql("SELECT id FROM rooms WHERE name = :n").param("n", name).query(Long.class).single();
    }
}
```

하위 클래스 4개는 기존 `MySqlBMessageRepositoryTest`와 같은 모양이다. 아래는 `MySqlADbScriptsTest`다.
```java
package jissuo.chat.sql;

import jissuo.chat.support.MySqlContainerSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = "chat.message-schema=A")
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
class MySqlADbScriptsTest extends DbScriptsContract {

    private static final SqlCli CLI = new MySqlCli(MySqlContainerSupport.container());

    @Override SqlCli cli() { return CLI; }
    @Override String dbDir() { return "mysql"; }
    @Override String schema() { return "a"; }

    // MySQL은 FK가 가리키는 테이블을 TRUNCATE할 수 없어 같은 세션에서 FK 검사를 잠시 끈다
    @Override String resetSql() {
        return "SET FOREIGN_KEY_CHECKS=0; TRUNCATE TABLE messages; TRUNCATE TABLE messages_b; "
                + "TRUNCATE TABLE room_members; TRUNCATE TABLE rooms; TRUNCATE TABLE users; SET FOREIGN_KEY_CHECKS=1;";
    }

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }
}
```
- `MySqlBDbScriptsTest`: 위와 같고 `chat.message-schema=B`, `schema()`는 `"b"`이다.
- `PostgresADbScriptsTest`와 `PostgresBDbScriptsTest`는 다음이 다르다.
  - `@ActiveProfiles("postgres")`, `PostgresContainerSupport.register`, `new PostgresCli(PostgresContainerSupport.container())`
  - `dbDir()`는 `"postgresql"`
  - `resetSql()`는 `"TRUNCATE messages, messages_b, room_members, rooms, users RESTART IDENTITY;"`. FK로 묶인 테이블을 모두 나열하므로 CASCADE는 필요 없다.

- [x] **Step 6: 실패 확인**

Run: `cd backend && ./gradlew test --tests 'jissuo.chat.sql.*'`
Expected: 4개 클래스의 `seed는_정합성_규칙을_지킨다`가 실패한다. `db/seed/base.sql` 파일이 없어서 `copyFileToContainer`가 예외를 던진다. 검사 쿼리의 문법 오류로 실패하면 안 된다. 의심되면 `cli().runFile(".../check_invariants.sql")`만 부르는 임시 테스트로 빈 DB에서 I5=1, 나머지 0이 나오는지 보고 지운다.

- [x] **Step 7: 보고하고 멈춘다**

---

## 작업 2. seed (두 DB 공용) + 수동 확인 + 결과 보고서

**Files:**
- Create: `db/seed/base.sql`, `db/seed/messages_a.sql`, `db/seed/messages_b.sql`
- Modify: `backend/src/test/java/jissuo/chat/sql/DbScriptsContract.java` (API 테스트 2개 추가)
- Create: `docs/reports/2026-10-07-plan2-task2-seed.md`

**seed 내용:**
- 사용자: 철수, 영희, 민수
- 방: 잡담방(철수가 만듦, 메시지 5건), 스터디(영희가 만듦, 메시지 2건, 가장 최근), 빈 방(민수가 만듦, 메시지 없음)
- 멤버
  - 잡담방: 철수, 영희, 민수. 민수는 "잡담 3"을 보낸 뒤 나갔다가 다시 들어왔다고 본다
  - 스터디: 영희, 철수
  - 빈 방: 민수
- 기대 결과
  - 방 목록 순서: 스터디 → 잡담방 → 빈 방
  - 민수가 잡담방을 조회하면: 잡담 4, 잡담 5

- [ ] **Step 1: 실패하는 API 테스트 추가** (`DbScriptsContract`에 추가)

```java
    @Test
    void seed의_재입장한_민수는_재입장_이후_메시지만_본다() throws Exception {
        loadSeed();

        mvc.perform(get("/api/rooms/{roomId}/messages", roomId("잡담방")).header("X-User-Id", userId("민수")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages[*].content").value(contains("잡담 4", "잡담 5")))
                .andExpect(jsonPath("$.data.hasMore").value(false));
    }

    @Test
    void seed의_방_목록은_최근_대화순이고_빈_방은_마지막이다() throws Exception {
        loadSeed();

        mvc.perform(get("/api/rooms").header("X-User-Id", userId("철수")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rooms[*].name").value(contains("스터디", "잡담방", "빈 방")));
    }
```

Run: `./gradlew test --tests 'jissuo.chat.sql.*'` → Expected: seed 파일이 없어 실패한다

- [ ] **Step 2: `db/seed/base.sql`**

```sql
-- 화면 확인용 소량 데이터. 두 DB 공용이다(세부 #8). 빈 DB에서 실행한다.
-- id는 지정하지 않고 이름으로 참조한다: PostgreSQL GENERATED ALWAYS는 id를 직접 넣으면 거부한다 (세부 #1)
-- 시각은 타입 지정 리터럴로 쓴다: PostgreSQL에서 UNION 안의 따옴표 문자열은 text가 되어 timestamp 컬럼에 넣지 못한다
INSERT INTO users (nickname, created_at) VALUES
    ('철수', TIMESTAMP '2026-10-01 09:00:00'),
    ('영희', TIMESTAMP '2026-10-01 09:00:00'),
    ('민수', TIMESTAMP '2026-10-01 09:00:00');

INSERT INTO rooms (name, created_by, created_at)
SELECT '잡담방', id, TIMESTAMP '2026-10-01 09:10:00' FROM users WHERE nickname = '철수'
UNION ALL
SELECT '스터디', id, TIMESTAMP '2026-10-01 09:20:00' FROM users WHERE nickname = '영희'
UNION ALL
SELECT '빈 방', id, TIMESTAMP '2026-10-01 09:30:00' FROM users WHERE nickname = '민수';

-- 처음 입장한 멤버의 경계는 0, 입장 시각은 방 생성 시각이다 (생성자와 같은 규칙, R1)
INSERT INTO room_members (room_id, user_id, joined_message_id, joined_at)
SELECT r.id, u.id, 0, r.created_at
FROM rooms r JOIN users u
  ON (r.name = '잡담방' AND u.nickname IN ('철수', '영희', '민수'))
  OR (r.name = '스터디' AND u.nickname IN ('영희', '철수'))
  OR (r.name = '빈 방' AND u.nickname = '민수');
```

- [ ] **Step 3: `db/seed/messages_a.sql`**

```sql
-- 스키마 A(messages)용. base.sql 다음에 실행한다. 스키마 B면 messages_b.sql을 대신 실행한다 (ADR-041)
INSERT INTO messages (room_id, sender_id, content, created_at)
SELECT r.id, u.id, v.content, v.created_at
FROM (
    SELECT '잡담방' AS room, '철수' AS sender, '잡담 1' AS content, TIMESTAMP '2026-10-01 10:00:01' AS created_at
    UNION ALL SELECT '잡담방', '영희', '잡담 2', TIMESTAMP '2026-10-01 10:00:02'
    UNION ALL SELECT '잡담방', '민수', '잡담 3', TIMESTAMP '2026-10-01 10:00:03'
    UNION ALL SELECT '잡담방', '철수', '잡담 4', TIMESTAMP '2026-10-01 10:00:04'
    UNION ALL SELECT '잡담방', '영희', '잡담 5', TIMESTAMP '2026-10-01 10:00:05'
    UNION ALL SELECT '스터디', '영희', '스터디 1', TIMESTAMP '2026-10-01 11:00:01'
    UNION ALL SELECT '스터디', '철수', '스터디 2', TIMESTAMP '2026-10-01 11:00:02'
) v
JOIN rooms r ON r.name = v.room
JOIN users u ON u.nickname = v.sender
ORDER BY v.created_at;

-- 민수는 '잡담 3'을 보낸 뒤 나갔다가 다시 들어왔다고 본다. 재입장 경계(ADR-008)는 그 시점의 마지막 메시지다.
-- id 기준과 시각 기준이 같은 결과를 내도록 joined_at도 같은 메시지의 시각으로 맞춘다 (I7)
UPDATE room_members
SET joined_message_id = (SELECT id FROM messages WHERE content = '잡담 3'),
    joined_at = (SELECT created_at FROM messages WHERE content = '잡담 3')
WHERE room_id = (SELECT id FROM rooms WHERE name = '잡담방')
  AND user_id = (SELECT id FROM users WHERE nickname = '민수');

-- 방 목록 정렬용 비정규화 컬럼(ADR-016)을 메시지와 맞춘다 (I1)
UPDATE rooms SET last_message_id = (SELECT MAX(m.id) FROM messages m WHERE m.room_id = rooms.id);
```

- [ ] **Step 4: `db/seed/messages_b.sql`**: `messages_a.sql`을 복사하고 테이블 이름 `messages` 4곳(INSERT 대상, 두 서브쿼리, 마지막 UPDATE)을 `messages_b`로 바꾼다. 첫 주석은 `-- 스키마 B(messages_b)용. base.sql 다음에 실행한다. 스키마 A면 messages_a.sql을 대신 실행한다 (ADR-041)`이다.

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew test --tests 'jissuo.chat.sql.*'` → Expected: 4조합 × 3개 테스트가 모두 PASS한다.
그다음 `./gradlew test` 전체를 돌려 기존 테스트가 깨지지 않았는지 확인한다. TRUNCATE로 id가 다시 1부터 시작해도 다른 테스트는 id 값을 가정하지 않는다.

- [ ] **Step 6: 세부 #2 확인 (스키마를 직접 만든 뒤 앱을 띄우면 실패하는가)** — 저장소 루트에서 실행한다

```bash
docker compose -f infra/compose.db.yml down -v && docker compose -f infra/compose.db.yml up -d --wait
docker compose -f infra/compose.db.yml exec -T mysql mysql -uchat -pchat chat < backend/src/main/resources/db/migration/mysql/V1__init.sql
(cd backend && ./gradlew bootRun --args='--spring.profiles.active=local,mysql')
```
Expected(예상): Flyway가 "비어 있지 않은 스키마에 이력 테이블이 없다"는 오류를 내고 기동에 실패한다. **실제 메시지를 보고서에 그대로 옮긴다.** PostgreSQL도 같은 방식으로 확인한다(`exec -T postgres psql -U chat -d chat -v ON_ERROR_STOP=1 < …/postgresql/V1__init.sql`, 프로필 `local,postgres`).

- [ ] **Step 7: compose에서 수동 확인 (조합마다 반복)**

조합은 6가지다: MySQL-A, MySQL-B, PostgreSQL-A, PostgreSQL-B, MySQL-A + `--chat.join-boundary=time`, PostgreSQL-A + `--chat.join-boundary=time`. 조합마다 아래를 한다.
```bash
docker compose -f infra/compose.db.yml down -v && docker compose -f infra/compose.db.yml up -d --wait
# 터미널 1: 앱 기동 (Flyway가 스키마를 만든다)
(cd backend && ./gradlew bootRun --args='--spring.profiles.active=local,mysql --chat.message-schema=A')
# 터미널 2: seed 넣기 (PostgreSQL은 exec -T postgres psql -U chat -d chat -v ON_ERROR_STOP=1 < …)
docker compose -f infra/compose.db.yml exec -T mysql mysql --default-character-set=utf8mb4 -uchat -pchat chat < db/seed/base.sql
docker compose -f infra/compose.db.yml exec -T mysql mysql --default-character-set=utf8mb4 -uchat -pchat chat < db/seed/messages_a.sql
docker compose -f infra/compose.db.yml exec -T mysql mysql -uchat -pchat -t chat < db/queries/mysql/check_invariants.sql
```
빈 DB이므로 사용자 id는 철수 1, 영희 2, 민수 3, 방 id는 잡담방 1, 스터디 2, 빈 방 3이다(**예상**). 첫 curl 전에 `SELECT id, nickname FROM users`로 확인한다.

| # | 요청 | 기대 |
|---|---|---|
| 1 | `curl -s localhost:8080/api/rooms -H 'X-User-Id: 1'` | 이름 순서가 스터디, 잡담방, 빈 방 |
| 2 | `curl -s localhost:8080/api/rooms/1/messages -H 'X-User-Id: 1'` | 잡담 1~5, `hasMore:false` |
| 3 | `curl -s localhost:8080/api/rooms/1/messages -H 'X-User-Id: 3'` | 잡담 4, 5만 |
| 4 | `curl -s 'localhost:8080/api/rooms/1/messages?after={잡담 5 id}' -H 'X-User-Id: 3'` | 빈 목록 |
| 5 | `curl -s -X POST localhost:8080/api/rooms/1/messages -H 'X-User-Id: 1' -H 'Content-Type: application/json' -d '{"content":"새 메시지"}'` | 201 |
| 6 | 4번을 다시 실행 | "새 메시지" 1건 (폴링) |
| 7 | 1번을 다시 실행 | 잡담방이 맨 위 |
| 8 | `curl -s localhost:8080/api/rooms/2/messages -H 'X-User-Id: 3'` | 403 `NOT_A_MEMBER` |

- [ ] **Step 8: 결과 보고서** — `docs/reports/2026-10-07-plan2-task2-seed.md`

아래 틀을 채운다. 측정하지 않은 칸은 "미실행"으로 적는다. **결과를 지어내지 않는다.**
```markdown
# 계획 2 작업 2 결과 보고서: seed

> 날짜: 2026-10-07 · 대상 커밋: {git rev-parse --short HEAD, 미커밋 변경 있음 여부}

## 한 줄 결론
{seed가 두 DB × 스키마 A/B에서 규칙을 지키고 API에서 기대대로 보이는지 한 문장}

## 환경
| 항목 | 값 |
|---|---|
| DB 이미지 | mysql:8.4.11, postgres:18.6 |
| compose 자원 | cpus 2, mem 1g, 버퍼 256M (ADR-040) |
| 테스트 | Testcontainers (같은 이미지) |
| 앱 실행 | `./gradlew bootRun`, 프로필 {…} |

## 자동 테스트
| 조합 | seed 정합성 | 재입장 경계 | 방 목록 순서 |
|---|---|---|---|
| MySQL-A / MySQL-B / PostgreSQL-A / PostgreSQL-B | PASS/FAIL | … | … |
`./gradlew test` 전체 결과: {통과/실패 건수}

## 세부 #1, #2 확인 (예상 → 실제)
| 세부 | 예상 | 실제 (측정) |
|---|---|---|
| #1 id를 지정하지 않는 seed | 두 DB에서 같은 SQL로 실행된다 | … |
| #2 V1을 직접 실행한 뒤 앱 기동 | Flyway 오류로 기동 실패 | {실제 오류 메시지 그대로} |

## 수동 확인 (compose + curl)
| 조합 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | check_invariants |
|---|---|---|---|---|---|---|---|---|---|
(요청 번호는 작업 2 Step 7의 표를 따른다. 기대와 다르면 칸에 실제 응답을 짧게 적는다)

## 두 DB의 차이와 이상한 점
- {관찰한 것. 새 위험은 failure-lab에 가설로 추가하고 F 번호만 적는다 (ADR-034)}

## 다음 작업에 미치는 영향
- {bulk(작업 3)에 그대로 쓸 수 있는 것, 바꿔야 할 것}
```

- [ ] **Step 9: 보고하고 멈춘다** (보고서 경로와 요약을 포함한다)

---

## 작업 3. bulk (두 DB × A/B)

**Files:**
- Create: `db/bulk/{mysql,postgresql}/{base,messages_a,messages_b}.sql`, `db/queries/{mysql,postgresql}/check_distribution.sql`
- Modify: `DbScriptsContract.java` (bulk 테스트)

- [ ] **Step 1: 실패하는 테스트 추가** (`DbScriptsContract`)

```java
    @Test
    void bulk는_정합성_규칙과_구간별_분포를_지킨다() {
        cli().runFile("db/bulk/" + dbDir() + "/base.sql", Map.of());
        cli().runFile("db/bulk/" + dbDir() + "/messages_" + schema() + ".sql", Map.of("messages", "1000"));

        assertNoViolations();
        assertThat(count("users")).isEqualTo(10_000);
        assertThat(count("rooms")).isEqualTo(10_000);
        assertThat(count("room_members")).isEqualTo(100 * 50 + 900 * 20 + 9_000 * 5);
        assertThat(count(messageTable())).isEqualTo(1_000);
        List<List<String>> distribution =
                cli().runFile("db/queries/" + dbDir() + "/check_distribution.sql", Map.of());
        assertThat(distribution).extracting(row -> row.get(0) + "=" + row.get(2))
                .containsExactly("cold=20.00", "hot=50.00", "warm=30.00");
    }
```
Run → Expected: 파일이 없어 FAIL

- [ ] **Step 2: `db/bulk/mysql/base.sql`**

```sql
-- 부하 측정용 공통 데이터: 사용자 1만, 방 1만, 멤버 6만8천 (세부 #6). 빈 DB에서 실행한다 (ADR-041).
-- 방 구간은 id로 정한다: 1~100 인기(멤버 50), 101~1000 중간(멤버 20), 1001~10000 나머지(멤버 5).
-- 재귀 CTE는 cte_max_recursion_depth(기본 1000)에 걸리므로 숫자 0~9를 cross join해 만든다 (세부 #5)
INSERT INTO users (nickname, created_at)
WITH digits AS (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
                UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9),
     seq AS (SELECT d1.d + d2.d * 10 + d3.d * 100 + d4.d * 1000 + 1 AS n
             FROM digits d1 CROSS JOIN digits d2 CROSS JOIN digits d3 CROSS JOIN digits d4)
SELECT CONCAT('user', n), TIMESTAMP '2026-09-01 00:00:00' FROM seq ORDER BY n;

INSERT INTO rooms (name, created_by, created_at)
WITH digits AS (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
                UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9),
     seq AS (SELECT d1.d + d2.d * 10 + d3.d * 100 + d4.d * 1000 + 1 AS n
             FROM digits d1 CROSS JOIN digits d2 CROSS JOIN digits d3 CROSS JOIN digits d4)
SELECT CONCAT('room', n), n, TIMESTAMP '2026-09-01 00:00:00' FROM seq ORDER BY n;

-- 멤버 j번은 사용자 ((방id-1 + j*97) mod 10000) + 1. j=0이 생성자이고, j<50이면 97*j < 10000이라 겹치지 않는다
INSERT INTO room_members (room_id, user_id, joined_message_id, joined_at)
WITH digits AS (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
                UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9),
     slots AS (SELECT d1.d + d2.d * 10 AS j FROM digits d1 CROSS JOIN digits d2 WHERE d1.d + d2.d * 10 < 50)
SELECT r.id, MOD(r.id - 1 + s.j * 97, 10000) + 1, 0, r.created_at
FROM rooms r JOIN slots s
  ON s.j < CASE WHEN r.id <= 100 THEN 50 WHEN r.id <= 1000 THEN 20 ELSE 5 END;
```

- [ ] **Step 3: `db/bulk/mysql/messages_a.sql`**

```sql
-- 스키마 A(messages)에 메시지 @messages건을 넣는다. 실행: mysql --init-command="SET @messages=500000" … < messages_a.sql
-- base.sql 다음에 실행한다. 최대 9,999,999건 (7자리)
-- 메시지 k의 구간은 k mod 100으로 정해 비율이 정확히 50/30/20이 되게 하고, 구간 안에서는 방을 차례로 돌아가며 고른다 (세부 #6)
-- 자릿수마다 "d * 10^i < @messages" 조건을 걸어, 작은 @messages에서 1천만 조합을 만들지 않게 한다
INSERT INTO messages (room_id, sender_id, content, created_at)
WITH digits AS (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
                UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9),
     seq AS (SELECT d1.d + d2.d * 10 + d3.d * 100 + d4.d * 1000 + d5.d * 10000 + d6.d * 100000 + d7.d * 1000000 + 1 AS k
             FROM digits d1 CROSS JOIN digits d2 CROSS JOIN digits d3 CROSS JOIN digits d4
                  CROSS JOIN digits d5 CROSS JOIN digits d6 CROSS JOIN digits d7
             WHERE d2.d * 10 < @messages AND d3.d * 100 < @messages AND d4.d * 1000 < @messages
               AND d5.d * 10000 < @messages AND d6.d * 100000 < @messages AND d7.d * 1000000 < @messages),
     placed AS (SELECT k, MOD(k, 100) AS s, k DIV 100 AS q FROM seq WHERE k <= @messages),
     routed AS (SELECT k,
                       CASE WHEN s < 50 THEN 1 + MOD(q * 50 + s, 100)
                            WHEN s < 80 THEN 101 + MOD(q * 30 + s - 50, 900)
                            ELSE 1001 + MOD(q * 20 + s - 80, 9000) END AS room_id,
                       CASE WHEN s < 50 THEN 50 WHEN s < 80 THEN 20 ELSE 5 END AS members
                FROM placed)
SELECT room_id,
       MOD(room_id - 1 + MOD(k, members) * 97, 10000) + 1,
       RPAD(CONCAT('m', k, ' '), 20 + MOD(k, 181), 'x'),
       -- 30일(2,592,000,000,000마이크로초)을 메시지 수로 나눠 k 순서대로 늘린다 (I4)
       TIMESTAMPADD(MICROSECOND, k * (2592000000000 DIV @messages), TIMESTAMP '2026-09-08 00:00:00')
FROM routed
ORDER BY k;

UPDATE rooms SET last_message_id = (SELECT MAX(m.id) FROM messages m WHERE m.room_id = rooms.id);

-- 실행 계획(queries/explain_a.sql)이 방금 넣은 데이터 기준으로 나오게 통계를 갱신한다
ANALYZE TABLE rooms, room_members, messages;
```

- [ ] **Step 4: `db/bulk/mysql/messages_b.sql`**: `messages_a.sql`과 같다. 첫 주석의 "스키마 A(messages)"를 "스키마 B(messages_b)"로 바꾸고, 테이블 이름 `messages` 3곳(INSERT 대상, UPDATE 서브쿼리, ANALYZE)을 `messages_b`로 바꾼다.

- [ ] **Step 5: `db/bulk/postgresql/base.sql`**

```sql
-- 부하 측정용 공통 데이터: 사용자 1만, 방 1만, 멤버 6만8천 (세부 #6). 빈 DB에서 실행한다 (ADR-041).
-- 방 구간은 id로 정한다: 1~100 인기(멤버 50), 101~1000 중간(멤버 20), 1001~10000 나머지(멤버 5)
INSERT INTO users (nickname, created_at)
SELECT CONCAT('user', n), TIMESTAMP '2026-09-01 00:00:00' FROM generate_series(1, 10000) AS g(n) ORDER BY n;

INSERT INTO rooms (name, created_by, created_at)
SELECT CONCAT('room', n), n, TIMESTAMP '2026-09-01 00:00:00' FROM generate_series(1, 10000) AS g(n) ORDER BY n;

-- 멤버 j번은 사용자 ((방id-1 + j*97) mod 10000) + 1. j=0이 생성자이고, j<50이면 97*j < 10000이라 겹치지 않는다
INSERT INTO room_members (room_id, user_id, joined_message_id, joined_at)
SELECT r.id, MOD(r.id - 1 + s.j * 97, 10000) + 1, 0, r.created_at
FROM rooms r JOIN generate_series(0, 49) AS s(j)
  ON s.j < CASE WHEN r.id <= 100 THEN 50 WHEN r.id <= 1000 THEN 20 ELSE 5 END;
```

- [ ] **Step 6: `db/bulk/postgresql/messages_a.sql`**

```sql
-- 스키마 A(messages)에 메시지 :messages건을 넣는다. 실행: psql -v ON_ERROR_STOP=1 -v messages=500000 -f messages_a.sql
-- base.sql 다음에 실행한다
-- 메시지 k의 구간은 k mod 100으로 정해 비율이 정확히 50/30/20이 되게 하고, 구간 안에서는 방을 차례로 돌아가며 고른다 (세부 #6)
INSERT INTO messages (room_id, sender_id, content, created_at)
SELECT room_id,
       MOD(room_id - 1 + MOD(k, members) * 97, 10000) + 1,
       RPAD(CONCAT('m', k, ' '), (20 + MOD(k, 181))::int, 'x'),
       -- 30일(2,592,000,000,000마이크로초)을 메시지 수로 나눠 k 순서대로 늘린다 (I4)
       TIMESTAMP '2026-09-08 00:00:00' + (k * (2592000000000 / :messages)) * INTERVAL '1 microsecond'
FROM (
    SELECT k,
           CASE WHEN s < 50 THEN 1 + MOD(q * 50 + s, 100)
                WHEN s < 80 THEN 101 + MOD(q * 30 + s - 50, 900)
                ELSE 1001 + MOD(q * 20 + s - 80, 9000) END AS room_id,
           CASE WHEN s < 50 THEN 50 WHEN s < 80 THEN 20 ELSE 5 END AS members
    FROM (SELECT k, MOD(k, 100) AS s, k / 100 AS q FROM generate_series(1::bigint, :messages) AS g(k)) placed
) routed
ORDER BY k;

UPDATE rooms SET last_message_id = (SELECT MAX(m.id) FROM messages m WHERE m.room_id = rooms.id);

-- 실행 계획(queries/explain_a.sql)이 방금 넣은 데이터 기준으로 나오게 통계를 갱신한다
ANALYZE rooms, room_members, messages;
```

- [ ] **Step 7: `db/bulk/postgresql/messages_b.sql`**: `messages_a.sql`과 같다. 첫 주석을 "스키마 B(messages_b)"로 바꾸고, 테이블 이름 3곳(INSERT 대상, UPDATE 서브쿼리, ANALYZE)을 `messages_b`로 바꾼다.

- [ ] **Step 8: `check_distribution.sql`** (MySQL과 PostgreSQL에 같은 내용으로 둔다)

```sql
-- bulk의 구간별 메시지 비율(세부 #6: 인기 50%, 중간 30%, 나머지 20%)을 확인한다
WITH all_messages AS (
    SELECT room_id FROM messages
    UNION ALL
    SELECT room_id FROM messages_b
)
SELECT tier, COUNT(*) AS messages, ROUND(100.0 * COUNT(*) / (SELECT COUNT(*) FROM all_messages), 2) AS percent
FROM (SELECT CASE WHEN room_id <= 100 THEN 'hot' WHEN room_id <= 1000 THEN 'warm' ELSE 'cold' END AS tier
      FROM all_messages) t
GROUP BY tier
ORDER BY tier;
```

- [ ] **Step 9: 테스트 통과 확인**

Run: `./gradlew test --tests 'jissuo.chat.sql.*'` → Expected: 4조합 × 4개 테스트가 PASS한다.
- I4가 실패하면 INSERT … SELECT … ORDER BY k에서 id가 k 순서대로 붙지 않는다는 뜻이다. **SQL을 바꾸지 말고 멈춰서 보고한다.** 순서를 보장하는 방법은 사용자와 정한다.
- 테스트 시간이 길면(조합당 30초 이상) 측정값을 보고한다.

- [ ] **Step 10: 보고하고 멈춘다**

---

## 작업 4. 로컬에 넣어서 크기 재기 (측정)

**Files:** 코드 변경 없음. 결과는 `docs/journal/2026-10-07.md`(또는 진행한 날의 일지)에 표로 남긴다.

측정 조합은 실험 설계의 측정 매트릭스(`experiments.md`)를 따른다: MySQL-A, MySQL-B, PostgreSQL-A × 작은 크기(500,000), 큰 크기(5,000,000). 모두 6회다. PostgreSQL-B는 제외한다.

- [ ] **Step 1: 조합마다 반복** (예: MySQL-A, 500,000)

```bash
docker compose -f infra/compose.db.yml down -v && docker compose -f infra/compose.db.yml up -d --wait
(cd backend && ./gradlew bootRun --args='--spring.profiles.active=local,mysql')   # "Started" 로그를 확인한 뒤 Ctrl+C
time docker compose -f infra/compose.db.yml exec -T mysql mysql -uchat -pchat chat < db/bulk/mysql/base.sql
time docker compose -f infra/compose.db.yml exec -T mysql mysql -uchat -pchat --init-command="SET @messages=500000" chat < db/bulk/mysql/messages_a.sql
docker compose -f infra/compose.db.yml exec -T mysql mysql -uchat -pchat -t chat < db/queries/mysql/check_invariants.sql
docker compose -f infra/compose.db.yml exec -T mysql mysql -uchat -pchat -t chat < db/queries/mysql/sizes.sql
```
PostgreSQL은 `exec -T postgres psql -U chat -d chat -v ON_ERROR_STOP=1 -v messages=500000 < db/bulk/postgresql/messages_a.sql` 형태로 실행한다.
`sizes.sql`은 작업 5에서 만든다. 작업 4를 먼저 하면 작업 5의 Step 3~4(`sizes.sql`)를 먼저 만든다.

- [ ] **Step 2: 결과 표**

| DB-스키마 | 메시지 수 | base 시간 | messages 시간 | 메시지 테이블 데이터 MB | 인덱스 MB | 합계 MB | 버퍼(256MB) 대비 | 위반 0? |
|---|---|---|---|---|---|---|---|---|

- [ ] **Step 3: 판단 자료 보고**
  - "작은 크기는 버퍼 안, 큰 크기는 버퍼 밖"이 **측정으로** 맞았는지 보고한다.
  - 맞지 않으면 메시지 수 후보 2~3개와 추천안을 제시하고, 사용자가 정할 때까지 멈춘다.
  - 적재가 실패하거나 지나치게 느리면(예: 컨테이너 메모리 부족) 원인을 고치지 말고 그대로 보고한다. 필요하면 failure-lab에 가설로 적는다.

---

## 작업 5. 확인용 쿼리

**Files:**
- Create: `db/queries/{mysql,postgresql}/{explain_a,explain_b,sizes,cache_hit}.sql`
- Modify: `DbScriptsContract.java` (실행 확인 테스트)

- [ ] **Step 1: 실패하는 테스트**

```java
    @Test
    void 확인용_쿼리는_bulk_데이터에서_오류_없이_실행된다() {
        cli().runFile("db/bulk/" + dbDir() + "/base.sql", Map.of());
        cli().runFile("db/bulk/" + dbDir() + "/messages_" + schema() + ".sql", Map.of("messages", "1000"));

        assertThat(cli().runFile("db/queries/" + dbDir() + "/explain_" + schema() + ".sql", Map.of())).isNotEmpty();
        assertThat(cli().runFile("db/queries/" + dbDir() + "/sizes.sql", Map.of())).isNotEmpty();
        assertThat(cli().runFile("db/queries/" + dbDir() + "/cache_hit.sql", Map.of())).isNotEmpty();
    }
```

- [ ] **Step 2: `db/queries/mysql/explain_a.sql`**

```sql
-- 앱(JdbcMessageRepository, JdbcRoomRepository)과 같은 SQL의 실행 계획. 앱 SQL이 바뀌면 함께 고친다.
-- 인기 방 1과 나머지 구간 방 5000을 본다. 조회 크기는 앱처럼 size+1(=51, 방 목록은 21)이다. 경계는 id 기준(기본값)이다
SET @hot_after = (SELECT COALESCE(MAX(id), 0) - 10 FROM messages WHERE room_id = 1);
SET @hot_before = (SELECT COALESCE(MIN(id) + (MAX(id) - MIN(id)) DIV 2, 0) FROM messages WHERE room_id = 1);

-- W2 최근 메시지 (인기 방)
EXPLAIN ANALYZE SELECT id, room_id, sender_id, content, created_at FROM messages
WHERE room_id = 1 AND id > 0 ORDER BY id DESC LIMIT 51;
-- W2 최근 메시지 (나머지 구간 방)
EXPLAIN ANALYZE SELECT id, room_id, sender_id, content, created_at FROM messages
WHERE room_id = 5000 AND id > 0 ORDER BY id DESC LIMIT 51;
-- W3 과거 스크롤 (before 커서, 인기 방의 가운데)
EXPLAIN ANALYZE SELECT id, room_id, sender_id, content, created_at FROM messages
WHERE room_id = 1 AND id > 0 AND id < @hot_before ORDER BY id DESC LIMIT 51;
-- W4 폴링 (after 커서, 최근 10건 뒤)
EXPLAIN ANALYZE SELECT id, room_id, sender_id, content, created_at FROM messages
WHERE room_id = 1 AND id > 0 AND id > @hot_after ORDER BY id ASC LIMIT 51;
-- 방 목록 첫 페이지 (rooms에는 정렬용 인덱스가 없다, F20)
EXPLAIN ANALYZE SELECT id, name, created_by, last_message_id, created_at FROM rooms
ORDER BY (last_message_id IS NULL), last_message_id DESC, id DESC LIMIT 21;
```
`explain_b.sql`: 같은 파일에서 `messages`(두 SET의 FROM과 EXPLAIN 4개의 FROM, 모두 6곳)를 `messages_b`로 바꾼다. 방 목록 EXPLAIN은 그대로 둔다.

- [ ] **Step 3: `db/queries/postgresql/explain_a.sql`**

```sql
-- 앱(JdbcMessageRepository, JdbcRoomRepository)과 같은 SQL의 실행 계획. 앱 SQL이 바뀌면 함께 고친다.
-- 인기 방 1과 나머지 구간 방 5000을 본다. 조회 크기는 앱처럼 size+1(=51, 방 목록은 21)이다. 경계는 id 기준(기본값)이다
SELECT COALESCE(MAX(id), 0) - 10 AS hot_after,
       COALESCE(MIN(id) + (MAX(id) - MIN(id)) / 2, 0) AS hot_before
FROM messages WHERE room_id = 1 \gset

-- W2 최근 메시지 (인기 방)
EXPLAIN (ANALYZE, BUFFERS) SELECT id, room_id, sender_id, content, created_at FROM messages
WHERE room_id = 1 AND id > 0 ORDER BY id DESC LIMIT 51;
-- W2 최근 메시지 (나머지 구간 방)
EXPLAIN (ANALYZE, BUFFERS) SELECT id, room_id, sender_id, content, created_at FROM messages
WHERE room_id = 5000 AND id > 0 ORDER BY id DESC LIMIT 51;
-- W3 과거 스크롤 (before 커서, 인기 방의 가운데)
EXPLAIN (ANALYZE, BUFFERS) SELECT id, room_id, sender_id, content, created_at FROM messages
WHERE room_id = 1 AND id > 0 AND id < :hot_before ORDER BY id DESC LIMIT 51;
-- W4 폴링 (after 커서, 최근 10건 뒤)
EXPLAIN (ANALYZE, BUFFERS) SELECT id, room_id, sender_id, content, created_at FROM messages
WHERE room_id = 1 AND id > 0 AND id > :hot_after ORDER BY id ASC LIMIT 51;
-- 방 목록 첫 페이지 (rooms에는 정렬용 인덱스가 없다, F20)
EXPLAIN (ANALYZE, BUFFERS) SELECT id, name, created_by, last_message_id, created_at FROM rooms
ORDER BY (last_message_id IS NULL), last_message_id DESC, id DESC LIMIT 21;
```
`explain_b.sql`: `messages` 5곳(`\gset` 쿼리와 EXPLAIN 4개의 FROM)을 `messages_b`로 바꾼다.

- [ ] **Step 4: `sizes.sql`**

MySQL (`db/queries/mysql/sizes.sql`):
```sql
-- information_schema의 크기 값은 기본 86400초 동안 캐시되어, 방금 넣은 데이터가 0으로 보일 수 있다
SET SESSION information_schema_stats_expiry = 0;
ANALYZE TABLE users, rooms, room_members, messages, messages_b;

SELECT table_name, table_rows,
       ROUND(data_length / 1048576, 1) AS data_mb,
       ROUND(index_length / 1048576, 1) AS index_mb,
       ROUND((data_length + index_length) / 1048576, 1) AS total_mb
FROM information_schema.tables
WHERE table_schema = DATABASE()
ORDER BY data_length + index_length DESC;

-- 조건 C1(데이터가 메모리에 들어갈 때와 넘칠 때)의 기준인 버퍼 크기 (ADR-040)
SELECT ROUND(@@innodb_buffer_pool_size / 1048576) AS buffer_pool_mb;

-- 인덱스 목록. MySQL은 FK 컬럼(room_members.user_id)에 인덱스를 자동으로 만들고 PostgreSQL은 만들지 않는다 (2026-10-07 확인)
SELECT table_name, index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS columns
FROM information_schema.statistics
WHERE table_schema = DATABASE()
GROUP BY table_name, index_name
ORDER BY table_name, index_name;
```
PostgreSQL (`db/queries/postgresql/sizes.sql`):
```sql
SELECT relname AS table_name, n_live_tup AS live_rows,
       ROUND(pg_relation_size(relid) / 1048576.0, 1) AS data_mb,
       ROUND(pg_indexes_size(relid) / 1048576.0, 1) AS index_mb,
       ROUND(pg_total_relation_size(relid) / 1048576.0, 1) AS total_mb
FROM pg_stat_user_tables
ORDER BY pg_total_relation_size(relid) DESC;

-- 조건 C1(데이터가 메모리에 들어갈 때와 넘칠 때)의 기준인 버퍼 크기 (ADR-040)
SHOW shared_buffers;

-- 인덱스 목록. PostgreSQL은 FK 컬럼(room_members.user_id)에 인덱스를 자동으로 만들지 않는다 (2026-10-07 확인)
SELECT tablename, indexname, indexdef FROM pg_indexes WHERE schemaname = current_schema() ORDER BY tablename, indexname;
```

- [ ] **Step 5: `cache_hit.sql`**

MySQL:
```sql
-- 서버 기동 이후 누적값이다. 측정 구간의 적중률은 측정 전후 값의 차이로 계산한다:
-- 적중률 = 1 - (reads 증가분 / read_requests 증가분). SHOW는 추가 권한 없이 실행된다
SHOW GLOBAL STATUS LIKE 'Innodb_buffer_pool_read%';
```
PostgreSQL:
```sql
-- 통계 초기화(pg_stat_reset) 이후 누적값이다. 측정 구간의 적중률은 측정 전후 값의 차이로 계산한다
SELECT relname, heap_blks_read, heap_blks_hit, idx_blks_read, idx_blks_hit,
       ROUND(heap_blks_hit::numeric / NULLIF(heap_blks_hit + heap_blks_read, 0), 4) AS heap_hit_ratio,
       ROUND(idx_blks_hit::numeric / NULLIF(idx_blks_hit + idx_blks_read, 0), 4) AS idx_hit_ratio
FROM pg_statio_user_tables
ORDER BY relname;
```

- [ ] **Step 6: 테스트 통과 확인** — `./gradlew test --tests 'jissuo.chat.sql.*'` → 4조합 모두 PASS

- [ ] **Step 7: bulk 큰 크기에서 실행해 결과를 일지에 붙인다**
  - 작업 4의 큰 크기 데이터에서 `explain_a.sql`(MySQL, PostgreSQL)과 `explain_b.sql`(MySQL)을 실행한다.
  - W2~W4가 `(room_id, id)` 인덱스(B는 PK)를 타는지, 방 목록이 전체 정렬을 하는지 적는다.
  - **인덱스를 타지 않는 경우가 보여도 고치지 않는다.** failure-lab의 F16(인덱스 부재로 인한 풀스캔)이나 F20(방 목록 정렬 컬럼 갱신 경합)에 관찰로 덧붙이거나, 새 가설로 적는다 (ADR-034).

- [ ] **Step 8: 보고하고 멈춘다**

---

## 작업 6. README와 문서 반영

**Files:** `db/README.md`, `docs/adr/{진행한 날}.md`, `docs/design/architecture.md`, `docs/design/experiments.md`, `docs/README.md`, `docs/collab-rules.md`, `docs/journal/{진행한 날}.md`, `CLAUDE.md`

- [ ] **Step 1: `db/README.md`** — 다음 내용으로 쓴다 (작업 4에서 확정한 메시지 수를 반영한다)

````markdown
# 테스트용 SQL

앱 없이 DB 클라이언트만으로 실행할 수 있는 SQL이다. 테이블은 Flyway 스크립트 한 벌(`backend/src/main/resources/db/migration/{mysql,postgresql}/V1__init.sql`)로 만든다.

| 폴더 | 용도 |
|---|---|
| `seed/` | 화면 확인용 소량 데이터 (두 DB 공용) |
| `bulk/{mysql,postgresql}/` | 부하 측정용 대량 데이터 (인기 방 쏠림 50/30/20) |
| `queries/{mysql,postgresql}/` | 정합성 검사, 구간 분포, 실행 계획, 크기, 캐시 적중률 |

## 순서 (항상 빈 DB에서 시작)
messages 스키마 A/B를 바꾸거나 데이터를 다시 넣을 때는 DB를 비우고 다시 시작한다 (ADR-041).
1. `docker compose -f infra/compose.db.yml down -v && docker compose -f infra/compose.db.yml up -d --wait`
2. 스키마 만들기: `cd backend && ./gradlew bootRun --args='--spring.profiles.active=local,mysql'`를 실행해 기동 로그를 확인한 뒤 종료한다. 테이블을 직접 만든 뒤 앱을 띄우면 Flyway 이력이 없어 기동에 실패한다
3. 데이터: `base.sql` → `messages_a.sql` 또는 `messages_b.sql` (앱의 `chat.message-schema`와 맞춘다)
4. 확인: `queries/*/check_invariants.sql`의 위반 건수가 모두 0

## 실행 예
```bash
# MySQL
docker compose -f infra/compose.db.yml exec -T mysql mysql --default-character-set=utf8mb4 -uchat -pchat chat < db/seed/base.sql
docker compose -f infra/compose.db.yml exec -T mysql mysql -uchat -pchat --init-command="SET @messages=500000" chat < db/bulk/mysql/messages_a.sql
# PostgreSQL
docker compose -f infra/compose.db.yml exec -T postgres psql -U chat -d chat -v ON_ERROR_STOP=1 < db/seed/base.sql
docker compose -f infra/compose.db.yml exec -T postgres psql -U chat -d chat -v ON_ERROR_STOP=1 -v messages=500000 < db/bulk/postgresql/messages_a.sql
```
- 메시지 수는 꼭 넘긴다. MySQL에서 빠뜨리면 0건이 들어가고, PostgreSQL에서 빠뜨리면 오류가 난다.
- 정한 크기: 작은 크기 {작업 4 결과}건(버퍼 안), 큰 크기 {작업 4 결과}건(버퍼 밖). MySQL bulk의 최대치는 9,999,999건이다.

## 앱이 없는 환경
`V1__init.sql`을 클라이언트로 직접 실행한 뒤 같은 순서로 넣는다. 이 DB에 나중에 앱을 붙이면 Flyway가 실패한다.

## 데이터 형태 (bulk)
사용자 1만, 방 1만. 방 id 1~100 인기(멤버 50, 메시지 50%), 101~1000 중간(멤버 20, 30%), 1001~10000 나머지(멤버 5, 20%). 난수를 쓰지 않아 두 DB에서 같은 데이터가 나온다.
````

- [ ] **Step 2: ADR** — `docs/adr/{진행한 날}.md`에 다음 번호(ADR-054부터, 작성 시점의 마지막 번호를 확인한다)로 세부 #1~#8을 기록한다. 기존 표 형식(결정 | 이유 | 포기한 것)을 따르고, 작업 2·4에서 측정한 사실(예: #2의 실제 오류, #7의 확정값)을 이유에 적는다.

- [ ] **Step 3: 설계 문서**
  - `docs/design/architecture.md:148`: `seed/{mysql,postgresql}/` → `seed/` (두 DB 공용, ADR 번호)
  - `docs/design/experiments.md`의 "데이터:" 줄: 확정한 규모와 50/30/20 분포, `db/README.md` 링크

- [ ] **Step 4: 상태와 규칙 문서**
  - `docs/README.md`의 현재 상태에 계획 2 완료를 적는다. 다음은 계획 3(관측)이다.
  - `docs/collab-rules.md`의 기록 문서 목록에 `reports/`(테스트 결과 보고서, 요청 시 작성)를 추가한다.
  - `CLAUDE.md`의 "현재 위치"와 "기록"에 `reports/`를 넣는다.

- [ ] **Step 5: 일지** — `docs/journal/{진행한 날}.md`
  - 작업 4의 측정 표와 작업 5의 실행 계획 요약
  - 작업 2 보고서 링크
  - 미결정으로 넘기는 것: `room_members.user_id` 인덱스 차이를 DB 비교 조건으로 어떻게 다룰지(계획 5), MySQL과 PostgreSQL을 고르는 판정 규칙(계획 5 시작 전)

- [ ] **Step 6: 최종 확인과 보고** — `./gradlew test` 전체 통과. 결과를 보고하고 커밋 여부를 묻는다.

---

## 확인 방법 (끝까지)
1. `cd backend && ./gradlew test`: 기존 테스트와 `jissuo.chat.sql.*`(4조합 × 4개)가 모두 통과한다
2. compose + `bootRun`(Flyway) + seed + curl 시나리오: 작업 2 Step 7의 표대로 나오고 보고서가 있다
3. bulk 큰 크기: `check_invariants` 모두 0이고, `sizes.sql`의 메시지 테이블+인덱스가 버퍼 256MB를 넘는다 (작업 4의 측정 표)

---

## 계획 3~5 개요 (각 계획을 시작할 때 자세히 쓴다)
| 계획 | 범위 | 시작 전에 정할 것 |
|---|---|---|
| 3 관측 | Actuator/Micrometer, 구조화 로그, 로그 레벨, 감사 이벤트 수신(AUDIT 로거, IP), `infra/compose.monitoring.yml`(ADR-030) | `AccessDeniedEvent`는 롤백 트랜잭션 안에서 발행되므로 언제 수신할지. 메트릭 수집 도구 구성 |
| 4 프론트 | `frontend/` Vite + React + TS, `/api` proxy(ADR-028), seed로 화면 확인 | **화면 범위** (스펙의 남은 미결정 1번: 방 목록, 채팅방, 폴링과 WebSocket 비교 화면 등) |
| 5 실험 | k6 W1~W5 × C1~C4, `@Tag("experiment")` 실험(F1, F2, F15/F16, F18, F19, F20, F22, F23), **DB 비교 → DB 선택 ADR** | **MySQL과 PostgreSQL을 고르는 정량 판정 규칙**: ADR-014(측정 전에 판정 규칙을 확정)에 따라 측정 전에 정해야 하는데 아직 없다. `room_members.user_id` 인덱스 차이를 맞출지, 조건 차이로 기록만 할지 |

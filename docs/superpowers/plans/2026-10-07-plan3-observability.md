# 계획 3: 관측 (헬스 체크, 메트릭, 구조화 로그, 감사 로그) 구현 계획

> **실행하는 에이전트에게**: 작업은 하나씩 사용자 승인을 받고 시작한다. 작업이 끝나면 결과(테스트 출력 포함)를 보고하고 멈춘다. **커밋하지 않는다** (사용자가 요청할 때만). 단계는 체크박스(`- [ ]`)로 추적한다.

**목표:** 계획 5(실험)에서 결과를 숫자와 로그로 판정할 수 있도록, 앱에 헬스 체크·Prometheus 메트릭·요청 추적 ID가 붙은 JSON 로그·감사 로그를 붙이고, 이것을 모아 보는 모니터링 Compose(Prometheus+Grafana, Elasticsearch+Kibana+Filebeat)를 만든다.

**구조:** 앱은 `/actuator/health`, `/actuator/prometheus`를 연다. 요청마다 필터가 `requestId`·`clientIp`를 MDC에 넣고, 로그는 콘솔(사람이 읽는 텍스트)과 파일(ECS JSON) 두 곳에 쓴다. 감사 이벤트는 `audit` 패키지가 받아 `AUDIT` 전용 로거로 `audit.json`에 쓴다. Filebeat가 두 파일을 `app-*`, `audit-*` 색인으로 보낸다.

**기술:** Spring Boot 4.1.1 Actuator, Micrometer + Prometheus registry, Logback + Boot 구조화 로그(ECS), Prometheus, Grafana, Elasticsearch, Kibana, Filebeat (이미지 버전은 작업 5·6에서 확인해 패치까지 고정)

## Context
- 계획 2(테스트용 SQL)가 끝났다. ADR-033(Step 1을 계획 5개로 나눔)의 순서대로 다음은 계획 3(관측)이다. 계획 5의 F1(폴링 폭주로 커넥션 풀 고갈)과 DB 비교는 p99, 에러율, HikariCP 대기를 봐야 하는데 지금 앱에는 메트릭이 하나도 없다.
- 계획 1이 남긴 약속: 감사 이벤트 5종은 발행만 되고 받는 쪽이 없다 (`docs/superpowers/plans/2026-10-06-plan1-backend-core.md`의 "감사 이벤트" 표). IP는 받는 쪽이 채운다.
- 이미 결정된 것 (그대로 구현): ADR-021(모니터링 구성), ADR-022(환경 3개와 로그 레벨 표), ADR-023(감사 로그: 별도 저장소, 커밋 후 기록, 대상), ADR-030(Compose를 DB용과 모니터링용으로 분리)
- 사용자 결정 (2026-10-07)
  - 실패 감사 이벤트(인가 실패, 인증 실패)는 **트랜잭션이 끝난 뒤** 기록한다 (`AFTER_COMPLETION` + `fallbackExecution=true`). 처음 추천은 "즉시 기록(`@EventListener`)"이었으나, 처리 시간 차이는 없고(예상) 트랜잭션 안에서 리스너 예외가 403을 500으로 바꿀 수 있어 추천을 바꿨다.
  - 추적 ID는 **직접 만든 필터** (UUID, MDC + 응답 헤더 `X-Request-Id`)
  - **접근 로그(요청마다 한 줄)는 같은 필터 한 곳에서** 남긴다. 감사 로그는 필터로 옮기지 않고 이벤트로 남긴다. 접근 로그는 local·prod에서 켜고 bench에서 끈다
  - Grafana는 **직접 만든 작은 대시보드**를 저장소에 두고 자동 등록한다
  - local 콘솔은 **사람이 읽는 텍스트**, 파일은 항상 ECS JSON
- 근거 문서: `docs/design/architecture.md:57-101`(공용 응답과 모니터링 구성), Step 1 설계 문서 6장(관측)

## 지켜야 할 조건
- **장애 선행 (ADR-034)**: 관측을 붙이다 발견한 문제(로그 폭증, 측정 왜곡 등)는 `docs/failure-lab.md`에 가설로만 적는다. 코드로 미리 막지 않는다.
- 기존 API 동작(상태 코드, 응답 본문)은 바뀌지 않는다. 기존 테스트가 모두 그대로 통과해야 한다.
- 의존 방향: `audit`은 이벤트를 받기만 한다. `audit` 밖의 코드는 `audit`을 모른다 (작업 4에서 ArchUnit 규칙 추가).
- 감사 로그에 메시지 본문은 넣지 않는다 (ADR-023).
- Boot 4는 모듈 이름이 3.x와 다르다. **추측하지 않고** 실제로 해석되는 이름을 확인해 쓴다 (계획 1 작업 0과 같은 원칙).
- 측정 결과를 적을 때 "예상"과 "측정"을 구분한다.
- 코드 주석은 "왜"만 쓰고, 이 계획의 세부를 근거로 하면 승인 후 받은 ADR 번호를 적는다.

## 이 계획에서 새로 정하는 세부 (검토 필요, 승인되면 작업 7에서 ADR-062부터 기록)
| # | 항목 | 제안 | 이유 |
|---|---|---|---|
| 1 | 성공 감사 수신 시점 | 방 생성·입장·나가기는 `@TransactionalEventListener`(기본 `AFTER_COMMIT`) | ADR-023 그대로. 롤백된 성공이 기록되지 않는다 |
| 2 | 실패 감사 수신 시점 | 인가·인증 실패는 `@TransactionalEventListener(phase = AFTER_COMPLETION, fallbackExecution = true)` | 사용자 결정. 거부는 롤백과 무관하게 일어난 일이다. 트랜잭션이 없는 조회와 필터에서도 기록된다 |
| 3 | 추적 ID | `RequestLogContextFilter`(가장 먼저 실행)가 UUID를 만들어 MDC `requestId`와 응답 헤더 `X-Request-Id`에 넣는다. 클라이언트가 보낸 `X-Request-Id`는 무시한다 | 사용자 결정. 의존성 없이 curl·k6 응답에서 바로 로그를 찾는다. 검색 키를 외부가 고르지 못하게 한다 |
| 4 | 클라이언트 IP | `server.forward-headers-strategy: native` (Tomcat `RemoteIpValve`). 내부 대역(127.x, 10.x, 192.168.x 등)에서 온 요청의 `X-Forwarded-For`만 반영하고, 그 결과(`getRemoteAddr()`)를 MDC `clientIp`에 넣는다 | 헤더를 직접 읽으면 누구나 IP를 위조할 수 있다. 기본 신뢰 범위가 Vite proxy(localhost)와 Step 3 nginx(내부망)를 덮는다 |
| 5 | 로그 출력 | 콘솔은 Boot 기본 텍스트 + `[requestId]`, 파일은 ECS JSON. 파일은 `${LOG_DIR:-logs}/app.json`, `audit.json` (bootRun이면 `backend/logs/`). 테스트는 `build/test-logs/` | 사용자 결정(콘솔 텍스트). Filebeat는 파일만 읽는다. 테스트가 저장소에 로그 파일을 만들지 않게 한다 |
| 6 | 로그 파일 보관 (로컬) | app: 10MB 단위, 3일, 총 200MB / audit: 10MB 단위, 30일, 총 500MB | 일반 로그는 짧게, 감사는 길게 (ADR-021). 로컬 디스크가 넘치지 않게 상한을 둔다 |
| 7 | 감사 로그 필드 | 메시지 `audit` + SLF4J key-value: `action`(`ROOM_CREATED`·`MEMBER_JOINED`·`MEMBER_LEFT`·`ACCESS_DENIED`·`AUTHENTICATION_FAILED`), `outcome`(`SUCCESS`/`FAILURE`), `userId`, `roomId`, `code`, `path`, `credential`(앞 64자까지), `occurredAt`. 요청 정보(`requestId`, `clientIp`)는 MDC에서 자동으로 붙는다 | ADR-023의 누가·언제·무엇을·어디서·결과. 인증 실패 헤더 값은 공격자가 고르는 값이라 길이를 자른다 |
| 8 | 메트릭 설정 | `http.server.requests` 히스토그램 켬, 공통 태그 `application=chat`, `db=mysql|postgres`, `schema=A|B`, Tomcat 스레드 메트릭 켬(`server.tomcat.mbeanregistry.enabled`) | p99를 Prometheus에서 계산하려면 히스토그램 버킷이 필요하다. DB·스키마를 바꿔 가며 잰 값을 Grafana에서 구분한다. F1에서 스레드가 커넥션을 기다리는지 본다 |
| 9 | Actuator 노출 | 기본 `health,prometheus`. local·bench는 `loggers` 추가. health 구성 요소는 항상 보이고, 상세(DB 종류 등)는 local만 | ADR-022 표 그대로 + 상세 정보는 개발 중에만 |
| 10 | bench 프로필의 DB 주소 | local과 같은 compose DB 주소를 `application-bench.yml`에 복사한다 | 실험용 DB가 따로 생기면 이 파일만 바꾼다. local의 TRACE 로그 없이 같은 DB에 붙는다 |
| 11 | 모니터링 Compose | `infra/compose.monitoring.yml` 한 파일에 Compose profile `metrics`(Prometheus, Grafana)와 `logs`(Elasticsearch, Kibana, Filebeat)를 둔다. 포트는 Prometheus 19090, Grafana 13000, Elasticsearch 19200, Kibana 15601 | 부하 측정 중에는 `metrics`만 켜서 메모리 경쟁을 줄인다 (ADR-021 "필요할 때만"). 포트는 DB처럼 1만 번대로 겹침을 피한다 |
| 12 | Prometheus 수집 | 5초마다 `host.docker.internal:8080/actuator/prometheus` | 앱은 compose 밖(bootRun)에서 돈다. 실험이 수십 초 단위라 기본 15초보다 촘촘해야 한다 |
| 13 | Elasticsearch 설정 | 단일 노드, 보안 끔, 힙 512MB, 메모리 제한 1GB. 색인 이름은 `app-YYYY.MM.dd`, `audit-YYYY.MM.dd`. 보관 기간 정책(ILM)은 로컬에서 설정하지 않는다 | 로컬 학습용. 색인 이름만 나눠 두면 운영에서 보관 정책을 따로 걸 수 있다 |
| 14 | Grafana 접속 | 익명 접속 허용(관리자 권한), 데이터 소스와 대시보드는 파일로 자동 등록 | 로컬 전용. `docker compose up`만으로 같은 화면이 나온다 |
| 15 | 접근 로그 (요청마다 한 줄) | `RequestLogContextFilter`가 `finally`에서 전용 로거 `ACCESS`로 남긴다. 메시지 `access` + key-value `method`, `path`, `query`, `status`, `durationMs`. 처리 중 예외가 밖으로 나오면 `status`를 500으로 적는다. `/actuator/**`는 남기지 않는다 | 사용자 결정(필터 한 곳). 401을 포함한 모든 응답을 한 곳에서 본다. Prometheus가 5초마다 긁는 요청은 잡음이다 |
| 16 | 접근 로그 환경별 | 기본(local, prod) `logging.level.ACCESS: INFO`, bench는 `OFF`. 필요하면 `/actuator/loggers/ACCESS`로 실행 중에 켠다 | 사용자 결정. prod의 root WARN에 묻히지 않게 레벨을 직접 준다. bench는 측정 왜곡을 막는다 (ADR-022) |
| 17 | 사용자 id 전달 | `AuthFilter`가 인증에 성공하면 MDC `userId`에 넣고, `RequestLogContextFilter`가 로그를 남긴 뒤 지운다 | `common` 필터가 `auth`의 `AuthUser`를 읽으면 `auth → common → auth` 순환이 생긴다. MDC에 두면 감사·일반 로그에도 `userId`가 자동으로 붙는다 |
| 18 | 감사 로그 위치 | 필터로 옮기지 않고 이벤트 리스너에 둔다 | 사용자 결정. 필터는 URL과 상태 코드만 알아 방 생성 `roomId`를 모르고, Step 2 WebSocket 동작은 필터를 거치지 않는다. 접근 로그와는 `requestId`로 잇는다 |

## 파일 구조
```
backend/
 ├─ build.gradle.kts                         (수정: actuator, prometheus, 테스트 LOG_DIR)
 ├─ src/main/resources/
 │   ├─ application.yml                      (수정: actuator, 메트릭, 프록시 헤더, 상관 ID 패턴)
 │   ├─ application-local.yml                (수정: 로그 레벨, loggers 공개)
 │   ├─ application-bench.yml, application-prod.yml   (새로)
 │   ├─ application-mysql.yml, application-postgres.yml (수정: db 태그)
 │   └─ logback-spring.xml                   (새로)
 ├─ src/main/java/jissuo/chat/
 │   ├─ common/RequestLogContextFilter.java  (새로: 추적 ID, IP, 접근 로그)
 │   ├─ auth/AuthFilter.java                 (수정: MDC userId)
 │   └─ audit/AuditListener.java             (새로)
 └─ src/test/java/jissuo/chat/
     ├─ ArchitectureTest.java                (수정: audit 규칙)
     ├─ observe/ObservabilityEndpointsTest.java, LocalProfileTest.java, BenchProfileTest.java, ProdProfileTest.java
     ├─ common/RequestLogContextFilterTest.java, ClientIpTest.java
     └─ audit/AuditLogTest.java
infra/
 ├─ compose.monitoring.yml
 ├─ prometheus/prometheus.yml
 ├─ grafana/provisioning/{datasources/prometheus.yml, dashboards/provider.yml}, grafana/dashboards/chat-step1.json
 └─ filebeat/filebeat.yml
.gitignore                                   (수정: backend/logs/)
```

---

## 작업 0. 계획 저장
- [x] 이 문서를 `docs/superpowers/plans/2026-10-07-plan3-observability.md`에 저장한다 (실행 날짜가 다르면 그 날짜)

---

## 작업 1. 헬스 체크와 Prometheus 메트릭

**Files:**
- Modify: `backend/build.gradle.kts`, `backend/src/main/resources/application.yml`, `application-mysql.yml`, `application-postgres.yml`
- Create: `backend/src/test/java/jissuo/chat/observe/ObservabilityEndpointsTest.java`

**Interfaces:**
- Produces: `/actuator/health`(구성 요소 `db` 포함), `/actuator/prometheus`(`http_server_requests_seconds_bucket`, `hikaricp_connections_*`, 태그 `application`·`db`·`schema`). 작업 5의 Prometheus와 대시보드가 이 이름을 쓴다.

- [x] **Step 1: 실패하는 테스트**

```java
package jissuo.chat.observe;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jissuo.chat.support.MySqlContainerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureMetrics
@ActiveProfiles("mysql")
class ObservabilityEndpointsTest {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }

    @Autowired MockMvc mvc;

    @Test
    void 헬스_체크는_인증_없이_DB_상태를_보여준다() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"));
    }

    @Test
    void 프로메테우스는_p99_계산용_버킷과_커넥션_풀_지표를_DB_태그와_함께_낸다() throws Exception {
        mvc.perform(get("/api/rooms")).andExpect(status().isUnauthorized()); // 요청 지표를 하나 만든다
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("http_server_requests_seconds_bucket")))
                .andExpect(content().string(containsString("hikaricp_connections_active")))
                .andExpect(content().string(containsString("application=\"chat\"")))
                .andExpect(content().string(containsString("db=\"mysql\"")))
                .andExpect(content().string(containsString("schema=\"A\"")));
    }

    @Test
    void 기본_프로필에서는_loggers를_열지_않는다() throws Exception {
        mvc.perform(get("/actuator/loggers")).andExpect(status().isNotFound());
    }
}
```

- [x] **Step 2: 실패 확인**
Run: `cd backend && ./gradlew test --tests 'jissuo.chat.observe.ObservabilityEndpointsTest'`
Expected: FAIL (`/actuator/health` 404 → `NOT_FOUND` `ApiResponse`)

- [x] **Step 3: 의존성 추가**
`build.gradle.kts`의 `dependencies`에 추가한다:
```kotlin
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	runtimeOnly("io.micrometer:micrometer-registry-prometheus")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
```
`./gradlew dependencies --configuration testRuntimeClasspath | grep -E "actuator|prometheus"`로 세 이름이 실제로 해석되는지 확인한다. `spring-boot-starter-actuator-test`가 없으면 그 줄은 빼고 보고에 적는다.

- [x] **Step 4: 설정**
`application.yml` 전체:
```yaml
spring:
  application:
    name: chat
server:
  # 계획 3 세부 #4: 내부 대역 프록시(Vite, nginx)가 붙인 X-Forwarded-For만 믿는다
  forward-headers-strategy: native
  tomcat:
    mbeanregistry:
      enabled: true   # 계획 3 세부 #8: F1에서 요청 스레드가 커넥션을 기다리는지 본다
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
  endpoint:
    health:
      show-components: always
  metrics:
    tags:
      application: ${spring.application.name}
      schema: ${chat.message-schema:A}
    distribution:
      percentiles-histogram:
        # p99를 Prometheus에서 계산하려면 버킷이 필요하다 (계획 3 세부 #8)
        http.server.requests: true
logging:
  pattern:
    correlation: "[%X{requestId:-}] "
```
`application-mysql.yml`에 추가 (`application-postgres.yml`은 값만 `postgres`):
```yaml
management:
  metrics:
    tags:
      db: mysql
```

- [x] **Step 5: 통과 확인**
Run: `./gradlew test --tests 'jissuo.chat.observe.ObservabilityEndpointsTest'` → PASS.
`db="mysql"`가 없으면 Boot 4의 공통 태그 속성 이름이 바뀐 것이다. 추측해서 바꾸지 말고 Boot 4.1 문서(`MetricsProperties`)에서 확인한 이름을 쓰고 보고한다.

- [x] **Step 6: 기존 테스트 확인**
Run: `./gradlew test` → 전체 PASS. `/actuator/**`는 `/api/`로 시작하지 않아 `AuthFilter`를 거치지 않는다.

---

## 작업 2. 환경 프로필과 로그 레벨 (ADR-022)

**Files:**
- Modify: `backend/src/main/resources/application-local.yml`
- Create: `application-bench.yml`, `application-prod.yml`
- Create: `backend/src/test/java/jissuo/chat/observe/{LocalProfileTest,BenchProfileTest,ProdProfileTest}.java`

**Interfaces:**
- Produces: 프로필 `bench`, `prod`. 계획 5의 부하 측정은 `--spring.profiles.active=bench,mysql`로 띄운다.

- [x] **Step 1: 실패하는 테스트 3개**

```java
package jissuo.chat.observe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import jissuo.chat.support.MySqlContainerSupport;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** ADR-022 표의 local 행 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "mysql"})
class LocalProfileTest {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }

    @Autowired MockMvc mvc;

    @Test
    void 우리_코드는_TRACE_외부는_INFO이고_SQL_커넥션풀_트랜잭션_로그만_연다() {
        assertThat(level("jissuo.chat")).isEqualTo(Level.TRACE);
        assertThat(level(Logger.ROOT_LOGGER_NAME)).isEqualTo(Level.INFO);
        assertThat(level("org.springframework.jdbc.core")).isEqualTo(Level.TRACE);
        assertThat(level("com.zaxxer.hikari")).isEqualTo(Level.DEBUG);
        assertThat(level("org.springframework.jdbc.support.JdbcTransactionManager")).isEqualTo(Level.DEBUG);
    }

    @Test
    void loggers와_헬스_상세를_연다() throws Exception {
        mvc.perform(get("/actuator/loggers/jissuo.chat")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(jsonPath("$.components.db.details.database").exists());
    }

    static Level level(String name) {
        return ((Logger) LoggerFactory.getLogger(name)).getLevel();
    }
}
```
`BenchProfileTest`(`@ActiveProfiles({"bench","mysql"})`): `jissuo.chat`=INFO, root=WARN, `org.springframework.jdbc.core`=null(따로 열지 않음), `/actuator/loggers/jissuo.chat` 200, `$.components.db.details` 없음.
`ProdProfileTest`(`@ActiveProfiles({"prod","mysql"})`): `jissuo.chat`=INFO, root=WARN, `/actuator/loggers` 404.
두 클래스는 위와 같은 모양으로 쓰고, `level(...)`은 `LocalProfileTest.level`을 부른다.

- [x] **Step 2: 실패 확인**
Run: `./gradlew test --tests 'jissuo.chat.observe.*ProfileTest'` → FAIL (레벨이 null, bench·prod 프로필 파일 없음)

- [x] **Step 3: 설정**
`application-local.yml`의 첫 문서(공통 부분)에 추가한다:
```yaml
logging:
  level:
    root: INFO
    jissuo.chat: TRACE
    # ADR-022: 실험에 필요한 외부 로그만 연다. DEBUG는 SQL, TRACE는 바인딩 값
    org.springframework.jdbc.core: TRACE
    com.zaxxer.hikari: DEBUG
    org.springframework.jdbc.support.JdbcTransactionManager: DEBUG
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,loggers
  endpoint:
    health:
      show-details: always
```
`application-bench.yml`:
```yaml
# ADR-022: 부하 측정용. 로그를 줄여 측정 왜곡을 막는다
logging:
  level:
    root: WARN
    jissuo.chat: INFO
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,loggers
# 계획 3 세부 #10: 실험용 DB가 따로 생기기 전까지 local과 같은 compose DB를 쓴다
spring:
  datasource:
    username: chat
    password: chat
---
spring:
  config:
    activate:
      on-profile: mysql
  datasource:
    url: jdbc:mysql://localhost:13306/chat
---
spring:
  config:
    activate:
      on-profile: postgres
  datasource:
    url: jdbc:postgresql://localhost:15432/chat
```
`application-prod.yml`:
```yaml
# ADR-022: loggers는 공개하지 않는다 (기본 노출 health,prometheus 그대로). DB 주소는 배포 환경 변수로 받는다
logging:
  level:
    root: WARN
    jissuo.chat: INFO
```

- [x] **Step 4: 통과 확인**
Run: `./gradlew test --tests 'jissuo.chat.observe.*'` → PASS. 이어서 `./gradlew test` 전체 PASS.
(`DevUserControllerTest`는 이미 `local` 프로필이라 테스트 출력에 TRACE 로그가 늘어난다. 실패가 아니면 그대로 두고 보고에 적는다)

---

## 작업 3. 요청 추적 ID, 클라이언트 IP, 접근 로그, 구조화 로그 파일

**Files:**
- Create: `backend/src/main/java/jissuo/chat/common/RequestLogContextFilter.java`
- Modify: `backend/src/main/java/jissuo/chat/auth/AuthFilter.java` (MDC `userId`)
- Create: `backend/src/main/resources/logback-spring.xml`
- Modify: `backend/src/main/resources/application.yml`, `application-bench.yml` (`ACCESS` 레벨)
- Modify: `backend/build.gradle.kts` (테스트 `LOG_DIR`), `.gitignore`
- Test: `backend/src/test/java/jissuo/chat/common/{RequestLogContextFilterTest,ClientIpTest}.java`, `auth/AuthFilterTest.java`(수정)

**Interfaces:**
- Produces: `RequestLogContextFilter.HEADER = "X-Request-Id"`, MDC 키 `REQUEST_ID = "requestId"`, `CLIENT_IP = "clientIp"`, `USER_ID = "userId"` (작업 4의 감사 테스트가 쓴다). 로거 `ACCESS`(접근 로그, 일반 로그 파일로 간다), 로거 `AUDIT`은 `AUDIT_FILE`로만 간다 (local은 콘솔에도).

- [x] **Step 1: 실패하는 단위 테스트**

```java
package jissuo.chat.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestLogContextFilterTest {

    final RequestLogContextFilter filter = new RequestLogContextFilter();
    final ListAppender<ILoggingEvent> access = new ListAppender<>();

    @BeforeEach
    void attach() {
        access.start();
        accessLogger().addAppender(access);
    }

    @AfterEach
    void detach() {
        accessLogger().detachAppender(access);
    }

    @Test
    void 요청이_끝나면_접근_로그_한_줄을_사용자_id와_함께_남기고_MDC를_비운다() throws Exception {
        // AuthFilter가 인증 성공 후 하는 일을 흉내 낸다 (계획 3 세부 #17)
        FilterChain chain = (req, res) -> {
            MDC.put(RequestLogContextFilter.USER_ID, "7");
            ((MockHttpServletResponse) res).setStatus(201);
        };
        var request = new MockHttpServletRequest("POST", "/api/rooms/3/messages");
        request.setQueryString("after=10");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(access.list).hasSize(1);
        ILoggingEvent line = access.list.getFirst();
        assertThat(fields(line)).containsEntry("method", "POST").containsEntry("path", "/api/rooms/3/messages")
                .containsEntry("query", "after=10").containsEntry("status", 201).containsKey("durationMs");
        assertThat(line.getMDCPropertyMap()).containsEntry(RequestLogContextFilter.USER_ID, "7")
                .containsKey(RequestLogContextFilter.REQUEST_ID);
        assertThat(MDC.get(RequestLogContextFilter.USER_ID)).isNull();
    }

    @Test
    void 밖으로_나온_예외는_500으로_적는다() {
        FilterChain chain = (req, res) -> { throw new ServletException("boom"); };

        assertThatThrownBy(() -> filter.doFilter(
                new MockHttpServletRequest("GET", "/api/rooms"), new MockHttpServletResponse(), chain))
                .isInstanceOf(ServletException.class);
        assertThat(fields(access.list.getFirst())).containsEntry("status", 500);
    }

    @Test
    void 액추에이터_요청은_접근_로그를_남기지_않는다() throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/actuator/prometheus"), new MockHttpServletResponse(),
                (req, res) -> { });

        assertThat(access.list).isEmpty();
    }

    private static Logger accessLogger() {
        return (Logger) LoggerFactory.getLogger("ACCESS");
    }

    private static Map<String, Object> fields(ILoggingEvent event) {
        Map<String, Object> fields = new HashMap<>();
        event.getKeyValuePairs().forEach(pair -> fields.put(pair.key, pair.value));
        return fields;
    }

    @Test
    void 요청_동안_추적_ID와_IP를_MDC에_두고_응답_헤더로_돌려준다() throws Exception {
        Map<String, String> seen = new HashMap<>();
        FilterChain chain = (req, res) -> seen.putAll(MDC.getCopyOfContextMap());
        var request = new MockHttpServletRequest("GET", "/api/rooms");
        request.setRemoteAddr("203.0.113.7");
        request.addHeader(RequestLogContextFilter.HEADER, "from-client");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        String requestId = response.getHeader(RequestLogContextFilter.HEADER);
        assertThat(UUID.fromString(requestId)).isNotNull();
        assertThat(requestId).isNotEqualTo("from-client");
        assertThat(seen).containsEntry(RequestLogContextFilter.REQUEST_ID, requestId)
                .containsEntry(RequestLogContextFilter.CLIENT_IP, "203.0.113.7");
        assertThat(MDC.get(RequestLogContextFilter.REQUEST_ID)).isNull();
    }

    @Test
    void 처리_중_예외가_나도_MDC를_비운다() {
        FilterChain chain = (req, res) -> { throw new ServletException("boom"); };

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain))
                .isInstanceOf(ServletException.class);
        assertThat(MDC.get(RequestLogContextFilter.REQUEST_ID)).isNull();
        assertThat(MDC.get(RequestLogContextFilter.CLIENT_IP)).isNull();
    }
}
```
`AuthFilterTest`에 추가: 인증에 성공한 요청은 체인 안에서 `MDC.get("userId")`가 헤더 값과 같고, 실패한 요청은 `userId`가 없다 (기존 테스트의 체인 람다에서 MDC를 기록해 확인).

- [x] **Step 2: 실패 확인**
Run: `./gradlew test --tests 'jissuo.chat.common.RequestLogContextFilterTest' --tests 'jissuo.chat.auth.AuthFilterTest'` → 컴파일 실패 (클래스 없음)

- [x] **Step 3: 필터 구현**

```java
package jissuo.chat.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청 로그를 한 곳에서 남긴다 (계획 3 세부 #15). AuthFilter의 401도 남기고 인증 실패 기록에도 추적 ID가 붙도록 가장 먼저 실행한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLogContextFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String REQUEST_ID = "requestId";
    public static final String CLIENT_IP = "clientIp";
    public static final String USER_ID = "userId";

    // 계획 3 세부 #16: 환경별로 켜고 끌 수 있도록 일반 로그와 로거 이름을 나눈다 (bench는 OFF)
    private static final Logger ACCESS = LoggerFactory.getLogger("ACCESS");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 계획 3 세부 #3: 클라이언트가 보낸 값은 쓰지 않는다. 로그 검색 키를 외부가 고르지 못하게 한다
        String requestId = UUID.randomUUID().toString();
        MDC.put(REQUEST_ID, requestId);
        // forward-headers-strategy=native 덕분에 믿을 수 있는 프록시가 붙인 X-Forwarded-For만 반영된 값이다 (계획 3 세부 #4)
        MDC.put(CLIENT_IP, request.getRemoteAddr());
        response.setHeader(HEADER, requestId);
        long started = System.nanoTime();
        boolean failed = true;
        try {
            chain.doFilter(request, response);
            failed = false;
        } finally {
            if (!request.getRequestURI().startsWith("/actuator/")) {
                // 밖으로 나온 예외는 컨테이너가 500으로 바꾸지만 이 시점의 응답 상태에는 아직 반영되지 않았다
                ACCESS.atInfo().setMessage("access")
                        .addKeyValue("method", request.getMethod())
                        .addKeyValue("path", request.getRequestURI())
                        .addKeyValue("query", request.getQueryString())
                        .addKeyValue("status", failed ? 500 : response.getStatus())
                        .addKeyValue("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                        .log();
            }
            MDC.remove(REQUEST_ID);
            MDC.remove(CLIENT_IP);
            MDC.remove(USER_ID);
        }
    }
}
```
`AuthFilter.doFilterInternal`에서 `request.setAttribute(ATTRIBUTE, user);` 바로 다음에 추가한다 (`auth → common`은 이미 있는 의존 방향이다):
```java
        // 계획 3 세부 #17: 접근·감사·일반 로그에 사용자를 붙인다. 지우는 일은 RequestLogContextFilter가 한다
        MDC.put(RequestLogContextFilter.USER_ID, String.valueOf(user.id()));
```
(주석의 "계획 3 세부 #N"은 작업 7에서 받은 ADR 번호로 바꾼다)

`application.yml`에 추가:
```yaml
logging:
  level:
    ACCESS: INFO   # 계획 3 세부 #16: prod의 root WARN에 묻히지 않게 직접 준다
```
`application-bench.yml`의 `logging.level`에 추가:
```yaml
    ACCESS: "OFF"   # 계획 3 세부 #16: 요청마다 한 줄은 측정을 흔든다. 필요하면 /actuator/loggers/ACCESS로 켠다
```
작업 2의 `BenchProfileTest`에 `level("ACCESS") == Level.OFF`, `ProdProfileTest`에 `level("ACCESS") == Level.INFO` 확인을 더한다.

- [x] **Step 4: 단위 테스트 통과 확인**
Run: `./gradlew test --tests 'jissuo.chat.common.RequestLogContextFilterTest' --tests 'jissuo.chat.auth.AuthFilterTest' --tests 'jissuo.chat.observe.*'` → PASS

- [x] **Step 5: 프록시 헤더 신뢰 테스트 (실제 Tomcat)**
MockMvc는 Tomcat `RemoteIpValve`를 거치지 않으므로 `AuthPathBypassTest`처럼 실제 서버를 띄운다.

```java
package jissuo.chat.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import jissuo.chat.support.MySqlContainerSupport;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 계획 3 세부 #4: 내부 대역에서 온 X-Forwarded-For만 믿는다 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
@Import(ClientIpTest.ProbeController.class)
class ClientIpTest {

    final HttpClient client = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }

    @LocalServerPort
    int port;

    @Test
    void 헤더가_없으면_접속한_주소를_쓴다() throws Exception {
        assertThat(clientIp(null)).isEqualTo("127.0.0.1");
    }

    @Test
    void 내부_대역_프록시가_붙인_주소를_쓴다() throws Exception {
        assertThat(clientIp("203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    void 클라이언트가_앞에_끼워_넣은_주소는_믿지_않는다() throws Exception {
        // 오른쪽부터 믿을 수 있는 프록시를 건너뛰고 처음 만난 외부 주소를 쓴다
        assertThat(clientIp("1.2.3.4, 203.0.113.7")).isEqualTo("203.0.113.7");
    }

    private String clientIp(String forwardedFor) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/dev/probe/client-ip"));
        if (forwardedFor != null) {
            builder.header("X-Forwarded-For", forwardedFor);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    // /api/dev/는 인증 필터 밖이라 헤더 없이 부를 수 있다
    @RestController
    static class ProbeController {
        @GetMapping("/api/dev/probe/client-ip")
        String clientIp() {
            return MDC.get(RequestLogContextFilter.CLIENT_IP);
        }
    }
}
```
Run: `./gradlew test --tests 'jissuo.chat.common.ClientIpTest'` → PASS (작업 1에서 `forward-headers-strategy`를 이미 넣었다). 세 번째 테스트가 다르게 나오면 결과 그대로 보고하고 세부 #4를 다시 의논한다.

- [x] **Step 6: `logback-spring.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>
    <include resource="org/springframework/boot/logging/logback/console-appender.xml"/>

    <!-- 계획 3 세부 #5: bootRun이면 backend/logs, 테스트는 Gradle이 build/test-logs를 넘긴다 -->
    <property name="LOG_DIR" value="${LOG_DIR:-logs}"/>

    <!-- 계획 3 세부 #5, #6: Filebeat가 읽는 ECS JSON. 일반 로그는 짧게 보관한다 -->
    <appender name="APP_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_DIR}/app.json</file>
        <encoder class="org.springframework.boot.logging.logback.StructuredLogEncoder">
            <format>ecs</format>
            <charset>UTF-8</charset>
        </encoder>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${LOG_DIR}/app.%d{yyyy-MM-dd}.%i.json</fileNamePattern>
            <maxFileSize>10MB</maxFileSize>
            <maxHistory>3</maxHistory>
            <totalSizeCap>200MB</totalSizeCap>
        </rollingPolicy>
    </appender>

    <!-- ADR-023: 감사 로그는 보관 기간과 접근 권한을 따로 두기 위해 파일부터 나눈다 -->
    <appender name="AUDIT_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_DIR}/audit.json</file>
        <encoder class="org.springframework.boot.logging.logback.StructuredLogEncoder">
            <format>ecs</format>
            <charset>UTF-8</charset>
        </encoder>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${LOG_DIR}/audit.%d{yyyy-MM-dd}.%i.json</fileNamePattern>
            <maxFileSize>10MB</maxFileSize>
            <maxHistory>30</maxHistory>
            <totalSizeCap>500MB</totalSizeCap>
        </rollingPolicy>
    </appender>

    <!-- ADR-022: 감사 로그는 local에서만 콘솔에도 보인다 -->
    <springProfile name="local">
        <logger name="AUDIT" level="INFO" additivity="false">
            <appender-ref ref="AUDIT_FILE"/>
            <appender-ref ref="CONSOLE"/>
        </logger>
    </springProfile>
    <springProfile name="!local">
        <logger name="AUDIT" level="INFO" additivity="false">
            <appender-ref ref="AUDIT_FILE"/>
        </logger>
    </springProfile>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="APP_FILE"/>
    </root>
</configuration>
```
`build.gradle.kts`의 `tasks.withType<Test>` 블록:
```kotlin
tasks.withType<Test> {
	useJUnitPlatform()
	// 계획 3 세부 #5: 테스트가 backend/logs에 로그 파일을 만들지 않게 한다
	systemProperty("LOG_DIR", layout.buildDirectory.dir("test-logs").get().asFile.absolutePath)
}
```
`.gitignore`의 Backend 부분에 `backend/logs/`를 추가한다.

- [x] **Step 7: 확인**
1. `./gradlew test` → 전체 PASS, `backend/build/test-logs/app.json`이 생기고 `backend/logs/`는 생기지 않는다.
2. 수동: `docker compose -f infra/compose.db.yml up -d --wait` → `./gradlew bootRun --args='--spring.profiles.active=local,mysql'` → `curl -i localhost:8080/api/rooms`
   - 응답 헤더 `X-Request-Id`가 있다
   - 콘솔 줄에 `[그 requestId]`가 보인다 (안 보이면 `logging.pattern.correlation`이 커스텀 logback에서 적용되지 않는 것이다. `CONSOLE_LOG_PATTERN`을 직접 정의하는 방법을 제안하고 멈춘다)
   - `tail -1 backend/logs/app.json`이 한 줄 JSON이고 `@timestamp`, `log.level`, `requestId`, `clientIp`가 있다
   - 접근 로그: 헤더 없는 요청은 `status` 401, `curl -H 'X-User-Id: 1' localhost:8080/api/rooms`는 `status` 200 + `userId` 1인 `access` 줄이 `app.json`에 한 줄씩 생긴다. `/actuator/health`는 줄이 생기지 않는다
   - SQL 로그(`Executing prepared SQL statement`)와 바인딩 값이 보인다

---

## 작업 4. 감사 로그 수신 (`audit`)

**Files:**
- Create: `backend/src/main/java/jissuo/chat/audit/AuditListener.java`
- Modify: `backend/src/test/java/jissuo/chat/ArchitectureTest.java`
- Test: `backend/src/test/java/jissuo/chat/audit/AuditLogTest.java`

**Interfaces:**
- Consumes: `RoomCreatedEvent(roomId, userId, at)`, `MemberJoinedEvent`, `MemberLeftEvent`(같은 필드), `AccessDeniedEvent(roomId, userId, code, at)`, `AuthenticationFailedEvent(credential, path, at)`. MDC `requestId`, `clientIp` (작업 3)
- Produces: 로거 `AUDIT`의 `audit` 메시지와 key-value (세부 #7). 작업 6의 Kibana `audit-*`가 이 필드를 검색한다.

- [ ] **Step 1: 실패하는 통합 테스트 (MySQL 하나)**
리스너는 DB 종류와 무관하고 트랜잭션 동작만 보면 되므로 MySQL에서만 돌린다.

```java
package jissuo.chat.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jissuo.chat.common.RequestLogContextFilter;
import jissuo.chat.room.domain.RoomCreatedEvent;
import jissuo.chat.support.MySqlContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
class AuditLogTest {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired TransactionTemplate tx;

    final ListAppender<ILoggingEvent> audit = new ListAppender<>();
    long owner;
    long stranger;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM messages").update();
        jdbc.sql("DELETE FROM messages_b").update();
        jdbc.sql("DELETE FROM room_members").update();
        jdbc.sql("DELETE FROM rooms").update();
        jdbc.sql("DELETE FROM users").update();
        owner = addUser("철수");
        stranger = addUser("영희");
        audit.start();
        auditLogger().addAppender(audit);
    }

    @AfterEach
    void tearDown() {
        auditLogger().detachAppender(audit);
    }

    @Test
    void 방_생성은_커밋_후_요청_추적_ID와_함께_기록된다() throws Exception {
        String requestId = mvc.perform(post("/api/rooms").header("X-User-Id", owner)
                        .contentType("application/json").content("{\"name\":\"잡담방\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader(RequestLogContextFilter.HEADER);

        assertThat(audit.list).hasSize(1);
        ILoggingEvent entry = audit.list.getFirst();
        assertThat(fields(entry)).containsEntry("action", "ROOM_CREATED").containsEntry("outcome", "SUCCESS")
                .containsEntry("userId", owner).containsEntry("roomId", roomId("잡담방"));
        assertThat(entry.getMDCPropertyMap()).containsEntry(RequestLogContextFilter.REQUEST_ID, requestId)
                .containsEntry(RequestLogContextFilter.USER_ID, String.valueOf(owner))
                .containsKey(RequestLogContextFilter.CLIENT_IP);
    }

    @Test
    void 롤백된_성공_이벤트는_기록하지_않는다() {
        tx.executeWithoutResult(status -> {
            publisher.publishEvent(new RoomCreatedEvent(1, owner, Instant.now()));
            status.setRollbackOnly();
        });

        assertThat(audit.list).isEmpty();
    }

    @Test
    void 입장과_나가기가_기록된다() throws Exception {
        long roomId = createRoom("잡담방");
        audit.list.clear();

        mvc.perform(post("/api/rooms/{id}/members", roomId).header("X-User-Id", stranger)).andExpect(status().isCreated());
        mvc.perform(delete("/api/rooms/{id}/members/me", roomId).header("X-User-Id", stranger)).andExpect(status().isOk());

        assertThat(audit.list).extracting(e -> fields(e).get("action")).containsExactly("MEMBER_JOINED", "MEMBER_LEFT");
    }

    @Test
    void 비멤버_전송은_롤백되어도_거부가_기록된다() throws Exception {
        long roomId = createRoom("잡담방");
        audit.list.clear();

        mvc.perform(post("/api/rooms/{id}/messages", roomId).header("X-User-Id", stranger)
                        .contentType("application/json").content("{\"content\":\"안녕\"}"))
                .andExpect(status().isForbidden());

        assertThat(audit.list).hasSize(1);
        assertThat(fields(audit.list.getFirst())).containsEntry("action", "ACCESS_DENIED")
                .containsEntry("outcome", "FAILURE").containsEntry("code", "NOT_A_MEMBER")
                .containsEntry("userId", stranger).containsEntry("roomId", roomId);
    }

    @Test
    void 트랜잭션_없는_비멤버_조회도_거부가_기록된다() throws Exception {
        long roomId = createRoom("잡담방");
        audit.list.clear();

        mvc.perform(get("/api/rooms/{id}/messages", roomId).header("X-User-Id", stranger))
                .andExpect(status().isForbidden());

        assertThat(audit.list).extracting(e -> fields(e).get("action")).containsExactly("ACCESS_DENIED");
    }

    @Test
    void 인증_실패는_경로와_앞_64자까지의_헤더값이_기록된다() throws Exception {
        mvc.perform(get("/api/rooms").header("X-User-Id", "x".repeat(100))).andExpect(status().isUnauthorized());

        assertThat(audit.list).hasSize(1);
        assertThat(fields(audit.list.getFirst())).containsEntry("action", "AUTHENTICATION_FAILED")
                .containsEntry("outcome", "FAILURE").containsEntry("path", "/api/rooms")
                .containsEntry("credential", "x".repeat(64));
    }

    private static Logger auditLogger() {
        return (Logger) LoggerFactory.getLogger("AUDIT");
    }

    private static Map<String, Object> fields(ILoggingEvent event) {
        Map<String, Object> fields = new HashMap<>();
        List.copyOf(event.getKeyValuePairs()).forEach(pair -> fields.put(pair.key, pair.value));
        return fields;
    }

    private long addUser(String nickname) {
        var key = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO users (nickname, created_at) VALUES (:name, :at)")
                .param("name", nickname).param("at", LocalDateTime.parse("2026-10-07T01:02:03"))
                .update(key, "id");
        return key.getKey().longValue();
    }

    private long createRoom(String name) throws Exception {
        mvc.perform(post("/api/rooms").header("X-User-Id", owner)
                        .contentType("application/json").content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated());
        return roomId(name);
    }

    private long roomId(String name) {
        return jdbc.sql("SELECT id FROM rooms WHERE name = :name").param("name", name).query(Long.class).single();
    }
}
```

- [ ] **Step 2: 실패 확인**
Run: `./gradlew test --tests 'jissuo.chat.audit.AuditLogTest'` → FAIL (`audit.list`가 비어 있음. `롤백된_성공_이벤트는_기록하지_않는다`만 통과)

- [ ] **Step 3: 리스너 구현**

```java
package jissuo.chat.audit;

import jissuo.chat.auth.AuthenticationFailedEvent;
import jissuo.chat.room.domain.AccessDeniedEvent;
import jissuo.chat.room.domain.MemberJoinedEvent;
import jissuo.chat.room.domain.MemberLeftEvent;
import jissuo.chat.room.domain.RoomCreatedEvent;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * ADR-023: 감사 로그는 일반 로그와 보관 기간·접근 권한을 나누기 위해 전용 로거(AUDIT → audit.json)로 쓴다.
 * 요청 추적 ID와 IP는 RequestLogContextFilter가 MDC에 넣어 두므로 여기서 다시 넣지 않는다.
 */
@Component
class AuditListener {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");
    // 계획 3 세부 #7: 인증 실패 헤더는 공격자가 고르는 값이라 로그가 커지지 않게 자른다
    static final int CREDENTIAL_MAX = 64;

    // ADR-023: 롤백된 성공이 기록되지 않도록 커밋 후에만 받는다
    @TransactionalEventListener
    void on(RoomCreatedEvent e) {
        success("ROOM_CREATED", e.userId(), e.roomId(), e.at());
    }

    @TransactionalEventListener
    void on(MemberJoinedEvent e) {
        success("MEMBER_JOINED", e.userId(), e.roomId(), e.at());
    }

    @TransactionalEventListener
    void on(MemberLeftEvent e) {
        success("MEMBER_LEFT", e.userId(), e.roomId(), e.at());
    }

    // 계획 3 세부 #2: 거부는 롤백과 무관하게 일어난 일이다. 트랜잭션이 끝난 뒤(롤백 포함) 기록하고,
    // 트랜잭션이 없는 조회에서는 바로 기록한다. 트랜잭션 안에서는 이 리스너의 예외가 403 응답을 바꾸지 않는다
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION, fallbackExecution = true)
    void on(AccessDeniedEvent e) {
        AUDIT.atInfo().setMessage("audit")
                .addKeyValue("action", "ACCESS_DENIED")
                .addKeyValue("outcome", "FAILURE")
                .addKeyValue("userId", e.userId())
                .addKeyValue("roomId", e.roomId())
                .addKeyValue("code", e.code())
                .addKeyValue("occurredAt", e.at().toString())
                .log();
    }

    // 필터에서 발행되어 트랜잭션이 없으므로 fallbackExecution으로 바로 실행된다
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION, fallbackExecution = true)
    void on(AuthenticationFailedEvent e) {
        AUDIT.atInfo().setMessage("audit")
                .addKeyValue("action", "AUTHENTICATION_FAILED")
                .addKeyValue("outcome", "FAILURE")
                .addKeyValue("path", e.path())
                .addKeyValue("credential", truncate(e.credential()))
                .addKeyValue("occurredAt", e.at().toString())
                .log();
    }

    private static void success(String action, long userId, long roomId, Instant at) {
        AUDIT.atInfo().setMessage("audit")
                .addKeyValue("action", action)
                .addKeyValue("outcome", "SUCCESS")
                .addKeyValue("userId", userId)
                .addKeyValue("roomId", roomId)
                .addKeyValue("occurredAt", at.toString())
                .log();
    }

    private static String truncate(String credential) {
        if (credential == null || credential.codePointCount(0, credential.length()) <= CREDENTIAL_MAX) {
            return credential;
        }
        return credential.substring(0, credential.offsetByCodePoints(0, CREDENTIAL_MAX));
    }
}
```

- [ ] **Step 4: 통과 확인**
Run: `./gradlew test --tests 'jissuo.chat.audit.AuditLogTest'` → PASS

- [ ] **Step 5: ArchUnit 규칙 추가**
`ArchitectureTest`에 추가한다:
```java
    // 서비스는 이벤트를 발행할 뿐 감사를 모른다 (ADR-023). 감사를 바꿔도 기능 코드가 바뀌지 않는다
    @ArchTest
    static final ArchRule nothing_depends_on_audit = noClasses()
            .that().resideOutsideOfPackage("..audit..")
            .should().dependOnClassesThat().resideInAPackage("..audit..");
```
Run: `./gradlew test --tests 'jissuo.chat.ArchitectureTest'` → PASS

- [ ] **Step 6: 파일 형식 수동 확인**
`bootRun`(local,mysql) 후 방 생성과 헤더 없는 요청을 curl로 보내고 `tail -2 backend/logs/audit.json`을 본다.
- 기대: 한 줄 JSON에 `action`, `outcome`, `userId`, `roomId`, `requestId`, `clientIp`가 있다. `app.json`에는 이 줄이 없다.
- key-value 필드가 JSON에 없으면 Boot ECS 형식이 key-value를 내보내지 않는 것이다. 이 경우 필드를 MDC로 옮기는 방법을 제안하고 멈춘다 (예상과 다른 결과를 그대로 보고).
- 전체: `./gradlew test` → PASS

---

## 작업 5. 메트릭 모니터링 (Prometheus + Grafana)

**Files:**
- Create: `infra/compose.monitoring.yml` (이 작업에서는 `metrics` profile만)
- Create: `infra/prometheus/prometheus.yml`, `infra/grafana/provisioning/datasources/prometheus.yml`, `infra/grafana/provisioning/dashboards/provider.yml`, `infra/grafana/dashboards/chat-step1.json`

- [ ] **Step 1: 이미지 버전 고정**
Docker Hub에서 `prom/prometheus`와 `grafana/grafana`의 최신 안정 패치 태그를 확인해 고정한다. 확인 날짜와 태그를 작업 보고와 작업 7의 ADR에 적는다 (ADR-030: 측정 중 버전이 바뀌면 결과를 비교할 수 없다).

- [ ] **Step 2: Compose**
```yaml
# 필요할 때만 켠다 (ADR-021). 부하 측정 중에는 metrics만 켜서 DB와의 메모리 경쟁을 줄인다 (계획 3 세부 #11)
# 메트릭: docker compose -f infra/compose.monitoring.yml --profile metrics up -d
# 로그:   docker compose -f infra/compose.monitoring.yml --profile logs up -d
name: chat-monitoring

services:
  prometheus:
    image: prom/prometheus:<Step 1에서 고정한 태그>
    profiles: [metrics]
    ports:
      - "19090:9090"
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - prometheus-data:/prometheus
    # 앱은 compose 밖(bootRun)에서 돈다. Linux에서도 host.docker.internal이 풀리게 한다
    extra_hosts:
      - "host.docker.internal:host-gateway"

  grafana:
    image: grafana/grafana:<Step 1에서 고정한 태그>
    profiles: [metrics]
    ports:
      - "13000:3000"
    environment:
      # 계획 3 세부 #14: 로컬 전용이라 로그인 없이 연다
      GF_AUTH_ANONYMOUS_ENABLED: "true"
      GF_AUTH_ANONYMOUS_ORG_ROLE: Admin
    volumes:
      - ./grafana/provisioning:/etc/grafana/provisioning:ro
      - ./grafana/dashboards:/var/lib/grafana/dashboards:ro
    depends_on:
      - prometheus

volumes:
  prometheus-data:
```
(`<…>` 자리는 Step 1에서 확인한 실제 태그로 채운다)

- [ ] **Step 3: Prometheus 설정** `infra/prometheus/prometheus.yml`
```yaml
global:
  # 계획 3 세부 #12: 실험이 수십 초 단위라 기본 15초보다 촘촘하게 모은다
  scrape_interval: 5s
scrape_configs:
  - job_name: chat
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["host.docker.internal:8080"]
```

- [ ] **Step 4: Grafana 자동 등록**
`infra/grafana/provisioning/datasources/prometheus.yml`:
```yaml
apiVersion: 1
datasources:
  - name: Prometheus
    uid: prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
```
`infra/grafana/provisioning/dashboards/provider.yml`:
```yaml
apiVersion: 1
providers:
  - name: chat
    type: file
    options:
      path: /var/lib/grafana/dashboards
```
`infra/grafana/dashboards/chat-step1.json`: 아래 7개 패널(`timeseries`, 데이터 소스 uid `prometheus`, 범례에 `{{db}} {{schema}}`를 붙인다), `refresh: "5s"`, 기본 기간 최근 15분. `/actuator` 요청은 모든 식에서 `uri!~"/actuator.*"`로 뺀다.

| 패널 | 식 | 단위 | 쓰는 실험 |
|---|---|---|---|
| 초당 요청 수 (URI별) | `sum by (method, uri, db, schema) (rate(http_server_requests_seconds_count{application="chat"}[$__rate_interval]))` | req/s | 전체 |
| p99 응답 시간 (URI별) | `histogram_quantile(0.99, sum by (le, method, uri, db, schema) (rate(http_server_requests_seconds_bucket{application="chat"}[$__rate_interval])))` | s | DB 비교, F15/F16 |
| p50/p95/p99 (전체) | 위 식에서 `by (le, db, schema)`, 0.5·0.95·0.99 세 개 | s | DB 비교 |
| 에러율 4xx·5xx | `sum by (db, schema) (rate(http_server_requests_seconds_count{application="chat",status=~"5.."}[$__rate_interval])) / sum by (db, schema) (rate(http_server_requests_seconds_count{application="chat"}[$__rate_interval]))` (4xx는 `"4.."`) | 0~1 | F1 |
| HikariCP 커넥션 | `hikaricp_connections_active`, `hikaricp_connections_idle`, `hikaricp_connections_pending` (`{application="chat"}`) | 개 | F1 |
| 커넥션 대기와 타임아웃 | `hikaricp_connections_acquire_seconds_max`, `rate(hikaricp_connections_timeout_total[$__rate_interval])` | s, /s | F1 |
| Tomcat 바쁜 스레드 · JVM 힙 | `tomcat_threads_busy_threads`, `sum by (db) (jvm_memory_used_bytes{application="chat",area="heap"})` | 개, bytes | F1 |

JSON은 Grafana 화면에서 위 패널을 만든 뒤 "Export → Save to file"(데이터 소스를 uid `prometheus`로 고정, "Export for sharing externally" 끔)로 저장하고, 저장소의 파일을 그 결과로 바꾼다. 화면에서 만든 JSON이 원본이 되어 손으로 쓴 JSON의 오타를 피한다.

- [ ] **Step 5: 확인 (수동)**
1. `docker compose -f infra/compose.monitoring.yml --profile metrics up -d`
2. `bootRun`(local,mysql) 상태에서 `localhost:19090/targets`의 `chat`이 UP
3. 간단한 부하: `for i in $(seq 200); do curl -s -o /dev/null -H 'X-User-Id: 1' localhost:8080/api/rooms; done`
4. `localhost:13000`의 "chat Step 1" 대시보드에서 요청 수, p99, HikariCP 패널에 값이 보인다 (화면 캡처를 보고에 넣는다)
5. 앱을 `local,postgres`로 다시 띄우면 범례의 `db`가 `postgres`로 바뀐다

---

## 작업 6. 로그 모니터링 (Elasticsearch + Kibana + Filebeat)

**Files:**
- Modify: `infra/compose.monitoring.yml` (`logs` profile 추가)
- Create: `infra/filebeat/filebeat.yml`

- [ ] **Step 1: 이미지 버전 고정**
Elastic 공식 이미지(`docker.elastic.co/elasticsearch/elasticsearch`, `kibana`, `beats/filebeat`)의 최신 안정 패치를 확인한다. 세 이미지는 **같은 버전**이어야 한다. 날짜와 태그를 기록한다.

- [ ] **Step 2: Compose에 추가**
```yaml
  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:<Step 1 버전>
    profiles: [logs]
    ports:
      - "19200:9200"
    environment:
      # 계획 3 세부 #13: 로컬 학습용 단일 노드. 보안을 끄고 힙을 제한한다
      discovery.type: single-node
      xpack.security.enabled: "false"
      ES_JAVA_OPTS: -Xms512m -Xmx512m
    mem_limit: 1g
    volumes:
      - es-data:/usr/share/elasticsearch/data
    healthcheck:
      test: ["CMD-SHELL", "curl -fs localhost:9200/_cluster/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 30

  kibana:
    image: docker.elastic.co/kibana/kibana:<Step 1 버전>
    profiles: [logs]
    ports:
      - "15601:5601"
    environment:
      ELASTICSEARCH_HOSTS: http://elasticsearch:9200
    depends_on:
      elasticsearch:
        condition: service_healthy

  filebeat:
    image: docker.elastic.co/beats/filebeat:<Step 1 버전>
    profiles: [logs]
    user: root
    # 설정 파일이 바인드 마운트라 소유자 검사를 끈다
    command: ["filebeat", "-e", "--strict.perms=false"]
    volumes:
      - ./filebeat/filebeat.yml:/usr/share/filebeat/filebeat.yml:ro
      # 계획 3 세부 #5: bootRun은 backend/에서 돌아 로그가 backend/logs에 쌓인다
      - ../backend/logs:/logs:ro
      - filebeat-data:/usr/share/filebeat/data
    depends_on:
      elasticsearch:
        condition: service_healthy
```
`volumes:`에 `es-data:`, `filebeat-data:`를 추가한다.

- [ ] **Step 3: Filebeat 설정** `infra/filebeat/filebeat.yml`
```yaml
filebeat.inputs:
  - type: filestream
    id: app
    paths: ["/logs/app*.json"]
    parsers:
      - ndjson:
          target: ""
          overwrite_keys: true   # 앱이 찍은 @timestamp를 쓴다
          add_error_key: true
    fields:
      log_type: app
    fields_under_root: true
  - type: filestream
    id: audit
    paths: ["/logs/audit*.json"]
    parsers:
      - ndjson:
          target: ""
          overwrite_keys: true
          add_error_key: true
    fields:
      log_type: audit
    fields_under_root: true

output.elasticsearch:
  hosts: ["http://elasticsearch:9200"]
  # ADR-023, 계획 3 세부 #13: 감사 로그는 다른 색인에 둬서 보관 기간을 따로 걸 수 있게 한다
  indices:
    - index: "audit-%{+yyyy.MM.dd}"
      when.equals:
        log_type: audit
    - index: "app-%{+yyyy.MM.dd}"

# 색인 이름을 직접 정하려면 기본 템플릿과 보관 정책(데이터 스트림)을 꺼야 한다
setup.ilm.enabled: false
setup.template.enabled: false
```

- [ ] **Step 4: 확인 (수동)**
1. `docker compose -f infra/compose.monitoring.yml --profile logs up -d --wait`
2. `bootRun`(local,mysql) → 방 생성 1번, 헤더 없는 요청 1번
3. `curl -s 'localhost:19200/_cat/indices/app-*,audit-*?v'` → 두 색인이 있다
4. Kibana 데이터 뷰 등록:
   ```bash
   for p in app audit; do
     curl -s -X POST localhost:15601/api/data_views/data_view -H 'kbn-xsrf: true' -H 'Content-Type: application/json' \
       -d "{\"data_view\":{\"title\":\"$p-*\",\"name\":\"$p\",\"timeFieldName\":\"@timestamp\"}}"
   done
   ```
5. Kibana Discover에서 `audit`의 `action : "ROOM_CREATED"`가 검색되고, 그 줄의 `requestId`로 `app`을 검색하면 같은 요청의 일반 로그가 나온다 (화면 캡처를 보고에 넣는다)
6. 색인이 안 생기거나 `error.message`가 붙으면 결과를 그대로 보고하고 멈춘다

---

## 작업 7. 문서 반영
- [ ] `docs/adr/{진행한 날짜}.md`: ADR-062부터 이 계획의 세부 #1~#14 중 승인·확인된 것과 이미지 버전 고정을 기록한다. 측정·확인한 내용과 예상을 구분한다
- [ ] `docs/design/architecture.md` "공용 응답과 모니터링 구성": 추적 ID와 IP(필터, 신뢰 규칙), 접근 로그(필터 한 곳, 필드, 환경별 켜고 끄기)와 감사 로그(이벤트)를 나눈 이유, 로그 파일 위치와 보관, 감사 필드와 수신 시점(성공은 커밋 후, 실패는 트랜잭션이 끝난 뒤), Compose profile과 포트, 대시보드 패널 표를 반영한다. 모니터링 구성도의 "요청마다 추적 ID"를 구체화한다
- [ ] `docs/failure-lab.md`: 작업 중 발견한 위험을 가설로 추가한다 (상태 요약 표 포함). 지금 예상하는 후보:
  - F28 비멤버가 폴링을 계속하면 조회마다 `ACCESS_DENIED`가 기록되어 감사 로그가 폭증한다
  - F29 동기 파일 로그(콘솔+JSON 두 곳, 요청마다 접근 로그 한 줄)가 부하 측정 p99를 올린다. bench에서 `ACCESS`를 켰을 때와 껐을 때의 차이로 확인한다
  - F30 MDC는 스레드에 묶여 있어 Step 2의 비동기 전송에서 `requestId`가 사라진다
  - F31 히스토그램 버킷으로 계산한 p99는 근사값이라 k6가 잰 p99와 다를 수 있다
- [ ] `docs/README.md` 현재 상태(계획 3 완료, ADR 범위, 다음: 계획 4), `CLAUDE.md`의 "현재 위치"와 명령어(모니터링 compose, bench 프로필)
- [ ] `docs/journal/{진행한 날짜}.md`: 한 일, 예상과 다르게 나온 것(Boot 4 이름, ECS key-value, 상관 ID 패턴 등), 남은 것

---

## 확인 방법 (끝까지)
1. `cd backend && ./gradlew test`: 기존 테스트와 `observe.*`, `common.RequestLogContextFilterTest`, `common.ClientIpTest`, `audit.AuditLogTest`, ArchUnit 새 규칙이 모두 통과한다
2. `bootRun`(local,mysql) + curl: 응답 헤더 `X-Request-Id`, 콘솔 `[requestId]`, 요청마다 `access` 한 줄(`userId`, `status`, `durationMs`), `backend/logs/app.json`·`audit.json`의 ECS JSON
3. `--profile metrics`: Prometheus target UP, Grafana "chat Step 1"에 요청 수·p99·HikariCP 값
4. `--profile logs`: `app-*`, `audit-*` 색인, Kibana에서 `requestId`로 감사 로그와 일반 로그를 잇는다
5. `bench,mysql`로 띄우면 콘솔에 우리 코드 INFO 이상만 나오고, `prod,mysql`(DB 주소는 인자로 넘김)에서 `/actuator/loggers`가 404다

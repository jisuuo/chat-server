# 계획 2 작업 2 결과 보고서: seed

> 날짜: 2026-10-07 · 대상 커밋: `cd9bbd6` · 작업 2 변경사항은 미커밋

## 한 줄 결론

공통 seed SQL이 MySQL과 PostgreSQL의 스키마 A/B에서 정합성 규칙을 지켰고, API 및 compose 수동 확인에서도 기대한 결과가 나왔다.

## 환경

| 항목 | 값 |
|---|---|
| DB 이미지 | `mysql:8.4.11`, `postgres:18.6` |
| compose 자원 | 각 DB CPU 2, 메모리 1 GiB, 버퍼 256 MiB (ADR-040) |
| 자동 테스트 | Testcontainers, 같은 DB 이미지 |
| 앱 실행 | `./gradlew bootRun`, `local,mysql` 또는 `local,postgres`; 스키마 A/B, 입장 경계 id/time 지정 |

## 자동 테스트

| 조합 | seed 정합성 | 재입장 경계 | 방 목록 순서 |
|---|---|---|---|
| MySQL-A | PASS | PASS | PASS |
| MySQL-B | PASS | PASS | PASS |
| PostgreSQL-A | PASS | PASS | PASS |
| PostgreSQL-B | PASS | PASS | PASS |

`./gradlew test --tests 'jissuo.chat.sql.*'`: 12개 통과. `./gradlew test` 전체: 249개 통과, 실패·오류·건너뜀 0개.

## 세부 #1, #2 확인 (예상 → 실제)

| 세부 | 예상 | 실제 (측정) |
|---|---|---|
| #1 id를 지정하지 않는 seed | 두 DB에서 같은 SQL로 실행된다 | 같은 `db/seed/` 파일 3개가 두 DB에서 실행됐다. 사용자 id는 철수 1, 영희 2, 민수 3이고, 방 id는 잡담방 1, 스터디 2, 빈 방 3이었다. |
| #2 V1을 직접 실행한 뒤 앱 기동 | Flyway 오류로 기동 실패 | 두 DB 모두 실패했다. MySQL: ``Found non-empty schema(s) `chat` but no schema history table. Use baseline() or set baselineOnMigrate to true to initialize the schema history table.`` PostgreSQL: `Found non-empty schema(s) "public" but no schema history table. Use baseline() or set baselineOnMigrate to true to initialize the schema history table.` |

## 수동 확인 (compose + curl)

각 조합 전에 `docker compose down -v`와 `up -d --wait`로 초기화했다. 앱을 띄운 뒤 공통 seed 파일을 DB 클라이언트로 넣고, 작업 2 Step 7의 요청 1~8을 `curl`로 확인했다. 마지막 열은 검사 쿼리의 I1~I7이 모두 0인지 나타낸다.

| 조합 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | check_invariants |
|---|---|---|---|---|---|---|---|---|---|
| MySQL-A, id | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS |
| MySQL-B, id | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS |
| PostgreSQL-A, id | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS |
| PostgreSQL-B, id | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS |
| MySQL-A, time | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS |
| PostgreSQL-A, time | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS | PASS |

## 두 DB의 차이와 이상한 점

- MySQL에서 원래 I6 검사식 `INSTR(content, CHAR(0))`은 한글이 든 정상 메시지 7건을 모두 위반으로 잘못 셌다. `INSTR('잡담 1', CHAR(0))`을 직접 실행해 1을 확인했다. `CAST(content AS BINARY)`와 `X'00'`의 바이트 비교로 바꾼 뒤 0이 됐고, 실제 NUL을 섞은 문자열은 탐지됐다. PostgreSQL은 NUL 문자열을 저장할 수 없어 기존 계획대로 메시지 NUL 검사를 생략한다 (F25).
- 스키마를 직접 만든 뒤 앱을 띄우는 방식은 두 DB 모두 Flyway 이력 테이블 부재로 실패했다. compose에서 앱의 Flyway로 스키마를 먼저 만든 뒤 seed를 넣는 순서가 필요하다.

## 다음 작업에 미치는 영향

- 작업 3의 bulk SQL은 같은 컨테이너 클라이언트와 정합성 검사 쿼리를 사용할 수 있다.
- MySQL bulk의 한글 메시지 내용도 I6에서 바이트 기준 NUL 검사로 처리된다.

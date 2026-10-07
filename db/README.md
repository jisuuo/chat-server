# 테스트용 SQL

앱 없이 DB 클라이언트만으로 실행할 수 있는 SQL이다. 테이블은 DB별 Flyway 스크립트 `backend/src/main/resources/db/migration/{mysql,postgresql}/V1__init.sql`로 만든다.

| 폴더 | 용도 |
|---|---|
| `seed/` | 화면 확인용 소량 데이터. 두 DB 공용 |
| `bulk/{mysql,postgresql}/` | 부하 측정용 대량 데이터. 인기 방 쏠림 50/30/20 |
| `queries/{mysql,postgresql}/` | 정합성, 분포, 실행 계획, 크기, 캐시 통계 확인 |

## 순서

항상 빈 DB에서 시작한다. 스키마 A/B를 바꾸거나 데이터를 다시 넣을 때도 볼륨을 초기화한다 (ADR-041).

1. 저장소 루트에서 `docker compose -f infra/compose.db.yml down -v`와 `docker compose -f infra/compose.db.yml up -d --wait`를 실행한다.
2. `backend/`에서 `./gradlew bootRun --args='--spring.profiles.active=local,mysql --chat.message-schema=A'`로 앱을 한 번 띄워 Flyway가 테이블을 만들게 한다. PostgreSQL이면 `local,postgres`를 쓴다. 기동을 확인한 뒤 종료해도 된다.
3. `base.sql` 다음에 `messages_a.sql` 또는 `messages_b.sql`을 넣는다. 앱을 실행할 때의 `chat.message-schema`와 같은 것을 고른다.
4. `queries/*/check_invariants.sql`의 I1~I7 위반 건수가 모두 0인지 확인한다. bulk는 `check_distribution.sql`도 실행한다.

테이블을 직접 만든 뒤 앱을 띄우면 Flyway 이력 테이블이 없어 기동에 실패한다. 두 DB에서 확인했다 ([seed 결과 보고서](../docs/reports/2026-10-07-plan2-task2-seed.md)).

## 실행 예

저장소 루트에서 실행한다. compose 접속 정보는 MySQL `localhost:13306`, PostgreSQL `localhost:15432`, 계정과 DB는 `chat/chat`, `chat`이다.

### seed

```bash
# MySQL
docker compose -f infra/compose.db.yml exec -T mysql mysql --default-character-set=utf8mb4 -uchat -pchat chat < db/seed/base.sql
docker compose -f infra/compose.db.yml exec -T mysql mysql --default-character-set=utf8mb4 -uchat -pchat chat < db/seed/messages_a.sql

# PostgreSQL
docker compose -f infra/compose.db.yml exec -T postgres psql -U chat -d chat -v ON_ERROR_STOP=1 < db/seed/base.sql
docker compose -f infra/compose.db.yml exec -T postgres psql -U chat -d chat -v ON_ERROR_STOP=1 < db/seed/messages_a.sql
```

### bulk

seed와 별도의 빈 DB에서 실행한다.

```bash
# MySQL
docker compose -f infra/compose.db.yml exec -T mysql mysql --default-character-set=utf8mb4 -uchat -pchat chat < db/bulk/mysql/base.sql
docker compose -f infra/compose.db.yml exec -T mysql mysql --default-character-set=utf8mb4 -uchat -pchat --init-command="SET @messages=500000" chat < db/bulk/mysql/messages_a.sql

# PostgreSQL
docker compose -f infra/compose.db.yml exec -T postgres psql -U chat -d chat -v ON_ERROR_STOP=1 < db/bulk/postgresql/base.sql
docker compose -f infra/compose.db.yml exec -T postgres psql -U chat -d chat -v ON_ERROR_STOP=1 -v messages=500000 < db/bulk/postgresql/messages_a.sql
```

메시지 수는 반드시 넘긴다. 스키마 B를 쓰면 `messages_b.sql`을 실행한다.

```bash
# MySQL 확인 예
docker compose -f infra/compose.db.yml exec -T mysql mysql --default-character-set=utf8mb4 -uchat -pchat -t chat < db/queries/mysql/check_invariants.sql
docker compose -f infra/compose.db.yml exec -T mysql mysql --default-character-set=utf8mb4 -uchat -pchat -t chat < db/queries/mysql/check_distribution.sql
docker compose -f infra/compose.db.yml exec -T mysql mysql --default-character-set=utf8mb4 -uchat -pchat -t chat < db/queries/mysql/sizes.sql

# PostgreSQL 확인 예
docker compose -f infra/compose.db.yml exec -T postgres psql -U chat -d chat -v ON_ERROR_STOP=1 < db/queries/postgresql/check_invariants.sql
docker compose -f infra/compose.db.yml exec -T postgres psql -U chat -d chat -v ON_ERROR_STOP=1 < db/queries/postgresql/check_distribution.sql
docker compose -f infra/compose.db.yml exec -T postgres psql -U chat -d chat -v ON_ERROR_STOP=1 < db/queries/postgresql/sizes.sql
```

`explain_a.sql`·`explain_b.sql`은 앱의 메시지 조회와 방 목록 쿼리 실행 계획을 보여 준다. `cache_hit.sql`은 누적 통계를 보여 주므로 특정 측정 구간의 적중률은 실행 전후 차이로 계산한다.

## 앱이 없는 환경

DB 클라이언트로 해당 DB의 `V1__init.sql`을 먼저 실행하고 위 순서대로 데이터를 넣는다. 이 DB에 나중에 앱을 붙이면 Flyway 이력이 없어 기동이 실패한다.

```bash
docker compose -f infra/compose.db.yml exec -T mysql mysql --default-character-set=utf8mb4 -uchat -pchat chat < backend/src/main/resources/db/migration/mysql/V1__init.sql
docker compose -f infra/compose.db.yml exec -T postgres psql -U chat -d chat -v ON_ERROR_STOP=1 < backend/src/main/resources/db/migration/postgresql/V1__init.sql
```

## 데이터 형태

bulk는 사용자 10,000명과 방 10,000개를 만든다. 방 id 1~100은 인기(멤버 50, 메시지 50%), 101~1000은 중간(멤버 20, 메시지 30%), 1001~10000은 나머지(멤버 5, 메시지 20%)다. 난수를 쓰지 않아 두 DB가 같은 데이터를 만든다.

확정한 크기는 500,000건(세 조합 모두 256MB 버퍼 안)과 5,000,000건(모두 버퍼 밖)이다. MySQL bulk SQL의 최대치는 9,999,999건이다. 크기 측정값은 [일지](../docs/journal/2026-10-07.md)에 기록했다.

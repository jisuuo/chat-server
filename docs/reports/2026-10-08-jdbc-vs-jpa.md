# 계획 6 JDBC·JPA 비교 (기능 검증 완료, 성능 측정 연기)

## 조건과 범위

- 2026-10-08, Java 21, Spring Boot 4.1.1, MySQL 8.4.11·PostgreSQL 18.6(Testcontainers).
- `chat.repository=jdbc`가 기본값이다. JPA 메시지는 스키마 A만 지원하며 ID·time 입장 경계 조회를 구현했다. `jpa+B`는 기동 시 거절한다.
- 2026-10-08 사용자 요청에 따라 W1~W5 부하, 기동 시간, 힙 비교는 **나머지 계획이 끝난 뒤** 실행한다(ADR-128). JDBC·JPA 기본값 및 JDBC 구현 삭제 여부는 그 측정 뒤 결정한다.
- 벤치 실행을 시작했다가 중단했다. `load/results/plan6-mysql-a-500k-jdbc/`의 W2 두 반복은 불완전한 예비 자료이며 아래 비교나 결론에 포함하지 않는다. 격리된 `chat-bench` 컨테이너는 정지했고 볼륨은 보존했다.

## 정확성: 같은 계약

| 대상 | MySQL JDBC | PostgreSQL JDBC | MySQL JPA | PostgreSQL JPA | 관찰 |
|---|---|---|---|---|---|
| User 저장소 | 통과 | 통과 | 통과 | 통과 | ID·UTC 시각·닉네임 |
| Room 저장소 | 통과 | 통과 | 통과 | 통과 | 목록 커서·조건부 마지막 번호 갱신 |
| Membership 저장소 | 통과 | 통과 | 통과 | 통과 | 중복 입장·FK·삭제·재입장 |
| Message 저장소 | 스키마 A/B 통과 | 스키마 A/B 통과 | 스키마 A 통과 | 스키마 A 통과 | 최신·이전·이후, ID·time 경계, 마이크로초 정밀도 |
| User·Room·Message API | 통과 | 통과 | 통과 | 통과 | 같은 MockMvc 계약 |

전체 `cd backend && ./gradlew test`는 **373건 통과, 실패 0건**이다. `ArchitectureTest`도 포함한다. 메시지 스키마 B와 JPA를 함께 설정하면 기동이 실패하는 검증도 통과했다. JPA 엔티티와 Flyway 스키마 A의 `ddl-auto=validate` 기동이 두 DB에서 통과했다.

### 중복 입장 장애(F34)

처음의 JPA `MembershipEntity`는 복합 키가 생성 때부터 있어서 Spring Data JPA `save()`가 `merge`를 골랐다. 두 번째 입장에서 기존 행을 SELECT한 뒤 입장 경계를 UPDATE했고, 두 DB에서 `ALREADY_MEMBER` 계약이 각각 실패했다. `Persistable.isNew()`로 새 엔티티를 표시하고 `saveAndFlush()`로 INSERT 제약을 즉시 확인하도록 수정했다. PostgreSQL SQLSTATE `23505` 또는 MySQL 오류 코드 `1062`를 중복 키로 분류한다. 수정 후 두 DB의 저장소·API 계약에서 409 `ALREADY_MEMBER`와 없는 사용자 401 `UNAUTHENTICATED`가 통과했다(ADR-127). 오류를 커밋 때까지 미루는 원래 `save()` 경로의 API 실패 여부는 별도로 실행하지 않았다.

## 코드량과 쿼리

| 지표 | JDBC | JPA | 차이·한계 |
|---|---:|---:|---|
| `infra/jdbc/*.java`, `infra/jpa/*.java` 현재 전체 행 (`wc -l`) | 335 | 516 | JPA +181행(+54%). 엔티티·ID·설정 클래스 포함. JDBC에는 스키마 B 지원도 포함되어 완전히 동일한 기능 범위는 아니다 |
| 직접 쓴 SQL/JPQL | `jdbc.sql(` 13곳 | `@Query` 5곳 | JPA에는 추가로 파생 쿼리 메서드 6개가 있다 |
| JDBC `ResultSet` 변환 메서드 | 3곳 | 해당 없음 | JPA도 도메인 변환 메서드는 필요하다 |

JPA 의존성 추가 전 JDBC 기준선은 329행이었다. 구현 선택용 조건 어노테이션 등을 추가한 현재 JDBC 코드는 335행이다. 코드량 감소 예상은 **맞지 않았다**. 단순 CRUD의 SQL 문자열은 사라졌지만, 엔티티 매핑·어댑터·커서별 메서드와 멤버십 예외 처리가 늘었다.

## 요청당 SQL 실행 횟수

`@Tag("experiment")`의 `SqlCountExperiment`가 같은 `DataSource` 프록시에서 `prepareStatement`·`createStatement` 호출을 센다. `./gradlew experimentTest --tests '*SqlCountExperiment*'`의 MySQL·PostgreSQL × JDBC·JPA 네 조합이 통과했다. 아래 값은 두 DB에서 같았다.

| 요청 | JDBC | JPA | 차이 |
|---|---:|---:|---:|
| 방 만들기 | 2 | 2 | 0 |
| 입장 | 2 | 2 | 0 |
| 메시지 전송 | 3 | 3 | 0 |
| 최신 메시지 조회 | 2 | 2 | 0 |
| `after` 폴링 | 2 | 2 | 0 |
| 방 목록 | 1 | 1 | 0 |

이 실험은 준비 단계의 SQL을 제외하고 성공 요청 하나에서 준비한 문장 수를 센다. 서버 처리 시간, 네트워크 왕복, 행 수, SQL 실행 계획은 포함하지 않는다. `IDENTITY` 배치 가능성이나 p99 성능의 증거로 해석하지 않는다.

## 성능·기동 비교: 측정하지 않음

| 항목 | 상태와 이유 |
|---|---|
| W1~W5 p99·RPS·오류율(F21) | 사용자 요청에 따라 나머지 계획 완료 후 측정. 중단한 W2 예비 두 반복은 제외 |
| JDBC/JPA 기동 시간 | JPA 의존성 추가 전의 비교 가능한 JDBC 기동 로그가 없다. 동일 조건 재측정은 연기 |
| JVM 힙 | 동일 부하·동일 시점의 두 구현 측정은 연기 |
| Hibernate/JPA 기동 영향 | JDBC 기본 경로에서 `HibernateJpaAutoConfiguration`과 JPA 트랜잭션 관리자가 활성화된 것은 확인했다. JPA 의존성 추가 후 JDBC 경로의 한 번의 기동은 3.333초였으나 비교 기준값은 아니다 |

따라서 현재 확인된 **나아진 점**은 단순 저장·조회에서 직접 쓴 쿼리 문자열이 줄고, 엔티티 매핑과 Flyway 스키마의 차이를 기동 때 검사할 수 있게 된 것이다. 검사는 JPA 의존성이 활성화된 **JDBC 기본 경로에서도** 실행되므로 JPA 저장소만의 이점은 아니다. **나빠진 점**은 현재 비교에서 구현 코드가 181행 늘고, 할당 복합 키의 `merge`가 중복 입장 계약을 깨서 별도 수명 주기·예외 처리가 필요해진 것이다. SQL 문장 수는 같았다. 처리량·지연·메모리의 우열은 아직 판단하지 않는다.

## 나중에 재개할 때

`infra/compose.bench.yml`은 `BENCH_REPOSITORY`와 `BENCH_JAR`를 받는다. 새 구현 JAR은 `load/artifacts/chat-bench-plan6.jar`에 별도로 저장했고, 계획 5b의 기존 `chat-bench.jar`는 보존했다. 성능 비교 때는 같은 JAR·DB·스키마 A·50만 건 데이터·워밍업 15초·30초 3회를 사용하고 `BENCH_REPOSITORY`만 `jdbc`/`jpa`로 바꾼다. 정확한 재실행 방법은 `load/README.md`에 둔다. 데이터 누적, 실행 순서, Docker 자원 경합을 결과에 함께 기록한다.

# 계획 3 관측 구현 결과 보고서

작성일: 2026-10-07 · 브랜치: `plan3-observability` · 범위: 계획 3 작업 0~7

## 완료 범위

| 작업 | 구현·산출물 | 확인 결과 | 커밋 |
|---|---|---|---|
| 1 메트릭 | Actuator health·Prometheus, HTTP 히스토그램, `application/db/schema` 태그 | 테스트와 실제 Prometheus 출력에서 DB·스키마 태그, HikariCP·Tomcat·HTTP 버킷 확인 | `d563bfe` |
| 2 환경 | local/bench/prod 로그 레벨과 Actuator 노출 | 프로필별 테스트 통과, prod `loggers` 404 | `0b14e11` |
| 3 요청 로그 | 서버 생성 `X-Request-Id`, Tomcat IP 신뢰 규칙, MDC, `ACCESS` 로그, ECS 파일 | 실제 401·200 요청과 파일에서 ID·IP·상태·사용자 확인. `/actuator/**` 접근 로그 제외 | `22b9c62` |
| 4 감사 로그 | 성공 커밋 후·실패 트랜잭션 종료 뒤 수신, `audit.json`, 의존 방향 검사 | `ROOM_CREATED`, `ACCESS_DENIED`, `AUTHENTICATION_FAILED`의 필드와 `app.json` 분리 확인 | `fd39505` |
| 5 메트릭 스택 | Prometheus·Grafana Compose, 7개 패널 | target UP, 14개 PromQL 실행, MySQL·PostgreSQL 시계열 분리 확인 | `33e51d1` |
| 6 로그 스택 | Elasticsearch·Kibana·Filebeat Compose, app/audit 색인 | 방 생성 ID로 감사 1건과 일반 로그 20건 연결, 파싱 오류 0건, 데이터 뷰 2개 등록 | `8fe2de8` |
| 7 문서 | ADR-062~080, 아키텍처, F28~31, README·일지·이 보고서 | 문서 링크·설정·최종 테스트 점검 | 이 보고서와 함께 커밋 |

## 재현 방법

저장소 루트에서 DB와 필요한 모니터링 프로필을 띄운다. 백엔드는 `backend/`에서 실행한다.

```bash
docker compose -f infra/compose.db.yml up -d --wait
docker compose -f infra/compose.monitoring.yml --profile metrics up -d --wait
docker compose -f infra/compose.monitoring.yml --profile logs up -d --wait
cd backend
./gradlew test
./gradlew bootRun --args='--spring.profiles.active=local,mysql'
```

| 확인 대상 | 주소·파일 |
|---|---|
| 앱 health / 메트릭 | `http://localhost:8080/actuator/health`, `/actuator/prometheus` |
| Prometheus / Grafana | `http://localhost:19090/targets`, `http://localhost:13000`의 `chat Step 1` |
| Elasticsearch / Kibana | `http://localhost:19200/_cat/indices/app-*,audit-*?v`, `http://localhost:15601` |
| 일반 / 감사 JSON | `backend/logs/app.json`, `backend/logs/audit.json` |

로컬 Grafana는 익명 관리자, Elasticsearch는 보안이 꺼진 학습용 구성이다. 모니터링 이미지는 Prometheus `v3.14.0`, Grafana `13.2.3`, Elasticsearch·Kibana·Filebeat 공통 `9.5.4`로 고정했다. 확인 출처: [Prometheus 릴리스](https://github.com/prometheus/prometheus/releases/tag/v3.14.0), [Grafana 릴리스](https://github.com/grafana/grafana/releases/tag/v13.2.3), [Elastic 공식 이미지 목록](https://www.docker.elastic.co/).

## 실제 요청과 수집 결과

`local,mysql`에서 `POST /api/rooms`를 호출해 201과 `X-Request-Id: 8b363f03-d79e-4065-b5ce-f24df81156e6`를 받았다. 같은 ID로 `audit-*`의 `ROOM_CREATED` 1건(`roomId=4`, `userId=1`)과 `app-*`의 일반 로그 20건을 조회했다. 헤더 없는 `GET /api/rooms`는 401과 새 요청 ID를 반환했고 `AUTHENTICATION_FAILED`가 기록됐다. 두 색인의 `error.message` 필드가 있는 문서는 0건이었다. Kibana API에서 `app-*`, `audit-*` 데이터 뷰 등록을 확인했다.

Prometheus target은 UP이었다. Grafana API에서 7개 패널 자동 등록을 확인했고 14개 PromQL을 실행했다. 당시 요청 수·p99·HikariCP·Tomcat·JVM 관련 쿼리에는 값이 있었고, 요청에서 발생하지 않은 4xx/5xx 계열 쿼리는 빈 결과였다. PostgreSQL 프로필로 전환한 뒤 `db=postgres` 시계열을 확인했다. 이 결과는 연결과 쿼리 동작 확인이며 성능 비교값은 아니다.

## 구현 중 발견한 문제와 제한

- 전체 테스트에서 여러 Spring 컨텍스트의 기본 Hikari 유휴 연결이 MySQL 접속 수를 채워 `Too many connections`가 났다. 테스트 전용 `minimum-idle=0`을 적용한 뒤 전체 테스트를 통과했다. 운영 풀 크기는 변경하지 않았다.
- ECS 직렬화는 감사 이벤트의 `userId`가 MDC와 key-value에 중복되면 실패했다. MDC 한 곳에서 공급하도록 고친 뒤 실제 `audit.json`에서 필드를 확인했다.
- Elasticsearch 기본 디스크 임계값에서는 Docker 볼륨 여유 공간 약 2.3GB로 기본 샤드가 배치되지 않았다. 로컬 환경에 여유 공간 기준 1GB/750MB/500MB를 적용해 수집을 복구했다. 단일 노드의 복제 샤드가 배치되지 않아 색인 상태는 yellow이며 기본 샤드는 정상이다. 이 기준은 운영용이 아니다.
- Grafana와 Kibana의 브라우저 화면 캡처는 UI 자동화가 응답하지 않아 확보하지 못했다. Grafana API·Prometheus 쿼리와 Kibana 데이터 뷰 API·Elasticsearch 쿼리로 동작을 확인했다. Discover 화면 자체의 육안 검증은 남아 있다.
- F28~F31의 로그 폭증, 동기 로그 비용, 비동기 MDC 전파, p99 비교는 가설이다. 이번 작업에서 부하 수치나 비동기 동작은 측정하지 않았다.

## 최종 검증

`./gradlew test`: 성공 (`BUILD SUCCESSFUL`, 최종 실행은 4개 작업 up-to-date). 마지막 전체 실행의 JUnit XML에는 45개 suite, 테스트 283건, 실패·오류·건너뜀 0건이 기록됐다.

`docker compose -f infra/compose.monitoring.yml --profile metrics --profile logs config --quiet`: 성공.

# chat-server

HTTP 폴링으로 시작해 WebSocket, 다중 서버, Redis로 확장하는 채팅 서버 학습 프로젝트입니다. 기능 구현과 함께 장애 재현, 원인 분석, 성능 측정, 설계 결정을 기록합니다.

## 현재 구현

- Java 21, Spring Boot 4.1.1, Gradle 기반 REST API
- MySQL·PostgreSQL을 대상으로 한 JDBC·JPA 저장소 선택(`chat.repository`, 기본 JDBC)과 Flyway 마이그레이션
- React·TypeScript 채팅 화면: 사용자 선택, 방 목록·생성, 메시지 조회·전송, WebSocket 실시간 수신 (`?transport=polling` 비교 가능)
- Prometheus·Grafana 메트릭과 Elasticsearch·Kibana 로그 수집

현재 단계는 같은 MySQL을 쓰는 앱 2대와 nginx의 Step 3입니다. WebSocket 연결 설정(F8)과 재연결 시점 집중(F17)을 재현·보완했습니다. 다른 앱에 연결된 수신자에게는 즉시 push되지 않는 문제(F7)가 남아 있으며, 열린 방의 60초 재조회가 지연 표시할 뿐입니다. 서버 간 전달은 다음 Redis 단계에서 다룹니다. 메시지 스키마는 MySQL 스키마 A를 유지하고 DB 선택과 JDBC·JPA 성능 비교는 보류 중입니다. 조건과 측정값은 [계획 8 보고서](docs/reports/2026-10-08-plan8-multi-server.md), 결정 근거는 [문서 목록](docs/README.md)에 있습니다.

## 로컬 실행

Docker, Java 21, Node.js 22.22.2가 필요합니다. 저장소 루트에서 DB를 시작한 뒤 백엔드와 프론트엔드를 각각 실행합니다.

```bash
docker compose -f infra/compose.db.yml up -d --wait

cd backend
./gradlew bootRun --args='--spring.profiles.active=local,mysql'
```

다른 터미널에서:

```bash
cd frontend
npm ci
npm run dev
```

채팅 화면은 [http://localhost:5173](http://localhost:5173), 백엔드는 `http://localhost:8080`에서 열립니다. PostgreSQL로 실행하려면 백엔드 프로필의 `mysql`을 `postgres`로 바꿉니다.

서버 2대 구성을 보려면 먼저 jar와 프론트 정적 파일을 빌드합니다. 다음 명령은 저장소 루트에서 실행합니다.

```bash
(cd backend && ./gradlew bootJar)
(cd frontend && npm run build)
docker compose -f infra/compose.cluster.yml up -d --wait
```

클러스터 화면은 [http://localhost:18090](http://localhost:18090)입니다. app1·app2 직접 포트는 18081·18082이고 클러스터 MySQL은 33306입니다. 설정 교체와 로그 위치는 [클러스터 실행 안내](infra/cluster/README.md)를 따릅니다.

## Grafana

저장소 루트에서 메트릭 서비스를 시작합니다. 백엔드는 위 명령으로 별도 실행해야 합니다.

```bash
docker compose -f infra/compose.monitoring.yml --profile metrics up -d --wait
```

- **Grafana 주소:** [http://localhost:13000](http://localhost:13000)
- 대시보드: [chat Step 1](http://localhost:13000/d/chat-step1/chat-step-1) (자동 등록)
- Prometheus: [http://localhost:19090/targets](http://localhost:19090/targets)

로컬 Grafana는 로그인 없이 관리자 권한으로 접근할 수 있도록 설정돼 있습니다. 이 주소는 로컬 실행 환경의 주소입니다.

## 문서

- [설계와 작업 기록](docs/README.md)
- [협업 규칙](docs/collab-rules.md)
- [장애 실험 목록](docs/failure-lab.md)
- [아키텍처](docs/design/architecture.md)

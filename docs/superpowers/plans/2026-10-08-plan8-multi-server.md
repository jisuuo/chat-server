# 계획 8: 서버 2대와 nginx 구현 계획 (Step 3, 고도화 P8)

> **실행하는 에이전트에게**: 작업은 아래 "실행 순서와 병렬화"의 **웨이브 단위로 사용자 승인을 받고** 시작한다. 같은 웨이브의 작업은 동시에 진행할 수 있다. 웨이브가 끝나면 통합 확인을 하고 결과(테스트 출력 포함)를 보고한 뒤 멈춘다. **커밋하지 않는다** (사용자가 요청할 때만 한다). 단계는 체크박스(`- [ ]`)로 추적한다. 장애 재현 작업(3, 5, 6)은 끝나면 멈추고 측정값과 예상을 나눠 보고한다. 보완 작업(4, 7)은 7단계 제안을 승인받은 뒤에만 시작한다.

**목표:** 같은 MySQL을 쓰는 앱 2대를 nginx(라운드로빈) 뒤에 띄운다. 이 구성에서 다른 서버에 연결된 사용자가 메시지를 받지 못하는 문제(F7), 로드밸런서 뒤 WebSocket 연결 실패(F8), 재시작 때 재연결이 몰리는 문제(F17)를 재현한다. F8과 F17은 재현한 뒤 사용자와 결정해 보완한다. F7은 재현과 분석까지만 하고, 해결은 계획 9(Redis Pub/Sub)에서 한다.

**구조:** `infra/compose.cluster.yml` 하나로 `mysql`(별도 프로젝트·볼륨), `app1`, `app2`(빌드한 jar), `nginx`(빌드한 프론트 정적 파일 + `/api`·`/ws` 분배)를 띄운다. 앱 코드는 바꾸지 않는다. F7은 JVM 안에서 서버 컨텍스트 두 개로 결정적으로 재현하고, nginx 구성에서는 자연 분배로 재현한다. F8과 F17은 클러스터 구성을 대상으로 실험한다. 실험 코드는 `ProcessBuilder`로 `docker compose`를 불러 nginx 설정 교체와 앱 재시작을 한다.

**기술:** 계획 7과 같다(Java 21, Spring Boot 4.1.1, React 19.3.0, Vite 8.3.3, TypeScript 6.0.3, Vitest 5.0.3, Playwright 1.63.0). 새로 쓰는 것은 nginx 이미지 하나다. 태그는 작업 1에서 공식 stable 최신을 확인해 고정한다. 백엔드·프론트에 새 라이브러리를 추가하지 않는다.

## Context
- 고도화 개요(2026-10-07 승인): P6 UI → P7 WebSocket 1대 → **P8 서버 여러 대(F7)** → P9 Redis 사용자 채널 Pub/Sub. 이 계획은 P8이다.
- 지금 실시간 전달은 한 서버의 메모리 안에서만 일어난다. `MessageFanout`이 커밋 뒤 방 멤버 id를 조회하고, `WsMessagePusher.push`가 **자기 서버의** `WsSessionRegistry.sessionsOf(userId)`만 본다(`backend/src/main/java/jissuo/chat/message/api/ws/WsMessagePusher.java`). 다른 서버에 붙은 멤버는 조용히 건너뛴다. 로컬 수신자가 없어도 `DeliveryBatch`가 성공(`chat.delivery.total`)으로 기록한다.
- 서버 1대 상태를 전제로 한 다른 메모리 상태는 `WsHeartbeat.lastPong`, `WsOutboundQueue.queues`뿐이다. 둘 다 연결된 서버 안에서만 의미가 있어 여러 대에서도 그대로 맞다. id는 DB가 발급하고 요청 ID는 UUID라 서버끼리 충돌하지 않는다.
- 프론트는 끊기면 고정 1초 뒤 재연결한다(`frontend/src/realtime/chatSocket.ts`의 `RECONNECT_DELAY_MS`, ADR-137). 다시 연결되면 곧바로 복구 조회를 한다(ADR-145). 연결이 열린 동안에는 60초마다 재대조한다(ADR-146). 그래서 **브라우저에서는 F7이 "영구 누락"이 아니라 "최대 60초 지연"으로 보일 것**으로 예상한다. 테스트 클라이언트는 재대조를 하지 않으므로 누락으로 보인다.
- 서버 heartbeat는 10초마다 ping을 보내고 30초 동안 pong이 없으면 닫는다(ADR-144, `WsHeartbeat`, 상수). nginx 기본 `proxy_read_timeout`(60초)보다 짧으므로 유휴 끊김(F8의 두 번째 가설)은 일어나지 않을 것으로 예상한다.
- nginx 설정은 저장소에 아직 없다. Vite proxy(`frontend/vite.config.ts`)는 `localhost:8080` 한 대만 가리킨다.
- 이미 결정된 것 (그대로 지킨다): ADR-028(같은 origin, 운영은 nginx), ADR-034(장애 선행), ADR-040(DB 컨테이너 자원), ADR-022(bench 접근 로그 OFF), ADR-086(`X-Forwarded-For` 반영), ADR-128(W1~W5 부하 비교는 나머지 계획 뒤), ADR-130(같은 origin 핸드셰이크 인증, Spring 기본 origin 검사), ADR-137·144·145·146(재연결·heartbeat·복구·재대조).

### 사용자 결정 (2026-10-08, 계획 작성 대화)

| 항목 | 결정 |
|---|---|
| D1 범위 | 클러스터 구성 → F7·F8·F17 재현. F8·F17은 재현 뒤 7단계 형식으로 제안하고 승인받아 보완한다. F7은 재현·분석만 하고 해결은 계획 9(Redis)에서 한다 |
| D2 실행 방식 | docker compose로 nginx + 앱 2대 컨테이너를 띄운다(`compose.bench.yml` 방식처럼 빌드한 jar를 마운트한다). 재현 조건을 고정하고, 서버 하나만 멈추거나 재시작하기 쉽게 하기 위해서다 |
| D3 분배 방식 | 라운드로빈(nginx 기본값). sticky(`ip_hash`)는 같은 사용자를 고정할 뿐 F7을 막지 못한다. 로컬에서는 모든 탭이 한 서버로 몰려 재현이 가려진다 |
| D4 F17 규모 | Java 실험 클라이언트 1,000개(기존 `experiment/ws` 방식)로 재현한다. 폭주가 드러나지 않으면 사용자 승인을 받아 2,000개, 5,000개로 늘린다. k6 WebSocket 스크립트는 Step 6(ADR-128 이후)에서 만든다. **사용자 요청으로 ADR에 남긴다**(작업 0) |

## 지켜야 할 조건
- **장애 선행 (ADR-034)**: 처음 nginx 설정은 흔한 최소 설정(`proxy_pass`만, `Upgrade`·`Connection`·`Host` 전달 없음)으로 둔다. 재연결은 고정 1초 그대로 둔다. 서버 간 전달 코드(HTTP 중계, Redis, sticky)는 넣지 않는다. 작업 중 발견한 위험은 `docs/failure-lab.md`에 가설로만 적는다.
- **앱 코드(`backend/src/main`)는 바꾸지 않는다.** 바꿔야 할 것 같으면 멈추고 보고한다. 예외는 작업 7에서 승인받은 프론트 재연결 정책뿐이다.
- `./gradlew test`(ArchUnit 포함), `npx vitest run && npx tsc -b && npm run lint`, `npm run e2e`가 계속 통과해야 한다. 기존 Vite + `bootRun` 개발 흐름과 Playwright 설정은 그대로 둔다.
- 클러스터 실험은 `@Tag("experiment")`로 두어 `./gradlew test`에서 빠지게 한다. 클러스터가 떠 있지 않으면 `Assumptions`로 건너뛴다.
- 격리 수준은 각 DB 기본값이다. 결과를 적을 때 "예상"과 "측정"을 구분한다. 주석은 "왜"만 쓰고, 작업 중에는 `계획 8 세부 #n`으로 적었다가 작업 0의 ADR 번호로 바꾼다.

## 이 계획에서 새로 정하는 세부 (검토 필요. 승인되면 작업 0에서 ADR-147부터 기록)

| # | 항목 | 제안 | 이유 |
|---|---|---|---|
| 1 | 포트·프로젝트 | compose 프로젝트 이름 `chat-cluster`. nginx `18090`, app1 `18081`, app2 `18082`(직접 확인·지표용), MySQL `33306`(볼륨 `cluster-mysql-data`). 앱과 MySQL 자원은 ADR-040과 같다(앱마다 CPU 2·메모리 1g, `-XX:ActiveProcessorCount=2`). nginx는 CPU 1·메모리 256m | local(13306)·bench(23306·18080)와 겹치지 않는다. 실험이 local 데이터를 건드리지 않는다 |
| 2 | 앱 jar·프로필 | `./gradlew bootJar`의 결과 `backend/build/libs/chat-0.0.1-SNAPSHOT.jar`를 두 앱이 읽기 전용으로 마운트한다. 프로필은 `${CLUSTER_PROFILE:-local},mysql`이다. F17 측정만 `CLUSTER_PROFILE=bench`로 띄운다(ADR-022, 접근 로그 OFF). DB URL은 환경 변수로 덮어쓴다 | 두 서버가 같은 코드라는 것이 보장된다. 관찰할 때는 `WS_ACCESS` 로그를 보고, 측정할 때는 로그가 결과를 흔들지 않게 한다 |
| 3 | 로그 | 앱마다 `LOG_DIR=/app/logs`를 `backend/logs/cluster/app1`, `.../app2`에 마운트한다. Filebeat 수집 경로(`/logs/app*.json`)는 바꾸지 않는다(범위 밖) | 두 서버가 같은 `app.json`을 함께 쓰며 롤링이 충돌하는 것을 피한다 |
| 4 | 어느 서버로 갔는지 보기 | nginx 접근 로그 형식에 `$upstream_addr`·`$status`·`$request_time`을 넣는다. 응답 헤더 `X-Upstream: $upstream_addr`을 `always`로 붙인다. 앱 코드에 인스턴스 id는 넣지 않는다 | 앱을 바꾸지 않고 브라우저 개발자 도구와 실험 로그에서 분배를 확인한다. 101 응답에 `add_header ... always`가 붙는지는 작업 1에서 확인한다 |
| 5 | 프론트 제공 | `cd frontend && npm run build`의 `dist/`를 nginx `/usr/share/nginx/html`에 마운트한다. `try_files $uri /index.html` | ADR-028의 운영 구조. 화면은 `http://localhost:18090` |
| 6 | 지표 | `infra/prometheus/prometheus.yml`에 job `chat-cluster`(대상 `host.docker.internal:18081`, `:18082`)를 추가한다. 기존 `chat` job은 그대로 둔다 | Prometheus가 대상마다 `instance` 라벨을 붙이므로 서버별 세션 수·전달 지표를 나눠 볼 수 있다 |
| 7 | nginx 설정 교체 | `infra/cluster/nginx/${NGINX_CONF:-naive}.conf`를 `default.conf`로 마운트한다. 실험은 `NGINX_CONF=<이름> docker compose ... up -d --force-recreate --wait nginx`로 바꾼다. F8 보완 뒤 기본값을 승인된 설정으로 바꾼다 | 같은 compose 파일로 재현 조건과 보완 조건을 비교한다 |
| 8 | 실험 클라이언트의 Origin | 클러스터 실험의 WebSocket 클라이언트는 브라우저처럼 `Origin: http://localhost:18090` 헤더를 보낸다(`WsTestClient.connect(URI, WebSocketHttpHeaders)` 추가) | Java 클라이언트는 기본으로 Origin을 보내지 않아 Spring의 origin 검사(ADR-130)를 지나쳐 버린다. 브라우저에서만 나는 실패를 놓친다 |
| 9 | 실험 제어 | `experiment/cluster/ClusterControl`이 `docker compose -f ../infra/compose.cluster.yml`을 `ProcessBuilder`로 실행한다(`useNginx`, `restart`, `kill`, `start`, `logs`). 클러스터 건강 확인(`http://localhost:18090/api/users`가 401 또는 200)에 실패하면 `Assumptions.abort` | 측정 절차를 코드로 남겨 다시 돌릴 수 있게 한다 |
| 10 | F7 결정적 재현 | Testcontainers MySQL 하나에 `@SpringBootTest` 서버 1과 `SpringApplicationBuilder(ChatApplication.class)`로 띄운 서버 2를 붙인다(`server.port=0`, 같은 datasource) | nginx 없이 "A는 서버1, B는 서버2"를 확정할 수 있다. 기존 실험 패턴을 그대로 쓴다 |

## 예상되는 문제 (가설. 작업 0에서 `failure-lab.md`에 F51부터 기록. 미리 고치지 않는다)

| # | 가설 | 어디서 드러날지 |
|---|---|---|
| F7 (기존) | 저장한 서버에 붙지 않은 멤버는 push를 받지 못한다. 라운드로빈에서는 REST 전송 요청마다 저장 서버가 바뀌므로 각 수신자가 전체 메시지의 약 절반만 받는다(예상). 브라우저에서는 60초 재대조(ADR-146)로 최대 60초 늦게 보인다 | 작업 5 |
| F8 (기존) | 최소 설정의 nginx는 HTTP/1.0으로 프록시하고 `Upgrade`를 전달하지 않아 핸드셰이크가 실패한다(상태 코드는 측정). 브라우저는 1초마다 재연결을 끝없이 반복한다. 유휴 끊김(60초)은 서버 ping(10초) 때문에 일어나지 않을 것으로 예상한다 | 작업 3, 4 |
| F17 (기존) | 앱 1대를 재시작하면 그 서버의 연결(약 500개)이 1초 뒤 한꺼번에 재연결하고, 각자 복구 조회(ADR-145)를 한 번씩 보낸다. 핸드셰이크와 조회가 몰린다 | 작업 6 |
| F51 | `Upgrade`를 전달해도 nginx 기본 `Host`(`$proxy_host` = upstream 이름)가 넘어가면 Spring의 같은 origin 검사에서 403이 난다. `$host`로 바꿔도 포트가 빠져 `Origin: http://localhost:18090`과 맞지 않는다. `$http_host`나 `X-Forwarded-Host`가 필요하다(예상. `forward-headers-strategy: native`가 `X-Forwarded-Host`를 반영하는지 확인 필요) | 작업 4에서 설정을 하나씩 바꾸며 측정 |
| F52 | 최소 설정에서는 `X-Forwarded-For`가 없어 접근 로그의 `clientIp`가 nginx 컨테이너 주소가 된다(ADR-086 회귀) | 작업 3에서 앱 로그 확인 |
| F53 | 다른 서버의 멤버를 건너뛴 fan-out도 지표상 성공(`chat.delivery.total`)으로 기록되고 실패 지표는 늘지 않는다. 운영 지표로는 F7을 알아챌 수 없다 | 작업 5에서 두 서버의 지표 비교 |
| F54 | 재시작한 서버가 돌아와도 라운드로빈은 이미 맺어진 긴 연결을 다시 나누지 않는다. 재연결이 모두 살아 있는 서버로 몰린 채 남는다(쏠림) | 작업 6에서 복구 뒤 서버별 `chat_ws_sessions` |
| F55 | nginx 공식 이미지의 기본 `worker_connections`(1024)는 프록시 연결 하나에 두 개(클라이언트·upstream)를 쓰므로 작업자 수에 따라 연결 1,000개 근처에서 부족할 수 있다(`worker_connections are not enough`) | 작업 6에서 nginx 오류 로그 |
| F56 | 재연결 폭주의 복구 조회가 살아 있는 서버의 DB 커넥션 풀을 포화시킨다(F1 계열). Hikari 대기 수가 늘고 조회 p99가 커진다 | 작업 6에서 `hikaricp_connections_pending` |
| F57 | graceful 종료 중인 서버로 들어간 핸드셰이크는 실패하고, nginx가 다음 upstream으로 재시도(`proxy_next_upstream error timeout`)할지는 실패 종류에 따라 다르다 | 작업 6에서 실패 상태 코드 분포 |

## 파일 구조
```
infra/
 ├─ compose.cluster.yml                       (새, 작업 1) mysql, app1, app2, nginx
 ├─ cluster/nginx/naive.conf                  (새, 작업 1) 흔한 최소 설정 (F8 재현 조건)
 ├─ cluster/nginx/chat.conf                   (새, 작업 4) 승인된 보완 설정
 ├─ cluster/nginx/f8-*.conf                   (새, 작업 4) 원인 분리용 실험 설정
 ├─ cluster/README.md                         (새, 작업 1) 띄우는 방법, 포트, 설정 교체
 └─ prometheus/prometheus.yml                 (수정, 작업 1) job chat-cluster
backend/src/test/java/jissuo/chat/
 ├─ support/WsTestClient.java                 (수정, 작업 2) URI + 헤더로 연결하는 생성 메서드
 └─ experiment/cluster/
     ├─ ClusterControl.java                   (새, 작업 2) docker compose 실행, 건강 확인
     ├─ ClusterHttp.java                      (새, 작업 2) nginx 경유 REST (사용자 생성, 방, 입장, 전송, 조회)
     ├─ ProxyHandshakeExperiment.java         (새, 작업 3·4) F8
     ├─ TwoServerFanoutExperiment.java        (새, 작업 5) F7 결정적 재현 (JVM 안 2개 컨텍스트)
     ├─ ClusterFanoutExperiment.java          (새, 작업 5) F7 nginx 자연 분배
     ├─ StormClient.java                      (새, 작업 6) 재연결 정책을 받는 실험용 클라이언트
     └─ ReconnectStormExperiment.java         (새, 작업 6·7) F17
frontend/src/realtime/
 ├─ chatSocket.ts(+test)                      (수정, 작업 7, 승인 시) 재연결 대기 정책
 └─ reconnectDelay.ts(+test)                  (새, 작업 7, 승인 시)
docs/                                         (작업 0, 8) adr, failure-lab, journal, README, reports, design/architecture.md
CLAUDE.md                                     (작업 8) 현재 위치, 클러스터 명령
```

## 실행 순서와 병렬화

| 작업 | 선행 | 고치거나 만드는 파일 |
|---|---|---|
| 0 결정·가설 기록 | 없음 | `docs/adr/2026-10-08.md`(날짜는 실행일), `docs/failure-lab.md` |
| 1 클러스터 구성 | 없음 | `infra/compose.cluster.yml`, `infra/cluster/**`, `infra/prometheus/prometheus.yml` |
| 2 실험 지원 코드 | 없음 | `support/WsTestClient.java`, `experiment/cluster/{ClusterControl,ClusterHttp}.java` |
| 3 F8 재현 | 1, 2 | `experiment/cluster/ProxyHandshakeExperiment.java` |
| 4 F8 보완 (승인 뒤) | 3 | `infra/cluster/nginx/{chat,f8-*}.conf`, `compose.cluster.yml` 기본값, 실험 메서드 추가 |
| 5 F7 재현 | 4 | `experiment/cluster/{TwoServerFanoutExperiment,ClusterFanoutExperiment}.java` |
| 6 F17 재현 | 5 | `experiment/cluster/{StormClient,ReconnectStormExperiment}.java` |
| 7 F17 보완 (승인 뒤) | 6 | `frontend/src/realtime/{reconnectDelay,chatSocket}.*`, `StormClient` 정책 추가 |
| 8 E2E·브라우저·기록 | 7 | `docs/**`, `CLAUDE.md`, 주석의 `계획 8 세부 #n` |

| 웨이브 | 동시에 하는 작업 | 끝난 뒤 통합 확인 |
|---|---|---|
| 1 | 0, 1, 2 | `docker compose -f infra/compose.cluster.yml up -d --wait` 성공, `curl -si localhost:18090/api/rooms -H 'X-User-Id: 1'`의 `X-Upstream`이 번갈아 바뀜, `cd backend && ./gradlew test` 통과 |
| 2 | 3 | 작업 3 보고 (멈춤) |
| 3 | 4 | 작업 4 보고 |
| 4 | 5 | 작업 5 보고 (멈춤) |
| 5 | 6 | 작업 6 보고 (멈춤. 규모를 늘릴지 질문) |
| 6 | 7 | 작업 7 재측정 보고 |
| 7 | 8 | 검증 요약 전체 |

**동시에 진행할 때의 규칙** (계획 7과 같다): 같은 작업 트리에서 진행한다. 표에 없는 파일은 고치지 않는다. Gradle은 한 번에 하나만 실행한다. 작업이 끝나면 결과를 보고하고 멈춘다.

---

### 작업 0: 결정·가설 기록

> 웨이브 1 · 선행 없음 · 1, 2와 동시 진행

**Files:** Modify `docs/adr/<실행일>.md`, `docs/failure-lab.md`

- [ ] **Step 1: ADR 기록** — `## 계획 8: 서버 2대와 nginx` 절을 만들고 ADR-147부터 기존 표 형식(번호 | 결정 | 이유 | 버린 대안)으로 적는다.
  - 147 범위 (D1): 버린 대안은 "재현만", "Redis 전 임시 해결(서버 간 HTTP 중계·sticky)"
  - 148 compose 컨테이너 구성 (D2, 세부 1·2·3): 버린 대안은 "로컬 bootRun 2개", "둘 다"
  - 149 라운드로빈 (D3): 버린 대안은 `ip_hash`, `least_conn`(F54 보완 후보로 남김)
  - 150 **F17 규모: Java 실험 클라이언트 1,000개로 시작하고, 드러나지 않으면 승인받아 2,000·5,000개로 늘린다. k6 WebSocket은 Step 6에서 만든다** (D4, 사용자 요청). 이유: 기존 `experiment/ws` 자산을 쓰고 서버 지표와 함께 원인을 본다. 로컬 한 대에서 5,000개면 클라이언트 쪽 병목(파일 디스크립터, Docker 네트워크)이 섞인다. 버린 대안: k6 5,000개(새 스크립트, ADR-128과 범위가 겹침), 브라우저 관찰만
  - 151~ 승인된 세부 4~10 (분배 관찰, 프론트 제공, 지표, nginx 설정 교체, Origin 헤더, 실험 제어, F7 결정적 재현)
- [ ] **Step 2: 가설 기록** — `failure-lab.md` 상태 표에 F51~F57을 `가설`로 추가하고, "다중 서버" 절에 위 "예상되는 문제" 표의 내용을 적는다. F7·F8 본문에 ADR-146(60초 재대조)과 ADR-144(10초 ping) 때문에 예상이 달라진 점을 덧붙인다.
- [ ] **Step 3: 보고하고 멈춘다.**

### 작업 1: 클러스터 구성 (compose, 최소 nginx, Prometheus 대상)

> 웨이브 1 · 선행 없음

**Files:** Create `infra/compose.cluster.yml`, `infra/cluster/nginx/naive.conf`, `infra/cluster/README.md` · Modify `infra/prometheus/prometheus.yml`

**Produces:** 서비스 이름 `mysql`, `app1`, `app2`, `nginx`. 환경 변수 `CLUSTER_PROFILE`(기본 `local`), `NGINX_CONF`(기본 `naive`). 포트 18090/18081/18082/33306.

- [ ] **Step 1: nginx 태그 확인** — `docker pull nginx:stable-alpine && docker image inspect nginx:stable-alpine --format '{{index .Config.Env}}'`로 `NGINX_VERSION`을 확인하고 그 버전(`nginx:<버전>-alpine`)으로 고정한다. 기본 `worker_processes`·`worker_connections`도 `docker run --rm nginx:<버전>-alpine cat /etc/nginx/nginx.conf`로 확인해 일지에 "측정"으로 적는다(F55의 근거).
- [ ] **Step 2: `infra/compose.cluster.yml`**
```yaml
# 계획 8: 같은 DB를 쓰는 앱 2대를 nginx 뒤에 둔다. 별도 프로젝트·볼륨이라 local DB를 건드리지 않는다.
# 실행 전: cd backend && ./gradlew bootJar, cd frontend && npm run build
name: chat-cluster

x-app: &app
  image: eclipse-temurin:21-jre
  environment: &app-env
    SPRING_PROFILES_ACTIVE: "${CLUSTER_PROFILE:-local},mysql"
    SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/chat
    SPRING_DATASOURCE_USERNAME: chat
    SPRING_DATASOURCE_PASSWORD: chat
    JAVA_TOOL_OPTIONS: "-XX:ActiveProcessorCount=2"
    LOG_DIR: /app/logs
  command: [java, -jar, /app/chat.jar]
  # 계획 8 세부 1: ADR-040과 같은 자원 제한
  cpus: 2
  mem_limit: 1g
  depends_on:
    mysql:
      condition: service_healthy
  healthcheck:
    test: [CMD-SHELL, "wget -qO- localhost:8080/actuator/health | grep -q UP"]
    interval: 3s
    timeout: 2s
    retries: 40

services:
  mysql:
    image: mysql:8.4.11
    ports: ["33306:3306"]
    environment:
      MYSQL_DATABASE: chat
      MYSQL_USER: chat
      MYSQL_PASSWORD: chat
      MYSQL_ROOT_PASSWORD: root
      TZ: UTC
    command: [--innodb-flush-log-at-trx-commit=1, --innodb-buffer-pool-size=256M]
    cpus: 2
    mem_limit: 1g
    volumes: [cluster-mysql-data:/var/lib/mysql]
    healthcheck:
      test: [CMD, mysqladmin, ping, -h, 127.0.0.1, -uchat, -pchat]
      interval: 5s
      timeout: 3s
      retries: 20

  app1:
    <<: *app
    ports: ["18081:8080"]
    volumes:
      - ../backend/build/libs/chat-0.0.1-SNAPSHOT.jar:/app/chat.jar:ro
      # 계획 8 세부 3: 두 서버가 같은 app.json을 함께 롤링하지 않게 나눈다
      - ../backend/logs/cluster/app1:/app/logs

  app2:
    <<: *app
    ports: ["18082:8080"]
    volumes:
      - ../backend/build/libs/chat-0.0.1-SNAPSHOT.jar:/app/chat.jar:ro
      - ../backend/logs/cluster/app2:/app/logs

  nginx:
    image: nginx:<Step 1에서 고정한 버전>-alpine
    ports: ["18090:80"]
    cpus: 1
    mem_limit: 256m
    depends_on:
      app1: { condition: service_healthy }
      app2: { condition: service_healthy }
    volumes:
      # 계획 8 세부 7: 실험이 설정 파일만 바꿔 같은 구성에서 비교한다
      - ./cluster/nginx/${NGINX_CONF:-naive}.conf:/etc/nginx/conf.d/default.conf:ro
      - ../frontend/dist:/usr/share/nginx/html:ro
    healthcheck:
      test: [CMD-SHELL, "wget -qO- localhost/ >/dev/null"]
      interval: 3s
      timeout: 2s
      retries: 20

volumes:
  cluster-mysql-data:
```
`eclipse-temurin:21-jre`에 `wget`이 없으면 healthcheck를 `curl -sf`로 바꾼다(Step 5에서 확인). 바꾼 경우 일지에 적는다.
- [ ] **Step 3: `infra/cluster/nginx/naive.conf`** (F8 재현 조건. 흔한 최소 설정이다)
```nginx
# ADR-034: 흔히 처음 쓰는 최소 설정 그대로 둔다. Upgrade·Host·X-Forwarded-For를 전달하지 않는다 (F8, F51, F52)
upstream chat_backend {
    server app1:8080;
    server app2:8080;
}

# 계획 8 세부 4: 어느 서버로 갔는지 접근 로그와 응답 헤더로 본다 (분배를 관찰할 뿐, 동작은 바꾸지 않는다)
log_format upstream '$remote_addr "$request" $status upstream=$upstream_addr '
                    'upstream_status=$upstream_status rt=$request_time';

server {
    listen 80;
    access_log /var/log/nginx/access.log upstream;
    add_header X-Upstream $upstream_addr always;

    root /usr/share/nginx/html;
    location / {
        try_files $uri /index.html;
    }
    location /api/ {
        proxy_pass http://chat_backend;
    }
    location /ws {
        proxy_pass http://chat_backend;
    }
}
```
- [ ] **Step 4: Prometheus 대상** — `infra/prometheus/prometheus.yml`의 `scrape_configs`에 기존 `chat` job과 같은 `metrics_path`·간격으로 job `chat-cluster`(`targets: ['host.docker.internal:18081', 'host.docker.internal:18082']`)를 추가한다.
- [ ] **Step 5: 띄우고 확인**
```bash
cd backend && ./gradlew bootJar && cd ../frontend && npm run build && cd ..
docker compose -f infra/compose.cluster.yml up -d --wait
for i in 1 2 3 4; do curl -si localhost:18090/api/users -H 'X-User-Id: 1' | grep -i x-upstream; done
```
예상: `X-Upstream`이 두 주소를 번갈아 보인다(라운드로빈). `http://localhost:18090`에서 화면이 뜬다. 연결 상태 패널은 F8 때문에 "끊김"과 재연결 횟수 증가를 보일 것으로 예상한다. **고치지 않는다.**
- [ ] **Step 6: `infra/cluster/README.md`** — 사전 빌드 두 줄, 띄우기·내리기(`down -v`), 포트 표, `NGINX_CONF`·`CLUSTER_PROFILE` 사용법, 로그 위치(`backend/logs/cluster/app{1,2}`, `docker compose ... logs nginx`)를 적는다.
- [ ] **Step 7: 보고하고 멈춘다.**

### 작업 2: 실험 지원 코드

> 웨이브 1 · 선행 없음

**Files:** Modify `backend/src/test/java/jissuo/chat/support/WsTestClient.java` · Create `backend/src/test/java/jissuo/chat/experiment/cluster/{ClusterControl,ClusterHttp}.java`

**Produces:**
```java
// support
public static WsTestClient connect(URI uri, WebSocketHttpHeaders headers) throws Exception;  // 5초 안에 연결, 실패 시 예외
// experiment/cluster
final class ClusterControl {
    static final int NGINX = 18090, APP1 = 18081, APP2 = 18082;
    static final String ORIGIN = "http://localhost:18090";
    static void assumeRunning();                         // nginx /api/users가 응답하지 않으면 Assumptions.abort
    static void useNginx(String conf);                   // NGINX_CONF=conf ... up -d --force-recreate --wait nginx
    static void restart(String service);                 // docker compose restart <service>
    static void kill(String service);                    // docker compose kill <service>
    static void start(String service);                   // docker compose up -d --wait <service>
    static String logs(String service, Instant since);   // docker compose logs --since <ISO> <service>
    static WebSocketHttpHeaders browserHeaders();        // Origin: ORIGIN (계획 8 세부 8)
}
final class ClusterHttp {
    ClusterHttp(int port, JsonMapper json);
    long createUser(String nickname);                    // POST /api/dev/users
    long createRoom(long userId, String name);
    void join(long userId, long roomId);
    HttpResponse<String> send(long userId, long roomId, String content);
    HttpResponse<String> latest(long userId, long roomId);   // GET /api/rooms/{id}/messages
    static String upstream(HttpResponse<?> response);    // X-Upstream 헤더, 없으면 ""
}
```

- [ ] **Step 1: `WsTestClient`에 URI·헤더 생성 메서드 추가** — 기존 생성자를 `(URI uri, WebSocketHttpHeaders headers)`를 받는 private 생성자로 바꾸고 `CLIENT.execute(handler, headers, uri)`를 부른다. 기존 `connect(int, long)`과 `connectRaw(int, String)`은 `URI.create("ws://localhost:" + port + "/ws?" + query)`와 빈 헤더로 이 생성자를 부른다. 공개 시그니처는 바꾸지 않는다.
- [ ] **Step 2: `ClusterControl`** — 저장소 루트 기준 `infra/compose.cluster.yml`을 찾는다(Gradle 작업 디렉터리는 `backend/`이므로 `Path.of("..", "infra", "compose.cluster.yml")`). `ProcessBuilder(...).inheritIO()`가 아니라 출력을 읽어 실패 시 종료 코드와 출력을 담아 `IllegalStateException`을 던진다. `useNginx`는 `environment().put("NGINX_CONF", conf)`로 넘긴다. `assumeRunning`은 `HttpClient`로 2초 제한 GET을 보내고 예외면 `Assumptions.abort("클러스터가 떠 있지 않다: infra/cluster/README.md")`를 부른다.
- [ ] **Step 3: `ClusterHttp`** — `support/ChatHttp`와 같은 방식(`HttpClient`, `X-User-Id`, 응답 `data` 파싱)으로 쓰고 `createUser`·`latest`·`upstream`만 더한다. `ChatHttp`는 고치지 않는다.
- [ ] **Step 4: 확인** — `cd backend && ./gradlew test --tests 'jissuo.chat.message.api.ws.*'` 통과(`WsTestClient` 회귀). `./gradlew compileTestJava` 통과.
- [ ] **Step 5: 보고하고 멈춘다.**

### 작업 3: F8 재현 — 최소 설정 nginx 뒤 WebSocket

> 웨이브 2 · 선행: 1, 2

**Files:** Create `backend/src/test/java/jissuo/chat/experiment/cluster/ProxyHandshakeExperiment.java`

- [ ] **Step 1: 실험 작성** — `@Tag("experiment")`, Spring 컨텍스트 없음. `@BeforeAll`에서 `ClusterControl.assumeRunning()`.
  - `naive_설정에서_핸드셰이크`: `ClusterControl.useNginx("naive")` → 사용자 1명 생성 → `WsTestClient.connect(URI.create("ws://localhost:18090/ws?userId=" + id), ClusterControl.browserHeaders())`를 10번 시도한다. 성공 수, 실패 예외 메시지(상태 코드 포함), 시도 시각 이후의 `ClusterControl.logs("nginx", t0)`에서 `/ws` 줄(상태·upstream)을 `ExperimentResults.record("cluster-f8-handshake", "conf,attempt,result,status,upstream", ...)`로 남긴다.
  - `naive_설정의_REST_clientIp`(F52): REST 한 번 → `backend/logs/cluster/app{1,2}/app.json`의 마지막 `ACCESS` 줄의 `clientIp`를 읽어 기록한다.
- [ ] **Step 2: 실행** — `cd backend && ./gradlew experimentTest --tests 'jissuo.chat.experiment.cluster.ProxyHandshakeExperiment'`. 예상(측정 전): 10번 모두 실패한다. 앱은 `Upgrade` 없는 GET을 받아 400을 돌려줄 것으로 예상한다(정확한 코드는 측정한다). `clientIp`는 nginx 컨테이너 주소(172.x)다.
- [ ] **Step 3: 브라우저 관찰** — `http://localhost:18090`에서 사용자 시작 → 방 입장. 연결 상태 패널의 재연결 횟수가 초마다 늘고 nginx 접근 로그에 `/ws` 실패가 쌓이는지 본다. 메시지 전송은 소켓이 열리지 않아 연결 오류 문구가 나올 것으로 예상한다. 결과는 "측정"으로 일지에 적는다.
- [ ] **Step 4: 멈추고 보고** — 측정값과 예상을 나눠 보고한다. 원인 분석은 사용자와 함께 한다. 보완안(작업 4의 후보)은 7단계 형식으로 제안만 한다.

### 작업 4: F8 보완 (작업 3 보고 뒤 승인된 안으로)

> 웨이브 3 · 선행: 3 · **7단계 제안 승인 뒤 시작**

**Files:** Create `infra/cluster/nginx/{f8-upgrade,f8-host,chat}.conf` · Modify `infra/compose.cluster.yml`(`NGINX_CONF` 기본값을 `chat`으로), `ProxyHandshakeExperiment.java`(메서드 추가)

추천 후보(승인되면 이대로 한다). 원인을 하나씩 떼어 보려고 설정 세 개를 차례로 측정한다.

| 설정 | `naive`와 다른 점 | 예상(측정 전) |
|---|---|---|
| `f8-upgrade` | `proxy_http_version 1.1`, `Upgrade`/`Connection` 전달 | 403 (F51: Host가 `chat_backend`) |
| `f8-host` | 위 + `proxy_set_header Host $host` | 403 (포트가 빠짐) |
| `chat` | 위의 Host를 `$http_host`로 + `X-Forwarded-For`/`Proto` + 명시적 `proxy_read_timeout 60s` | 101 |

- [ ] **Step 1: `chat.conf`**
```nginx
upstream chat_backend {
    server app1:8080;
    server app2:8080;
}

# WebSocket 요청이 아닐 때 Connection: upgrade를 보내지 않게 한다
map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}

log_format upstream '$remote_addr "$request" $status upstream=$upstream_addr '
                    'upstream_status=$upstream_status rt=$request_time';

server {
    listen 80;
    access_log /var/log/nginx/access.log upstream;
    add_header X-Upstream $upstream_addr always;

    # F51: Spring의 같은 origin 검사(ADR-130)가 브라우저 Origin의 host:port와 비교하므로 포트까지 넘긴다
    proxy_set_header Host $http_host;
    # F52, ADR-086: 앱 접근 로그의 clientIp가 브라우저 주소가 되게 한다
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;

    root /usr/share/nginx/html;
    location / {
        try_files $uri /index.html;
    }
    location /api/ {
        proxy_pass http://chat_backend;
    }
    location /ws {
        proxy_pass http://chat_backend;
        # F8: 업그레이드는 HTTP/1.1에서만 되고 Upgrade·Connection은 홉 단위 헤더라 직접 넘긴다
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        # location에 proxy_set_header가 있으면 server 수준 설정을 상속하지 않으므로 다시 적는다
        proxy_set_header Host $http_host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        # ADR-144: 서버 ping(10초)이 이 시간보다 짧아 유휴 연결이 끊기지 않는다. 기본값과 같지만 관계를 드러내려고 적는다
        proxy_read_timeout 60s;
    }
}
```
`f8-upgrade.conf`·`f8-host.conf`는 위 표의 차이만 두고 나머지는 `naive.conf`와 같다.
- [ ] **Step 2: 실험 메서드 추가** — `설정별_핸드셰이크`: `naive`, `f8-upgrade`, `f8-host`, `chat`을 차례로 `useNginx`하고 작업 3과 같은 10회 시도를 기록한다. `chat_설정의_유휴_유지`: 연결 1개를 180초 동안 유지하고 닫힘 이벤트와 시각을 기록한다(예상: 닫히지 않음). 대조로 `proxy_read_timeout 5s`만 다른 `f8-timeout5.conf`로 같은 측정을 한다(예상: ping 간격 10초보다 짧아 약 5초 뒤 닫힘). 이 대조로 heartbeat가 유휴 끊김을 막는다는 것을 확인한다.
- [ ] **Step 3: 실행·확인** — 같은 `experimentTest` 명령. 브라우저 `http://localhost:18090`에서 연결 상태 "연결됨", 두 탭 송수신(같은 서버일 때), `/api` 접근 로그의 `clientIp`가 브라우저 쪽 주소인지 확인한다.
- [ ] **Step 4: 기본값 교체·기록** — `compose.cluster.yml`의 `${NGINX_CONF:-naive}`를 `${NGINX_CONF:-chat}`으로 바꾼다. 측정 결과로 ADR(F8 보완)을 추가하고 F8·F51·F52 상태를 바꾼다. 보고하고 멈춘다.

### 작업 5: F7 재현 — 다른 서버에 붙은 멤버

> 웨이브 4 · 선행: 4

**Files:** Create `experiment/cluster/{TwoServerFanoutExperiment,ClusterFanoutExperiment}.java`

- [ ] **Step 1: 결정적 재현 (`TwoServerFanoutExperiment`)** — `ReconnectLossExperiment`와 같은 머리(`@Tag("experiment")`, `@SpringBootTest(RANDOM_PORT)`, `@ActiveProfiles("mysql")`, `MySqlContainerSupport.register`). `@BeforeAll`이 아니라 테스트 안에서 서버 2를 띄운다.
```java
ConfigurableApplicationContext server2 = new SpringApplicationBuilder(ChatApplication.class)
        .profiles("mysql")
        .properties("server.port=0",
                "spring.datasource.url=" + jdbcUrl,          // 서버 1의 Environment에서 읽는다
                "spring.datasource.username=chat", "spring.datasource.password=chat")
        .run();
int port2 = server2.getEnvironment().getProperty("local.server.port", Integer.class);
```
시나리오: 방에 A·B·S(보낸 사람). A는 서버 1, B는 서버 2에 연결한다. S가 서버 1의 REST로 1건, 서버 2의 REST로 1건 보낸다. 각 클라이언트가 3초 동안 받은 id, DB 저장 여부(`ChatHttp.get`), 두 서버의 `chat.delivery.total`·`chat.delivery.failed` 증가분(F53)을 `ExperimentResults.record("cluster-f7-two-servers", ...)`로 남긴다. 끝나면 `server2.close()`.
예상(측정 전): A는 서버 1로 보낸 메시지만, B는 서버 2로 보낸 메시지만 받는다. 두 메시지 모두 DB에는 있다. 두 서버 모두 실패 지표는 0이다.
- [ ] **Step 2: nginx 자연 분배 (`ClusterFanoutExperiment`)** — `ClusterControl.assumeRunning()`. 사용자 21명(보낸 사람 1 + 수신자 20)을 만들고 한 방에 넣는다. 수신자 20명은 nginx로 연결한다(라운드로빈이라 약 10명씩). 보낸 사람이 REST로 20건을 보내고 응답의 `X-Upstream`을 기록한다. 5초 뒤 수신자별 받은 건수와 받은 메시지의 저장 서버를 집계해 `cluster-f7-round-robin`에 남긴다. 수신자가 어느 서버에 붙었는지는 받은 메시지의 저장 서버로 추정하고, 받은 것이 없으면 `unknown`으로 둔다.
예상(측정 전): 수신자마다 약 10건(절반)을 받는다. 받은 메시지는 모두 자기 서버에 저장된 것이다.
- [ ] **Step 3: 브라우저 관찰** — 두 탭(A, B)을 `http://localhost:18090`에 연다. 개발자 도구 Network에서 `/ws`의 `X-Upstream`이 다른 서버인지 확인한다(같으면 B 탭을 새로고침해 다시 분배받는다). A가 보낸 메시지가 B에게 늦게 보이는지, 몇 초 뒤에 보이는지(60초 재대조, ADR-146) 측정한다. 결과는 "측정"으로 일지에 적는다.
- [ ] **Step 4: 실행·멈추고 보고** — `./gradlew experimentTest --tests 'jissuo.chat.experiment.cluster.*Fanout*'`. 해결은 계획 9(Redis)다. 이번에는 분석과 기록만 한다.

### 작업 6: F17 재현 — 앱 1대 재시작 때 재연결 폭주

> 웨이브 5 · 선행: 5

**Files:** Create `experiment/cluster/{StormClient,ReconnectStormExperiment}.java`

**Interfaces:**
```java
// 재연결 대기 정책. 작업 7에서 보완 정책을 같은 자리에 넣어 같은 실험으로 비교한다
interface ReconnectPolicy { Duration delay(int attempt); }       // attempt는 연속 실패 횟수, 0부터
record Fixed(Duration delay) implements ReconnectPolicy { ... }  // ADR-137 재현: 항상 1초

final class StormClient implements AutoCloseable {
    StormClient(long userId, long roomId, ReconnectPolicy policy, ClusterHttp http, ScheduledExecutorService timer);
    void start();                  // nginx로 연결 (ClusterControl.browserHeaders)
    List<Event> events();          // CLOSED(code), ATTEMPT, OPENED, FAILED(message), RECOVERED(status, millis)
    record Event(long nanos, String type, String detail) {}
}
```
- `StormClient`는 `chatSocket.ts`를 따라 한다. 닫히면 `policy.delay(attempt)` 뒤 다시 연결한다. 연결에 실패해도 같은 정책으로 다시 시도한다. 다시 열리면 `ClusterHttp.latest`를 한 번 보내 복구 조회(ADR-145의 첫 페이지)를 흉내 낸다. 새 메시지가 없는 조건이라 `recover.ts`의 다음 페이지 조회는 일어나지 않는다고 가정하고, 이 가정을 보고서에 적는다. REST는 가상 스레드 실행기로 보낸다.

- [ ] **Step 1: 준비** — `CLUSTER_PROFILE=bench docker compose -f infra/compose.cluster.yml up -d --wait`(ADR-022). 맥의 파일 디스크립터 한도(`ulimit -n`, `launchctl limit maxfiles`)를 기록한다. 테스트 JVM이 연결 1,000개 이상을 열 수 있는지 먼저 100개로 확인한다.
- [ ] **Step 2: 실험** — 방 100개 × 멤버 10명 = 사용자 1,000명을 REST로 만든다(`ClusterHttp`). `Fixed(1s)`로 1,000개를 연결하고, 서버별 `chat_ws_sessions`(18081/18082의 `/actuator/prometheus`)가 합계 1,000이 될 때까지 기다린다. 이 시점의 기준 조회 p99를 따로 잰다. 조건 두 가지를 차례로 실행한다: (a) `ClusterControl.restart("app1")`(graceful 종료), (b) `ClusterControl.kill("app1")` 뒤 `start("app1")`. 조건마다 다음을 `cluster-f17-storm`에 기록한다.
  - 초 단위 핸드셰이크 시도·성공·실패 수(최대값), 실패 메시지별 수(F57)
  - 첫 닫힘부터 1,000개가 모두 열릴 때까지 걸린 시간
  - 복구 조회의 p50/p99·실패 수, 기준 대비 배수
  - 1초 간격으로 읽은 app2의 `hikaricp_connections_pending` 최대값(F56)
  - 복구 뒤 60초 시점의 서버별 `chat_ws_sessions`(F54)
  - 측정 구간의 nginx 오류 로그 중 `worker_connections` 줄 수(F55)
- [ ] **Step 3: 실행** — `./gradlew experimentTest --tests 'jissuo.chat.experiment.cluster.ReconnectStormExperiment'`. 예상(측정 전): (a)에서 약 500개가 1초 뒤 거의 동시에 재연결을 시도한다. app1이 내려가 있는 동안의 시도는 nginx가 app2로 넘기거나 502로 실패한다. 복구 뒤에도 연결 대부분이 app2에 남는다(F54). 복구 조회 p99는 기준보다 커지지만 1,000개 규모에서 풀 포화(F56)까지 갈지는 모르겠다.
- [ ] **Step 4: 멈추고 보고** — 측정값과 예상을 나눠 보고한다. 폭주 신호가 없으면(실패 0, 조회 p99가 기준의 2배 미만, Hikari 대기 0) 2,000·5,000개로 늘릴지 묻는다(ADR-150). 보완 후보는 7단계 형식으로 제안만 한다. 지금 생각하는 후보는 지수 대기 + 전체 지터(full jitter), 복구 조회 지연, `least_conn`, 재시작 전 연결 분산 종료다.

### 작업 7: F17 보완 (작업 6 보고 뒤 승인된 안으로)

> 웨이브 6 · 선행: 6 · **7단계 제안 승인 뒤 시작**

추천 후보(승인되면 이대로 한다): 프론트 재연결을 "지수 대기 + 전체 지터"로 바꾼다. `delay = random(0, min(30초, 1초 × 2^attempt))`이고, 연결이 열리면 `attempt`를 0으로 되돌린다. 복구 조회는 연결 뒤 바로 보낸다(지연을 따로 두지 않는다). 승인 결과가 다르면 이 작업의 코드 블록을 승인된 안으로 바꿔 쓴 뒤 시작한다.

**Files:** Create `frontend/src/realtime/reconnectDelay.ts(+test)` · Modify `frontend/src/realtime/chatSocket.ts(+test)`, `experiment/cluster/StormClient.java`(정책 `FullJitter` 추가), `ReconnectStormExperiment.java`(정책별 실행)

- [ ] **Step 1: 실패하는 테스트** — `reconnectDelay.test.ts`
```ts
import { describe, expect, it } from 'vitest'
import { reconnectDelay } from './reconnectDelay'

describe('reconnectDelay', () => {
  it('상한 안에서 attempt마다 두 배로 넓어진 구간의 값을 고른다', () => {
    expect(reconnectDelay(0, () => 0.5)).toBe(500)
    expect(reconnectDelay(3, () => 0.5)).toBe(4000)
  })
  it('30초를 넘지 않는다', () => {
    expect(reconnectDelay(20, () => 0.999)).toBeLessThan(30_000)
  })
  it('0에 가까운 난수면 바로 다시 붙는다', () => {
    expect(reconnectDelay(5, () => 0)).toBe(0)
  })
})
```
`chatSocket.test.ts`에 추가: `createChatSocket(userId, { open, random: () => 0.5 })`로 만들고, 연결 실패가 두 번 이어지면 두 번째 재연결이 `vi.advanceTimersByTime(1000)`에서 일어나는지 본다(attempt 1 → 2초 × 0.5). 열린 뒤 다시 끊기면 대기가 500ms로 돌아가는지 본다.
- [ ] **Step 2: 실패 확인** — `cd frontend && npx vitest run src/realtime` → FAIL(모듈 없음)
- [ ] **Step 3: 구현**
```ts
// reconnectDelay.ts
const BASE_MS = 1000
const CAP_MS = 30_000

// F17: 같은 순간에 끊긴 탭들이 같은 순간에 다시 붙지 않게 구간 전체에서 고르게 흩는다 (full jitter)
export function reconnectDelay(attempt: number, random: () => number = Math.random): number {
  return Math.floor(random() * Math.min(CAP_MS, BASE_MS * 2 ** attempt))
}
```
`chatSocket.ts`: `RECONNECT_DELAY_MS`를 지우고 `Options`에 `random?: () => number`를 더한다. `let attempt = 0`을 둔다. `onopen`에서 `attempt = 0`으로 되돌린다. `onclose`에서 `setTimeout(..., reconnectDelay(attempt++, random))`을 쓴다. ADR-137 주석은 새 ADR 번호로 바꾼다. `RECONNECT_DELAY_MS`를 쓰는 곳은 `grep -rn RECONNECT_DELAY_MS frontend/src frontend/e2e`로 찾아 함께 고친다.
- [ ] **Step 4: 통과 확인** — `npx vitest run && npx tsc -b && npm run lint`
- [ ] **Step 5: 재측정** — `StormClient`에 같은 공식의 `record FullJitter(Duration base, Duration cap, RandomGenerator random) implements ReconnectPolicy`를 더한다. `ReconnectStormExperiment`를 `Fixed(1s)`와 `FullJitter`로 같은 조건 (a)·(b)에서 실행해 작업 6과 같은 열에 기록한다. 예상(측정 전): 초당 핸드셰이크 최대값은 줄고, 전원이 다시 연결될 때까지의 시간은 늘어난다.
- [ ] **Step 6: 기록·보고** — ADR(F17 보완)을 추가하고 F17 상태를 바꾼다. 쏠림(F54)이 남으면 가설로 남겨 둔다. 보고하고 멈춘다.

### 작업 8: E2E, 브라우저 확인, 기록

> 웨이브 7 · 선행: 7

- [ ] **Step 1: 회귀** — `cd backend && ./gradlew test`, `cd frontend && npx vitest run && npx tsc -b && npm run lint && npm run e2e`(기존 Vite + bootRun 구성).
- [ ] **Step 2: 클러스터 브라우저 확인** — `npm run build` 뒤 클러스터를 다시 띄운다(`NGINX_CONF` 기본 `chat`). 두 탭이 다른 서버일 때 F7 지연, 같은 서버일 때 즉시 수신을 확인한다. `docker compose restart app1` 때 재연결 간격이 흩어지는지 연결 상태 패널과 nginx 로그로 확인한다.
- [ ] **Step 3: 문서**
  - `docs/reports/<실행일>-plan8-multi-server.md`: F8·F7·F17 조건, 측정값, 예상과의 차이, 한계(Java 클라이언트는 브라우저가 아님, 복구 조회는 한 페이지만 가정, 로컬 단일 머신)
  - `docs/failure-lab.md` 상태, `docs/journal/<실행일>.md`, `docs/README.md` 현재 상태·로드맵, `docs/design/architecture.md`의 nginx 구성
  - `CLAUDE.md` 현재 위치와 클러스터 명령(`docker compose -f infra/compose.cluster.yml up -d --wait`, 사전 빌드)
  - 코드·설정 주석의 `계획 8 세부 #n`을 ADR 번호로 바꾼다
  - 이 계획서 머리에 실행 상태를 적는다
- [ ] **Step 4: 보고하고 멈춘다.** 커밋은 사용자가 요청할 때만 한다.

## 검증 요약

| 무엇을 | 어떻게 |
|---|---|
| 기존 회귀 | `cd backend && ./gradlew test`, `cd frontend && npx vitest run && npx tsc -b && npm run lint && npm run e2e` |
| 클러스터 기동·분배 | `docker compose -f infra/compose.cluster.yml up -d --wait`, `curl -si localhost:18090/api/users -H 'X-User-Id: 1'`의 `X-Upstream` 교대 |
| F8 | `./gradlew experimentTest --tests 'jissuo.chat.experiment.cluster.ProxyHandshakeExperiment'` (설정별 핸드셰이크 결과, 180초 유휴 유지와 5초 대조) |
| F7 | `./gradlew experimentTest --tests 'jissuo.chat.experiment.cluster.*Fanout*'`, 브라우저 두 탭의 지연 시간 |
| F17 | `./gradlew experimentTest --tests 'jissuo.chat.experiment.cluster.ReconnectStormExperiment'` (정책별, 재시작·강제 종료별) |
| 지표 | Prometheus job `chat-cluster`에서 `instance`별 `chat_ws_sessions`, `chat_delivery_total_seconds_count`, `hikaricp_connections_pending` |
| 결과 파일 | `backend/build/experiment-results/cluster-*.csv` |

## 범위 밖
F7 해결(Redis Pub/Sub, 계획 9), sticky 세션, 서버 간 HTTP 중계, k6 WebSocket 부하와 W1~W5 비교(ADR-128, Step 6), Filebeat의 클러스터 로그 수집, HTTPS·`wss`, nginx 무중단 설정 재적재(reload 중 기존 WebSocket 작업자 잔류), DB 복제·자동 전환, 앱 인스턴스 id를 앱 코드에 넣는 일.

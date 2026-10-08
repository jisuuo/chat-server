# 계획 7: WebSocket 서버 1대 구현 계획 (Step 2, 고도화 P7)

> **진행 상태 (2026-10-08):** 웨이브 1(작업 1·2·5) 커밋·푸시 완료. 웨이브 2(작업 3·6) 구현 및 통합 검증 완료. 웨이브 3 승인 대기.

> **실행하는 에이전트에게**: 작업은 아래 "실행 순서와 병렬화"의 **웨이브 단위로 사용자 승인을 받고** 시작한다. 같은 웨이브의 작업은 동시에 진행할 수 있다. 웨이브가 끝나면 통합 확인을 하고 결과(테스트 출력 포함)를 보고한 뒤 멈춘다. **커밋하지 않는다** (사용자가 요청할 때만). 단계는 체크박스(`- [ ]`)로 추적한다. 장애 재현 작업(9~12)은 웨이브마다 멈추고 측정값과 예상을 나눠 보고한다.

**목표:** 수신을 HTTP 폴링(기본 2초)에서 WebSocket push로 바꾼다. 서버 1대, 탭마다 사용자 연결 1개로 같은 방 멤버에게 메시지 본문 전체를 보내고, 전송도 WebSocket으로 한다. 기존 REST API와 폴링은 그대로 남긴다. 전달 단계별 시간을 AOP로 재서, 이후 비동기 전달과 같은 지표로 비교할 수 있게 한다. 그 다음 흔한 구현에서 생기는 장애 F3~F6을 차례로 재현한다.

**구조:** 백엔드는 `/ws` 하나에 순수 `WebSocketHandler`(ADR-002)를 둔다. 핸드셰이크에서 쿼리 `userId`를 기존 `Authenticator`로 한 번 검사한다. `MessageService.send`가 `MessageSentEvent`를 발행하면 `MessageFanout`이 커밋 뒤(AFTER_COMMIT) 보내는 스레드에서 동기로 멤버 id를 조회하고 `MessagePusher`로 보낸다. REST 전송과 WS 전송이 같은 경로를 쓴다. 프론트는 App 수준의 연결 하나(`ChatSocketProvider`)를 두고, `useRoomMessages`(ADR-108)의 수신·전송 부분만 바꾼다. 화면은 그대로다.

**기술:** 계획 6과 같다(Java 21, Spring Boot 4.1.1, React 19.3.0, Vite 8.3.3, TypeScript 6.0.3, Vitest 5.0.3, Playwright 1.63.0). 백엔드에는 `spring-boot-starter-websocket`과 AOP 스타터(Spring Boot 4의 이름은 `spring-boot-starter-aspectj`로 예상하며, 작업 13에서 확인)만 추가한다. 프론트에는 새 라이브러리를 추가하지 않는다(브라우저 `WebSocket`).

## Context
- 고도화 개요(2026-10-07 승인): P6 UI → **P7 WebSocket 1대** → P8 서버 여러 대(F7) → P9 Redis 사용자 채널 Pub/Sub. 이 계획은 P7이다.
- 지금 수신은 HTTP 폴링(기본 2초, ADR-083)이라 실시간성이 떨어지고 읽기 부하가 크다. `useRoomMessages`(ADR-108)는 수신 방식만 바꾸려고 만든 훅이다.
- 이미 결정된 것 (그대로 지킨다): ADR-002(순수 `WebSocketHandler`, STOMP 아님), ADR-005·006(`X-User-Id` 신뢰, 추출/판별/전달 분리, `Authenticator`는 문자열을 받는다), ADR-028(같은 origin), ADR-034(장애 선행), ADR-082(방을 옮기면 `key`로 다시 만듦), ADR-083(폴링 동작), ADR-084(방 목록 자동 갱신 없음, 멤버 판단은 서버 인가 결과로), ADR-022(bench는 접근 로그 OFF), ADR-023(감사는 전용 로거, 성공은 커밋 후).

### 사용자 결정 (2026-10-08, 계획 작성 대화)

| 항목 | 결정 |
|---|---|
| D1 WS 인증 (Step 2 미결정 항목) | 쿼리 파라미터 `/ws?userId=7`. 핸드셰이크에서 기존 `Authenticator`로 한 번 검사(ADR-005·006과 같은 신뢰 수준) |
| D2 연결·전송 | 탭마다 사용자 연결 1개. 전송도 WS로 한다. 기존 REST `POST .../messages`는 유지(k6·API 계약·E2E 호환) |
| D3 push 내용 | 메시지 본문 전체(`MessageResponse`와 같은 모양). 화면은 기존 `mergeMessages`로 합친다 |
| D4 전달 방식 | 동기로 시작(보내는 스레드에서 커밋 뒤 push). 단 AOP로 "전송 수신 → 마지막 push 완료"까지 단계별 시간을 모두 재서 이후 비동기와 같은 지표로 비교한다 |
| D5 AOP 로그 범위 | WebSocket 접속·프레임·종료 로그(`WS_ACCESS`)와 전달 단계별 시간만 AOP로 한다. 기존 `ACCESS`(필터)·`AUDIT`(커밋 후 이벤트)·예외 로그(`GlobalExceptionHandler`)는 그대로 둔다. 이유: 필터 밖 요청(401·404)이 빠지고, 감사의 커밋 시점을 지킬 수 없고, 예외가 두 번 기록된다. 작업 8에서 ADR로 남긴다 |
| D6 장애 선행 | 흔한 구현 그대로: 동기 전송 루프(F4), ping/pong 없음(F5), 재연결 따라잡기 없음(F6), 세션 저장소는 일반 `HashMap`(F3). F3을 재현한 뒤 `ConcurrentHashMap` 보완안을 7단계 형식으로 알리고 승인받아 넣는다 |

## 지켜야 할 조건
- **장애 선행 (ADR-034)**: F3(일반 `HashMap`), F4(동기 전송 루프), F5(ping/pong 없음), F6(따라잡기 없음), F22·F23(의도적으로 열어 둔 문제), F33(전송 중 비활성화·낙관적 표시 없음)을 **미리 고치지 않는다.** 응답 짝 맞춤 id·중복 방지 키·비동기 전달·전송 큐·세션 잠금(`ConcurrentWebSocketSessionDecorator`)·재연결 지수 대기를 넣지 않는다. 작업 중 발견한 위험은 `docs/failure-lab.md`에 가설로만 적는다(작업 8).
- 기존 REST API의 동작과 응답은 바꾸지 않는다. `./gradlew test`(ArchUnit 포함)가 계속 통과해야 한다. 의존 방향 `api → application → domain ← infra`, `message → room.domain`만 허용, `domain`은 Spring을 모름.
- `usePolling.ts`, `merge.ts`, `PollingPanel.tsx`는 수정하지 않는다(세부 #10).
- E2E가 쓰는 이름은 유지한다: label `닉네임`, `방 이름`, `메시지` / 버튼 `새 사용자로 시작`, `방 만들기`, `입장`, `보내기`, `나가기` / `list` 이름 `대화` / URL `#/rooms/{id}`.
- 격리 수준은 각 DB 기본값. 측정·관찰 결과를 적을 때 "예상"과 "측정"을 구분한다.
- 주석은 "왜"만 쓴다. 이 계획의 세부를 근거로 하면 작업 중에는 `계획 7 세부 #n`으로 적고 작업 8에서 ADR 번호로 바꾼다. 타입만 가져올 때는 `import type`.

## 이 계획에서 새로 정하는 세부 (검토 필요, 승인되면 작업 8에서 ADR-129부터 기록)

| # | 항목 | 제안 | 이유 |
|---|---|---|---|
| 1 | 엔드포인트 | `/ws` 하나, 순수 `WebSocketHandler`(ADR-002). Origin 검사는 Spring 기본(같은 origin만, ADR-028). `setAllowedOrigins`를 부르지 않는다 | 다른 origin 허용 설정이 필요 없다. Vite proxy는 Host를 바꾸지 않으므로(`changeOrigin` 없음) 같은 origin으로 보인다(작업 8에서 확인) |
| 2 | 핸드셰이크 인증 | `auth/QueryUserIdHandshakeInterceptor`가 `userId`를 `Authenticator`에 넘기고 `AuthUser`를 세션 속성에 둔다. 실패 시 401로 거절하고 `AuthenticationFailedEvent(path=/ws)`를 발행해 감사에 남긴다 | ADR-006의 "통로마다 추출만 따로" 그대로. 존재하지 않는 사용자 id는 형식이 맞으면 통과한다(ADR-005와 같은 수준, DB 조회 없음) |
| 3 | 프로토콜 (JSON 텍스트 프레임) | C→S `{"type":"send","roomId":1,"content":"..."}` / S→C `{"type":"message","message":{id,roomId,senderId,content,createdAt}}` / S→C `{"type":"error","roomId":1,"code":"NOT_A_MEMBER","message":"..."}`(보낸 세션에만). 응답 짝 맞춤용 id는 두지 않는다(중복 방지 키로 번질 수 있어 ADR-034·F33 유지). 보낸 사람도 `message` push로 자기 메시지를 받는다. 잘못된 JSON·모르는 type·검증 실패는 `INVALID_REQUEST` error, 연결은 유지 | 화면은 REST와 같은 `Message` 모양을 그대로 `mergeMessages`로 합친다 |
| 4 | 전달(fan-out) | `MessageService.send`가 `MessageSentEvent`를 발행 → `message/application/MessageFanout`이 `@TransactionalEventListener`(AFTER_COMMIT)로 받아 `MembershipRepository.findUserIds(roomId)` 조회 → `MessagePusher.push(userIds, message, origin)`. 보내는 스레드에서 동기로 실행(F4 재현 조건). REST 전송과 WS 전송 모두 같은 경로. push 중 예외(`IOException`, `IllegalStateException`)는 애플리케이션 코드에서 잡지 않는다. Spring의 `afterCompletion` 경계에서 기록하고 요청자에게는 전파하지 않는다(F44). 닫힌 세션은 `isOpen()`으로 건너뛴다 | 롤백된 메시지를 보내지 않는다. 동기라 원인이 한 스레드에 보인다 |
| 5 | 패키지·의존 | `MessagePusher` 인터페이스는 `message/application`, 구현(세션 저장소·핸들러·프레임 처리·전송)은 `message/api/ws/`(api → application 방향 유지). `message → room.domain`만 쓰므로 ArchUnit 규칙 그대로 통과 | application이 WebSocket을 모른다 |
| 6 | 세션 저장소 | `WsSessionRegistry`: `HashMap<Long, List<WebSocketSession>>`(사용자 → 탭들), 목록은 `ArrayList`. `sessionsOf`는 내부 목록을 그대로 돌려준다 | D6, F3 재현 조건 |
| 7 | 관측 | 게이지 `chat.ws.sessions`(저장소의 세션 수), 카운터 `chat.ws.frames{type}`(`send`·`invalid` 받은 프레임, `message`·`error` 보낸 프레임). MDC는 핸드셰이크 HTTP 요청에만 있고, 프레임 처리에서는 세부 #7B가 새로 만든다. 비동기 전달 스레드로 이어지지 않는 문제(F30)는 미리 고치지 않는다 | |
| 7A | 전달 시간 측정 (AOP) | 표시용 애너테이션 `common/metrics/@DeliveryStage`를 진입점(REST 컨트롤러 `send`=`receive`, WS 프레임 처리 `ChatFrameHandler.handle`=`receive`), `MessageService.send`=`save`, `MessageFanout.on`=`fanout`, 세션별 전송 `WsFrameSender.send`=`push`에 붙인다. `common/metrics/DeliveryTimingAspect`(`@Around`)가 Micrometer 타이머 `chat.delivery.stage{stage,transport}`로 기록한다. 전체 시간은 진입점에서 잰 시작 시각(`System.nanoTime`)을 `MessageSentEvent`에 실어 보내고, aspect가 fanout이 끝날 때 `chat.delivery.total{transport}` = 마지막 push 완료 − 시작으로 기록한다(메서드 반환 시점과 무관해 비동기로 바꿔도 같은 뜻). 히스토그램(p50/p95/p99) 켬. 시작 시각은 `MessageService.send(userId, roomId, content, DeliveryOrigin origin)`으로 명시적으로 넘긴다(ThreadLocal은 비동기 전환 때 끊기므로 쓰지 않는다, F30과 같은 이유). **보완(계획 작성 중 정함, 검토 필요):** ① `DeliveryOrigin(transport, startedNanos)`는 Spring 없는 record로 `message/domain`에 둔다. 그래서 aspect(`common`)가 `message.domain`을 안다. ② push 단계에도 `transport` 태그가 있어야 Prometheus의 같은 이름 지표가 같은 태그 키를 가지므로 `MessagePusher.push`와 `WsFrameSender.send`가 `DeliveryOrigin`을 받는다. ③ 실험 코드 10여 곳이 서비스를 직접 부르므로 3인자 `send`는 남기고 `transport=internal`로 둔다. ④ aspect는 트랜잭션 프록시보다 바깥(`@Order(HIGHEST_PRECEDENCE + 1)`)이라 단계가 겹친다: receive ⊃ save(커밋과 동기 fanout 포함) ⊃ fanout ⊃ push. ⑤ 프록시가 가로채도록 붙이는 메서드는 모두 `public`이다(`MessageController.send`를 `public`으로 바꾼다) | 비동기로 바꿀 때 같은 지표로 비교한다. 단계가 겹친다는 점 자체가 동기·비동기의 차이를 보여 준다 |
| 7B | WS 로그 (AOP) | `message/api/ws/WsAccessLogAspect`가 `ChatWebSocketHandler`의 접속(`afterConnectionEstablished`)·종료(`afterConnectionClosed`)와 `ChatFrameHandler.handle`(프레임)을 감싸 `WS_ACCESS` 로거에 한 줄: `event`, `sessionId`, `frameType`, `roomId`, `result`(`ok`/오류 코드), `durationMs`, `closeCode`. `userId`·`requestId`는 MDC로 넣는다(ECS는 MDC와 key-value에 같은 필드가 있으면 거부한다, `AuditListener` 참고). 이벤트마다 서버가 새 요청 ID(UUID)를 만들어 MDC에 넣고 끝나면 지운다(HTTP와 같은 규칙). bench에서는 `ACCESS`처럼 OFF(ADR-022, F29). **보완:** 같은 객체 안 호출(self-invocation)은 프록시가 가로채지 못한다. `TextWebSocketHandler`는 `handleMessage` 안에서 `handleTextMessage`를 자기 호출하므로, 핸들러는 `WebSocketHandler`를 직접 구현하고 프레임 처리는 별도 빈 `ChatFrameHandler.handle`(반환값 `FrameOutcome`으로 결과 전달)로 뺀다. 접속 로그는 작업 1이 아니라 이 aspect(작업 13)가 남긴다 | 로그 코드가 핸들러 본문에 섞이지 않는다 |
| 8 | 프론트 연결 | `src/realtime/chatSocket.ts`(연결·재연결·구독·send) + App 수준 `ChatSocketProvider`(방을 옮겨도 연결 유지, ADR-082의 `key` 재생성과 분리). 끊기면 고정 1초 뒤 재연결(지수 대기·지터는 F17, Step 3). 재연결 뒤 놓친 메시지 따라잡기 없음(F6) | |
| 9 | `useRoomMessages` | 최초 조회·이전 메시지·입장·나가기는 REST 그대로. 수신은 상태가 `ready`가 된 뒤 소켓을 구독해 같은 방 `message`를 `mergeMessages`로 합친다. 전송은 소켓 `send`(연결이 열려 있지 않으면 연결 오류 문구, `false`). 다른 방 메시지는 무시한다(방 목록 자동 갱신 없음, ADR-084). 소켓 error 중 `NOT_A_MEMBER`는 `notMember` 상태로, 나머지는 서버 문구를 오류로 보인다 | |
| 10 | 폴링 | 코드(`usePolling.ts`, `merge.ts`)는 지우지도 고치지도 않는다. URL `?transport=polling`일 때만 기존 폴링으로 동작(Step 6 비교용). 기본은 `websocket`. polling 모드에서는 소켓을 열지 않는다 | |
| 11 | 상태 패널 | `<details>` "폴링 상태" → websocket 모드에서는 "연결 상태": 연결 상태(연결 중/연결됨/끊김)·재연결 횟수·받은/보낸 프레임 수·마지막 종료 코드. polling 모드에서는 기존 "폴링 상태" 패널 그대로 | 관측 도구(ADR-081)를 같은 자리에 둔다 |
| 12 | Vite | `/ws` proxy에 `ws: true`(architecture.md "같은 주소로 서비스하는 이유"의 예고대로) | |

## 예상되는 문제 (가설, 작업 8에서 `failure-lab.md`에 F43부터 기록. 미리 고치지 않는다)

| # | 가설 | 어디서 드러날지 |
|---|---|---|
| F43 | 같은 세션에 두 스레드가 동시에 `sendMessage`하면(두 사람이 동시에 보내 같은 수신자에게 push, 또는 push와 오류 프레임이 겹침) 전송 예외가 발생할 수 있다. 예외가 난 세션 뒤의 fan-out 대상은 push를 받지 못할 수 있다 | 작업 10 실험에서 예외 종류·후속 수신자·응답을 분리해 측정 |
| F44 | `AFTER_COMMIT` 리스너의 push 예외는 Spring의 `afterCompletion(COMMITTED)`에서 로그에 남고 요청자에게 전파되지 않는다(코드·프레임워크 계약 확인, 실제 실패 미재현). 저장된 메시지에 REST 201이 나가면서 일부 수신자의 화면에는 빠질 수 있다 | 작업 10에서 전송 실패를 주입해 DB 저장·REST/WS 결과·후속 수신자를 확인. 이전의 REST 500/WS 1011 예상은 철회 |
| F45 | 두 사람이 동시에 보내면 push 도착 순서가 id 순서와 다를 수 있다(F22와 같은 계열). 화면은 `mergeMessages`의 id 정렬로 보이므로 늦게 온 작은 id가 위쪽에 끼어든다 | 두 탭 동시 전송 관찰 |
| F46 | 나가기 커밋과 멤버 id 조회가 경쟁하면 막 나간 사용자의 탭에 push된다(F23 계열) | 나가기·전송 동시 실험 |
| F47 | 재연결 1초 고정이라 서버 재시작 때 모든 탭이 같은 순간에 다시 붙는다(F17 연결) | 작업 8 브라우저 확인(백엔드 재시작) |
| F48 | 단계별 타이머 aspect의 오버헤드(프록시 호출, 타이머 조회, 히스토그램)가 메시지당 지연에 섞인다 | 이후 부하 비교(W1~W5, ADR-128) 때 aspect 켬/끔 비교 |
| F49 | **(계획 작성 중 추가, 검토 필요)** 최초 조회 응답과 구독 시작(`ready` 뒤 effect) 사이, 그리고 나가기 직후 다시 입장할 때 온 push는 버려진다(F6의 축소판) | 단위 테스트로는 드러나지 않음, 작업 12에서 함께 관찰 |
| F50 | DB 저장 후 실시간 push가 실패해도 열린 WebSocket 화면은 누락을 감지하거나 재조회하지 않는다. 조회 계기가 없으면 표시 지연에 상한이 없다 | [장애 실험 목록](../../failure-lab.md)의 추후 결정 항목. 연결 유지와 재연결 사례를 나눠 관찰하고 허용 지연·재조회 계기·오류 표시 방식을 결정 |

## 파일 구조
```
backend/
 ├─ build.gradle.kts                                   (수정) websocket(작업 1), aspectj(작업 13) 스타터
 ├─ src/main/java/jissuo/chat/
 │   ├─ auth/QueryUserIdHandshakeInterceptor.java      (새) 쿼리 userId → Authenticator → 세션 속성
 │   ├─ common/metrics/DeliveryStage.java              (새, 작업 13) 표시용 애너테이션
 │   ├─ common/metrics/DeliveryTimingAspect.java       (새, 작업 13) 단계별·전체 타이머
 │   ├─ room/domain/MembershipRepository.java          (수정) findUserIds
 │   ├─ room/infra/jdbc/JdbcMembershipRepository.java  (수정)
 │   ├─ room/infra/jpa/{Jpa,SpringData}MembershipRepository.java (수정)
 │   ├─ message/domain/MessageSentEvent.java           (새) Spring 없는 record
 │   ├─ message/domain/DeliveryOrigin.java             (새) Spring 없는 record (transport, startedNanos)
 │   ├─ message/application/MessageService.java        (수정) 4인자 send, 이벤트 발행
 │   ├─ message/application/MessagePusher.java         (새) 인터페이스
 │   ├─ message/application/MessageFanout.java         (새) AFTER_COMMIT 리스너
 │   ├─ message/api/MessageController.java             (수정) DeliveryOrigin, public
 │   ├─ message/api/MessageResponse.java               (수정) from을 public으로 (ws 패키지에서 씀)
 │   └─ message/api/ws/
 │       ├─ WebSocketConfig.java                       (새) /ws 등록
 │       ├─ ChatWebSocketHandler.java                  (새) WebSocketHandler 직접 구현 (접속·프레임 위임·종료)
 │       ├─ WsSessionRegistry.java                     (새) HashMap 세션 저장소 + 게이지
 │       ├─ WsMessagePusher.java                       (새) MessagePusher 구현
 │       ├─ WsFrameSender.java                         (새) 세션 하나에 프레임 전송 (push 단계)
 │       ├─ ChatFrameHandler.java                      (새, 작업 4) 받은 프레임 처리
 │       ├─ FrameOutcome.java, SendFrame.java, MessageFrame.java, ErrorFrame.java (새)
 │       └─ WsAccessLogAspect.java                     (새, 작업 13)
 ├─ src/main/resources/application.yml, application-bench.yml (수정, 작업 13)
 └─ src/test/java/jissuo/chat/
     ├─ support/WsTestClient.java, ChatHttp.java       (새) 테스트용 WS 클라이언트·HTTP 도우미
     ├─ auth/QueryUserIdHandshakeInterceptorTest.java  (새)
     ├─ message/api/ws/WebSocketHandshakeTest.java     (새)
     ├─ message/api/ws/ChatWebSocketContract.java + {MySql,Postgres}ChatWebSocketTest.java (새)
     ├─ message/api/ws/ChatFrameHandlerTest.java, WsAccessLogAspectTest.java, WsAccessLogTest.java (새)
     ├─ common/metrics/DeliveryTimingAspectTest.java, DeliveryMetricsTest.java (새)
     ├─ room/infra/MembershipRepositoryContract.java   (수정) findUserIds 계약
     ├─ observe/BenchProfileTest.java                  (수정) WS_ACCESS OFF
     └─ experiment/ws/                                 (새) SessionRegistry·SlowConsumer·HalfOpen·ReconnectLoss 실험, StalledWsClient, SilentDropProxy
frontend/
 ├─ vite.config.ts                                     (수정) /ws proxy
 ├─ src/realtime/
 │   ├─ chatSocket.ts(+test)                           (새) 연결·재연결·구독·send
 │   ├─ transport.ts(+test)                            (새) ?transport=polling 판별
 │   └─ useChatSocket.ts(+test)                        (새) ChatSocketProvider, useChatSocket, useSocketStats
 ├─ src/test/fakeChatSocket.ts                         (새) 훅·화면 테스트용 가짜 소켓
 ├─ src/messages/useRoomMessages.ts(+test)             (수정) 수신·전송 경로, transport
 ├─ src/components/ConnectionPanel.tsx(+test)          (새)
 ├─ src/pages/ChatRoomPage.tsx(+test)                  (수정) 연결 상태 패널
 ├─ src/App.tsx(+test)                                 (수정) ChatSocketProvider
 └─ e2e/chat.spec.ts                                   (수정)
```

## 실행 순서와 병렬화

**의존 관계**

| 작업 | 선행 | 고치거나 만드는 파일 | 이유 |
|---|---|---|---|
| 1 WS 기반 | 없음 | `build.gradle.kts`, `auth/QueryUserIdHandshakeInterceptor*`, `message/api/ws/{WebSocketConfig,ChatWebSocketHandler,WsSessionRegistry}`, `support/WsTestClient`, `WebSocketHandshakeTest` | |
| 2 `findUserIds` | 없음 | `room/domain/MembershipRepository`, `room/infra/{jdbc,jpa}/*Membership*`, `MembershipRepositoryContract` | |
| 5 프론트 소켓 클라이언트 | 없음 | `frontend/src/realtime/chatSocket.*` | |
| 3 fan-out | 1, 2 | `message/domain/{MessageSentEvent,DeliveryOrigin}`, `message/application/{MessageService,MessagePusher,MessageFanout}`, `message/api/{MessageController,MessageResponse}`, `message/api/ws/{WsMessagePusher,WsFrameSender,MessageFrame}`, `support/ChatHttp`, `ChatWebSocketContract`+하위 클래스 | 저장소(1)와 `findUserIds`(2)를 쓴다 |
| 6 프론트 수신 전환 | 5 | `realtime/{transport,useChatSocket}.*`, `test/fakeChatSocket.ts`, `messages/useRoomMessages.*`, `components/ConnectionPanel.*`, `pages/ChatRoomPage.*`, `App.*`, `vite.config.ts` | |
| 4 WS 전송 처리 | 3 | `message/api/ws/{ChatFrameHandler,FrameOutcome,SendFrame,ErrorFrame,ChatWebSocketHandler}`, `ChatFrameHandlerTest`, `ChatWebSocketContract` | 3의 4인자 `send`를 쓴다 |
| 7 프론트 전송 전환 | 6 | `messages/useRoomMessages.*`, `pages/ChatRoomPage.test.tsx` | 6과 같은 파일 |
| 13 AOP 측정·WS 로그 | 3, 4 | `build.gradle.kts`, `common/metrics/*`, `message/api/ws/WsAccessLogAspect`, 애너테이션 붙이기(`MessageController`, `MessageService`, `MessageFanout`, `WsFrameSender`, `ChatFrameHandler`), `application*.yml`, 관련 테스트, `BenchProfileTest` | 붙일 메서드가 3·4에서 생긴다 |
| 8 E2E·브라우저·기록 | 1~7, 13 | `frontend/e2e/chat.spec.ts`, `docs/**`, `CLAUDE.md`, 코드 주석의 `계획 7 세부 #n` | 백엔드를 띄워야 한다 |
| 9 F3 재현 | 8 | `experiment/ws/SessionRegistryExperiment` | |
| 10 F4 재현 | 9 | `experiment/ws/{SlowConsumerExperiment,StalledWsClient}` | F3 보완 여부가 측정 조건이 된다 |
| 11 F5 재현 | 10 | `experiment/ws/{HalfOpenExperiment,SilentDropProxy}` | |
| 12 F6 재현 | 11 | `experiment/ws/ReconnectLossExperiment` | |

**웨이브**

| 웨이브 | 동시에 하는 작업 | 끝난 뒤 통합 확인 |
|---|---|---|
| 1 | 1, 2, 5 | `cd backend && ./gradlew test --tests 'jissuo.chat.auth.*' --tests 'jissuo.chat.message.api.ws.*' --tests 'jissuo.chat.room.infra.*' --tests 'jissuo.chat.ArchitectureTest'`, `cd frontend && npx vitest run && npx tsc -b && npm run lint` |
| 2 | 3, 6 | `cd backend && ./gradlew test`, 프론트 같은 세 명령 |
| 3 | 4, 7 | 같음 |
| 4 | 13 | `cd backend && ./gradlew test` |
| 5 | 8 | 작업 8 Step 6 |
| 6 | 9 | 작업 9 보고 |
| 7 | 10 | 작업 10 보고 |
| 8 | 11 | 작업 11 보고 |
| 9 | 12 | 작업 12 보고 |

**동시에 진행할 때의 규칙**
- 같은 작업 트리에서 진행한다(커밋하지 않으므로 worktree를 나누면 합칠 방법이 없다). **표에 없는 파일은 고치지 않는다.** 고쳐야 하면 멈추고 보고한다.
- 작업 중 확인은 자기 파일만 한다(`npx vitest run <파일>`, `npx eslint <파일>`, `./gradlew test --tests '<자기 테스트>'`). 전체 실행은 웨이브 끝의 통합 확인에서 한 번만 한다.
- **Gradle은 한 번에 하나만 실행한다.** 웨이브 1의 작업 1과 2는 파일이 겹치지 않아 코드는 동시에 써도 되지만, Gradle은 `src/main`·`src/test` 전체를 컴파일하고 `build/`를 함께 쓴다. 작업 2(작다)를 먼저 확인하고 작업 1을 확인한다. 상대 작업이 쓰다 만 파일 때문에 컴파일이 실패하면 그 작업이 끝날 때까지 기다린다.
- 각 작업은 끝나면 결과를 보고하고 멈춘다. 웨이브가 끝나면 통합 확인을 하고 결과를 한 번에 보고한 뒤 다음 웨이브 승인을 기다린다.

---

### 작업 1: WebSocket 기반 (엔드포인트, 핸드셰이크 인증, 세션 저장소, 게이지)

> 웨이브 1 · 선행 없음 · 2, 5와 동시 진행

**Files:**
- Modify: `backend/build.gradle.kts`
- Create: `backend/src/main/java/jissuo/chat/auth/QueryUserIdHandshakeInterceptor.java`
- Create: `backend/src/main/java/jissuo/chat/message/api/ws/{WebSocketConfig,ChatWebSocketHandler,WsSessionRegistry}.java`
- Create (test): `support/WsTestClient.java`, `auth/QueryUserIdHandshakeInterceptorTest.java`, `message/api/ws/WebSocketHandshakeTest.java`

**Interfaces:**
- Consumes: `Authenticator.authenticate(String) → AuthUser`(실패 시 `ChatException(UNAUTHENTICATED)`), `AuthenticationFailedEvent(String credential, String path, Instant at)`
- Produces:
```java
// auth
public class QueryUserIdHandshakeInterceptor implements HandshakeInterceptor {
    public static final String PARAMETER = "userId";
    public static final String ATTRIBUTE = AuthUser.class.getName();   // 세션 속성 키, 값은 AuthUser
}
// message/api/ws
public class WsSessionRegistry {
    public void add(long userId, WebSocketSession session);
    public void remove(long userId, WebSocketSession session);
    public List<WebSocketSession> sessionsOf(long userId);   // 내부 목록 그대로 (F3)
    public int count();
}
public class ChatWebSocketHandler implements WebSocketHandler { }   // 작업 4에서 프레임 위임 추가
// 게이지 chat.ws.sessions
// test support
public final class WsTestClient implements AutoCloseable {
    public static WsTestClient connect(int port, long userId) throws Exception;
    public static WsTestClient connectRaw(int port, String query) throws Exception;   // "userId=abc" 등
    public void send(String json) throws IOException;
    public String next() throws InterruptedException;          // 5초 안에 다음 프레임, 없으면 AssertionError
    public String poll(Duration wait) throws InterruptedException;   // 없으면 null
    public boolean isOpen();
    public CloseStatus awaitClosed() throws Exception;
    public void close() throws IOException;
}
```

- [x] **Step 1: 의존성 추가** — `build.gradle.kts`의 `dependencies`에 한 줄 추가(버전은 Spring Boot가 관리):
```kotlin
	implementation("org.springframework.boot:spring-boot-starter-websocket")
```
  Awaitility는 이미 테스트 클래스패스에 있다(`./gradlew dependencies --configuration testRuntimeClasspath`에서 `org.awaitility:awaitility:4.3.0` 확인). 컴파일 클래스패스에 없으면 `testImplementation("org.awaitility:awaitility")`를 추가하고 보고한다.

- [x] **Step 2: 테스트 클라이언트** — `backend/src/test/java/jissuo/chat/support/WsTestClient.java`
```java
package jissuo.chat.support;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** 서버가 보낸 텍스트 프레임을 큐에 모으는 테스트용 클라이언트. 실험에서도 쓴다. */
public final class WsTestClient implements AutoCloseable {

    // 연결마다 컨테이너를 새로 만들면 실험(수백 연결)에서 스레드가 크게 늘어 하나를 같이 쓴다
    private static final StandardWebSocketClient CLIENT = new StandardWebSocketClient();

    private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
    private final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
    private final WebSocketSession session;

    private WsTestClient(int port, String query) throws Exception {
        session = CLIENT.execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession s, TextMessage message) {
                frames.add(message.getPayload());
            }

            @Override
            public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
                closed.complete(status);
            }
        }, "ws://localhost:" + port + "/ws?" + query).get(5, TimeUnit.SECONDS);
    }

    public static WsTestClient connect(int port, long userId) throws Exception {
        return new WsTestClient(port, "userId=" + userId);
    }

    public static WsTestClient connectRaw(int port, String query) throws Exception {
        return new WsTestClient(port, query);
    }

    public void send(String json) throws IOException {
        session.sendMessage(new TextMessage(json));
    }

    public String next() throws InterruptedException {
        String frame = frames.poll(5, TimeUnit.SECONDS);
        if (frame == null) {
            throw new AssertionError("5초 안에 프레임이 오지 않았다");
        }
        return frame;
    }

    public String poll(Duration wait) throws InterruptedException {
        return frames.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
    }

    public boolean isOpen() {
        return session.isOpen();
    }

    public CloseStatus awaitClosed() throws Exception {
        return closed.get(5, TimeUnit.SECONDS);
    }

    @Override
    public void close() throws IOException {
        session.close();
    }
}
```

- [x] **Step 3: 실패하는 테스트 작성**

`backend/src/test/java/jissuo/chat/auth/QueryUserIdHandshakeInterceptorTest.java`
```java
package jissuo.chat.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

class QueryUserIdHandshakeInterceptorTest {

    final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    final Clock clock = Clock.fixed(Instant.parse("2026-10-08T00:00:00Z"), ZoneOffset.UTC);
    final QueryUserIdHandshakeInterceptor interceptor =
            new QueryUserIdHandshakeInterceptor(new HeaderUserIdAuthenticator(), events, clock);

    @Test
    void 올바른_id면_AuthUser를_세션_속성에_둔다() {
        MockHttpServletRequest servlet = new MockHttpServletRequest("GET", "/ws");
        servlet.setParameter("userId", "7");
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(new ServletServerHttpRequest(servlet),
                new ServletServerHttpResponse(new MockHttpServletResponse()), mock(WebSocketHandler.class), attributes);

        assertThat(accepted).isTrue();
        assertThat(attributes).containsEntry(QueryUserIdHandshakeInterceptor.ATTRIBUTE, new AuthUser(7));
        verifyNoInteractions(events);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "abc", "0", "007", "+5"})
    void 없거나_형식이_틀리면_401로_거절하고_인증_실패_이벤트를_발행한다(String userId) {
        MockHttpServletRequest servlet = new MockHttpServletRequest("GET", "/ws");
        if (userId != null) {
            servlet.setParameter("userId", userId);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean accepted = interceptor.beforeHandshake(new ServletServerHttpRequest(servlet),
                new ServletServerHttpResponse(response), mock(WebSocketHandler.class), new HashMap<>());

        assertThat(accepted).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        verify(events).publishEvent(new AuthenticationFailedEvent(userId, "/ws", clock.instant()));
    }
}
```

`backend/src/test/java/jissuo/chat/message/api/ws/WebSocketHandshakeTest.java`
```java
package jissuo.chat.message.api.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import jissuo.chat.support.MySqlContainerSupport;
import jissuo.chat.support.WsTestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
class WebSocketHandshakeTest {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }

    @LocalServerPort int port;
    @Autowired MeterRegistry meters;

    final ListAppender<ILoggingEvent> audit = new ListAppender<>();

    @BeforeEach
    void setUp() {
        audit.start();
        auditLogger().addAppender(audit);
    }

    @AfterEach
    void tearDown() {
        auditLogger().detachAppender(audit);
    }

    @Test
    void 올바른_id로_연결하면_세션_게이지가_늘고_닫으면_줄어든다() throws Exception {
        double before = sessions();
        // ADR-005: 형식만 본다. 사용자 7이 DB에 없어도 연결된다
        try (WsTestClient client = WsTestClient.connect(port, 7)) {
            assertThat(client.isOpen()).isTrue();
            await().atMost(Duration.ofSeconds(5)).until(() -> sessions() == before + 1);
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> sessions() == before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "userId=", "userId=abc", "userId=007"})
    void 쿼리의_id가_없거나_형식이_틀리면_401로_거절하고_감사에_남긴다(String query) {
        double before = sessions();

        assertThatThrownBy(() -> WsTestClient.connectRaw(port, query)).hasStackTraceContaining("401");

        await().atMost(Duration.ofSeconds(5)).until(() -> auditEvents().stream().anyMatch(e ->
                "AUTHENTICATION_FAILED".equals(value(e, "action")) && "/ws".equals(value(e, "path"))));
        assertThat(sessions()).isEqualTo(before);
    }

    private double sessions() {
        return meters.get("chat.ws.sessions").gauge().value();
    }

    private List<ILoggingEvent> auditEvents() {
        // 서버 스레드가 쓰는 중에 읽지 않도록 appender 잠금(AppenderBase.doAppend와 같은 객체)으로 복사한다
        synchronized (audit) {
            return List.copyOf(audit.list);
        }
    }

    private static String value(ILoggingEvent event, String key) {
        return event.getKeyValuePairs() == null ? null : event.getKeyValuePairs().stream()
                .filter(pair -> pair.key.equals(key)).map(pair -> String.valueOf(pair.value)).findFirst().orElse(null);
    }

    private static Logger auditLogger() {
        return (Logger) LoggerFactory.getLogger("AUDIT");
    }
}
```
  `LocalServerPort`의 패키지가 Spring Boot 4에서 다르면(컴파일 오류) 실제 위치로 고치고 보고한다.

- [x] **Step 4: 실패 확인** — `cd backend && ./gradlew test --tests 'jissuo.chat.auth.QueryUserIdHandshakeInterceptorTest' --tests 'jissuo.chat.message.api.ws.*'`
  - 예상: 컴파일 실패(`QueryUserIdHandshakeInterceptor` 없음).

- [x] **Step 5: 구현**

`backend/src/main/java/jissuo/chat/auth/QueryUserIdHandshakeInterceptor.java`
```java
package jissuo.chat.auth;

import java.time.Clock;
import java.util.Map;
import jissuo.chat.common.ChatException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * 브라우저 WebSocket API는 헤더를 붙일 수 없어 쿼리에서 꺼낸다. 판별은 HTTP와 같은 Authenticator가 한다
 * (계획 7 결정 D1, ADR-005·006과 같은 신뢰 수준). 핸드셰이크에서 한 번만 검사하고 프레임마다 다시 보지 않는다.
 */
@Component
public class QueryUserIdHandshakeInterceptor implements HandshakeInterceptor {

    public static final String PARAMETER = "userId";
    public static final String ATTRIBUTE = AuthUser.class.getName();

    private final Authenticator authenticator;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public QueryUserIdHandshakeInterceptor(Authenticator authenticator, ApplicationEventPublisher events, Clock clock) {
        this.authenticator = authenticator;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        // 서블릿이 디코딩한 값을 쓴다. 헤더와 같은 문자열 규칙(ADR-046)으로 판별되게 하려는 것이다
        String credential = ((ServletServerHttpRequest) request).getServletRequest().getParameter(PARAMETER);
        try {
            attributes.put(ATTRIBUTE, authenticator.authenticate(credential));
            return true;
        } catch (ChatException e) {
            events.publishEvent(new AuthenticationFailedEvent(credential, request.getURI().getPath(), clock.instant()));
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
```

`backend/src/main/java/jissuo/chat/message/api/ws/WsSessionRegistry.java`
```java
package jissuo.chat.message.api.ws;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * 사용자 → 탭들의 세션. 계획 7 결정 D6: 흔한 구현 그대로 스레드 안전하지 않은 HashMap·ArrayList를 쓴다.
 * 동시 접속·종료·push 순회가 겹칠 때의 문제(F3)를 재현한 뒤 사용자와 보완을 정한다.
 */
@Component
public class WsSessionRegistry {

    private final Map<Long, List<WebSocketSession>> sessions = new HashMap<>();

    public WsSessionRegistry(MeterRegistry meters) {
        Gauge.builder("chat.ws.sessions", this, WsSessionRegistry::count).register(meters);
    }

    public void add(long userId, WebSocketSession session) {
        sessions.computeIfAbsent(userId, id -> new ArrayList<>()).add(session);
    }

    public void remove(long userId, WebSocketSession session) {
        List<WebSocketSession> tabs = sessions.get(userId);
        if (tabs == null) {
            return;
        }
        tabs.remove(session);
        if (tabs.isEmpty()) {
            sessions.remove(userId);
        }
    }

    public List<WebSocketSession> sessionsOf(long userId) {
        return sessions.getOrDefault(userId, List.of());
    }

    public int count() {
        int count = 0;
        for (List<WebSocketSession> tabs : sessions.values()) {
            count += tabs.size();
        }
        return count;
    }
}
```

`backend/src/main/java/jissuo/chat/message/api/ws/ChatWebSocketHandler.java`
```java
package jissuo.chat.message.api.ws;

import jissuo.chat.auth.AuthUser;
import jissuo.chat.auth.QueryUserIdHandshakeInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * ADR-002: 순수 WebSocketHandler. TextWebSocketHandler를 상속하지 않는 이유는 handleMessage가 handleTextMessage를
 * 자기 호출해 AOP 프록시(계획 7 세부 7B)가 가로채지 못하기 때문이다.
 */
@Component
public class ChatWebSocketHandler implements WebSocketHandler {

    private final WsSessionRegistry sessions;

    public ChatWebSocketHandler(WsSessionRegistry sessions) {
        this.sessions = sessions;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(userOf(session).id(), session);
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
        // 작업 4에서 받은 프레임을 ChatFrameHandler로 넘긴다
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        // 오류 뒤에는 컨테이너가 연결을 닫고 afterConnectionClosed를 부르므로 여기서 지우지 않는다
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
        sessions.remove(userOf(session).id(), session);
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    static AuthUser userOf(WebSocketSession session) {
        return (AuthUser) session.getAttributes().get(QueryUserIdHandshakeInterceptor.ATTRIBUTE);
    }
}
```

`backend/src/main/java/jissuo/chat/message/api/ws/WebSocketConfig.java`
```java
package jissuo.chat.message.api.ws;

import jissuo.chat.auth.QueryUserIdHandshakeInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler handler;
    private final QueryUserIdHandshakeInterceptor authentication;

    public WebSocketConfig(ChatWebSocketHandler handler, QueryUserIdHandshakeInterceptor authentication) {
        this.handler = handler;
        this.authentication = authentication;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 계획 7 세부 1, ADR-028: 허용 origin을 지정하지 않아 Spring 기본(같은 origin만)을 쓴다
        registry.addHandler(handler, "/ws").addInterceptors(authentication);
    }
}
```

- [x] **Step 6: 통과 확인** — Step 4와 같은 명령 → PASS. 이어서 `./gradlew test --tests 'jissuo.chat.ArchitectureTest' --tests 'jissuo.chat.observe.*'`로 `@EnableWebSocket`이 MOCK 환경 테스트 컨텍스트를 깨지 않는지 확인한다.
  - `hasStackTraceContaining("401")`이 Tomcat 클라이언트의 실제 문구와 맞지 않으면 실제 예외 메시지를 보고하고 그 문구로 바꾼다(검사를 지우지 않는다).

- [x] **Step 7: 결과 보고 후 멈춤**

---

### 작업 2: 방 멤버 id 조회 `MembershipRepository.findUserIds`

> 웨이브 1 · 선행 없음 · 1, 5와 동시 진행(Gradle 실행은 작업 1과 번갈아)

**Files:**
- Modify: `room/domain/MembershipRepository.java`, `room/infra/jdbc/JdbcMembershipRepository.java`, `room/infra/jpa/JpaMembershipRepository.java`, `room/infra/jpa/SpringDataMembershipRepository.java`
- Modify (test): `room/infra/MembershipRepositoryContract.java` (JDBC·JPA × MySQL·PostgreSQL 하위 클래스 4개가 그대로 물려받는다)

**Interfaces:**
- Produces: `List<Long> MembershipRepository.findUserIds(long roomId)` — 그 방 멤버의 사용자 id, 오름차순. 멤버가 없으면 빈 목록.

- [x] **Step 1: 실패하는 테스트** — `MembershipRepositoryContract`의 `insertUser()` 위에 추가:
```java
    @Test
    void 방의_멤버_id를_오름차순으로_돌려준다() {
        long second = insertUser();
        long otherRoom = rooms.save(new RoomName("다른 방"), userId, AT);
        repository.save(new Membership(roomId, second, new JoinBoundary(0, AT)));
        repository.save(new Membership(roomId, userId, new JoinBoundary(0, AT)));
        repository.save(new Membership(otherRoom, insertUser(), new JoinBoundary(0, AT)));

        assertThat(repository.findUserIds(roomId)).containsExactly(userId, second);
    }

    @Test
    void 멤버가_없는_방은_빈_목록이다() {
        assertThat(repository.findUserIds(roomId)).isEmpty();
    }

    @Test
    void 나간_사용자는_멤버_id에서_빠진다() {
        // 계획 7 세부 4: push 대상은 커밋된 멤버 행 기준이다 (나가기와의 경쟁은 F46으로 남긴다)
        long second = insertUser();
        repository.save(new Membership(roomId, userId, new JoinBoundary(0, AT)));
        repository.save(new Membership(roomId, second, new JoinBoundary(0, AT)));
        repository.delete(roomId, userId);

        assertThat(repository.findUserIds(roomId)).containsExactly(second);
    }
```
  (`userId`는 `second`보다 먼저 만들어져 id가 더 작다.)

- [x] **Step 2: 실패 확인** — `./gradlew test --tests 'jissuo.chat.room.infra.*'` → 컴파일 실패(`findUserIds` 없음)

- [x] **Step 3: 구현**

`MembershipRepository`에 추가:
```java
    /** 계획 7 세부 4: fan-out 대상. 오름차순 */
    List<Long> findUserIds(long roomId);
```
  (`import java.util.List;` 추가)

`JdbcMembershipRepository`에 추가:
```java
    @Override
    public List<Long> findUserIds(long roomId) {
        // PK (room_id, user_id)의 앞부분으로 찾는다
        return jdbc.sql("SELECT user_id FROM room_members WHERE room_id = :roomId ORDER BY user_id")
                .param("roomId", roomId)
                .query(Long.class)
                .list();
    }
```

`SpringDataMembershipRepository`에 추가:
```java
    @Query("SELECT m.id.userId FROM MembershipEntity m WHERE m.id.roomId = :roomId ORDER BY m.id.userId")
    List<Long> findUserIds(long roomId);
```

`JpaMembershipRepository`에 추가:
```java
    @Override
    public List<Long> findUserIds(long roomId) {
        return memberships.findUserIds(roomId);
    }
```

- [x] **Step 4: 통과 확인** — `./gradlew test --tests 'jissuo.chat.room.*' --tests 'jissuo.chat.ArchitectureTest'` → PASS

- [x] **Step 5: 결과 보고 후 멈춤**

---

### 작업 5: 프론트 소켓 클라이언트 `chatSocket.ts`

> 웨이브 1 · 선행 없음 · 1, 2와 동시 진행

**Files:**
- Create: `frontend/src/realtime/chatSocket.ts`, `frontend/src/realtime/chatSocket.test.ts`

**Interfaces:**
- Produces:
```ts
export const RECONNECT_DELAY_MS = 1000
export type ServerFrame =
  | { type: 'message'; message: Message }
  | { type: 'error'; roomId: number | null; code: string; message: string }
export type ConnectionState = 'connecting' | 'open' | 'closed'
export type SocketStats = { state: ConnectionState; reconnects: number; received: number; sent: number; lastCloseCode: number | null }
export type ChatSocket = {
  start: () => void                  // 연결 시작 (이미 시작했으면 무시)
  stop: () => void                   // 닫고 다시 연결하지 않음
  send: (roomId: number, content: string) => boolean   // 열려 있지 않으면 false
  subscribe: (listener: (frame: ServerFrame) => void) => () => void
  watch: (listener: () => void) => () => void          // useSyncExternalStore용
  stats: () => SocketStats                              // 바뀔 때만 새 객체
}
export function socketUrl(userId: number, location?: Pick<Location, 'protocol' | 'host'>): string
export function createChatSocket(userId: number, options?: { url?: string; open?: (url: string) => WebSocket }): ChatSocket
```

- [x] **Step 1: 실패하는 테스트** — `frontend/src/realtime/chatSocket.test.ts`
```ts
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { RECONNECT_DELAY_MS, createChatSocket, socketUrl } from './chatSocket'
import type { ServerFrame } from './chatSocket'

class FakeWebSocket {
  static instances: FakeWebSocket[] = []
  url: string
  readyState = 0
  sent: string[] = []
  onopen: (() => void) | null = null
  onmessage: ((event: { data: string }) => void) | null = null
  onclose: ((event: { code: number }) => void) | null = null
  constructor(url: string) {
    this.url = url
    FakeWebSocket.instances.push(this)
  }
  send(data: string) { this.sent.push(data) }
  close() { this.readyState = 3; this.onclose?.({ code: 1000 }) }
  accept() { this.readyState = 1; this.onopen?.() }
  deliver(data: unknown) { this.onmessage?.({ data: typeof data === 'string' ? data : JSON.stringify(data) }) }
  drop(code: number) { this.readyState = 3; this.onclose?.({ code }) }
}

const open = (url: string) => new FakeWebSocket(url) as unknown as WebSocket
const URL_7 = 'ws://test/ws?userId=7'

function latest(): FakeWebSocket {
  const ws = FakeWebSocket.instances.at(-1)
  if (!ws) throw new Error('연결이 없다')
  return ws
}

const message: ServerFrame = { type: 'message', message: { id: 1, roomId: 1, senderId: 2, content: '안녕', createdAt: '2026-10-08T00:00:00Z' } }

describe('chatSocket', () => {
  beforeEach(() => {
    FakeWebSocket.instances = []
    vi.useFakeTimers()
  })
  afterEach(() => vi.useRealTimers())

  it('같은 origin의 /ws에 userId 쿼리로 연결한다', () => {
    expect(socketUrl(7, { protocol: 'http:', host: 'localhost:5173' })).toBe('ws://localhost:5173/ws?userId=7')
    expect(socketUrl(7, { protocol: 'https:', host: 'chat.example' })).toBe('wss://chat.example/ws?userId=7')
  })

  it('start 전에는 연결하지 않고, 열리기 전 send는 false', () => {
    const socket = createChatSocket(7, { url: URL_7, open })
    expect(FakeWebSocket.instances).toHaveLength(0)
    socket.start()
    socket.start()
    expect(FakeWebSocket.instances).toHaveLength(1)
    expect(latest().url).toBe(URL_7)
    expect(socket.stats().state).toBe('connecting')
    expect(socket.send(1, '안녕')).toBe(false)
    expect(latest().sent).toHaveLength(0)
  })

  it('send는 type·roomId·content JSON 한 프레임으로 보낸다', () => {
    const socket = createChatSocket(7, { url: URL_7, open })
    socket.start()
    latest().accept()
    expect(socket.send(3, '안녕\n😀')).toBe(true)
    expect(JSON.parse(latest().sent[0])).toEqual({ type: 'send', roomId: 3, content: '안녕\n😀' })
    expect(socket.stats()).toMatchObject({ state: 'open', sent: 1 })
  })

  it('받은 프레임을 구독자에게 넘기고, 구독을 해제하면 넘기지 않는다', () => {
    const socket = createChatSocket(7, { url: URL_7, open })
    const listener = vi.fn()
    const unsubscribe = socket.subscribe(listener)
    socket.start()
    latest().accept()
    latest().deliver(message)
    expect(listener).toHaveBeenCalledWith(message)
    unsubscribe()
    latest().deliver(message)
    expect(listener).toHaveBeenCalledTimes(1)
    expect(socket.stats().received).toBe(2)
  })

  it('JSON이 아닌 프레임은 세기만 하고 넘기지 않는다', () => {
    const socket = createChatSocket(7, { url: URL_7, open })
    const listener = vi.fn()
    socket.subscribe(listener)
    socket.start()
    latest().accept()
    latest().deliver('{')
    expect(listener).not.toHaveBeenCalled()
    expect(socket.stats().received).toBe(1)
  })

  it('끊기면 정확히 1초 뒤 다시 연결하고 횟수와 종료 코드를 남긴다', () => {
    const socket = createChatSocket(7, { url: URL_7, open })
    socket.start()
    latest().accept()
    latest().drop(1006)
    expect(socket.stats()).toMatchObject({ state: 'closed', lastCloseCode: 1006, reconnects: 0 })

    vi.advanceTimersByTime(RECONNECT_DELAY_MS - 1)
    expect(FakeWebSocket.instances).toHaveLength(1)
    vi.advanceTimersByTime(1)
    expect(FakeWebSocket.instances).toHaveLength(2)
    expect(socket.stats()).toMatchObject({ state: 'connecting', reconnects: 1 })
  })

  it('stop하면 닫고 다시 연결하지 않으며, 다시 start할 수 있다', () => {
    const socket = createChatSocket(7, { url: URL_7, open })
    socket.start()
    latest().accept()
    socket.stop()
    expect(latest().readyState).toBe(3)
    expect(socket.stats().state).toBe('closed')
    vi.advanceTimersByTime(RECONNECT_DELAY_MS * 5)
    expect(FakeWebSocket.instances).toHaveLength(1)

    socket.start()
    expect(FakeWebSocket.instances).toHaveLength(2)
  })

  it('watch는 상태가 바뀔 때마다 알리고 stats는 바뀔 때만 새 객체다', () => {
    const socket = createChatSocket(7, { url: URL_7, open })
    const watcher = vi.fn()
    socket.watch(watcher)
    const before = socket.stats()
    expect(socket.stats()).toBe(before)
    socket.start()
    latest().accept()
    expect(watcher).toHaveBeenCalled()
    expect(socket.stats()).not.toBe(before)
  })
})
```

- [x] **Step 2: 실패 확인** — `cd frontend && npx vitest run src/realtime/chatSocket.test.ts` → FAIL(모듈 없음)

- [x] **Step 3: 구현** — `frontend/src/realtime/chatSocket.ts`
```ts
import type { Message } from '../api/types'

export const RECONNECT_DELAY_MS = 1000
// WebSocket.OPEN. 테스트의 가짜 WebSocket에는 정적 상수가 없어서 값을 둔다
const OPEN = 1

export type ServerFrame =
  | { type: 'message'; message: Message }
  | { type: 'error'; roomId: number | null; code: string; message: string }
export type ConnectionState = 'connecting' | 'open' | 'closed'
export type SocketStats = { state: ConnectionState; reconnects: number; received: number; sent: number; lastCloseCode: number | null }
export type ChatSocket = {
  start: () => void
  stop: () => void
  send: (roomId: number, content: string) => boolean
  subscribe: (listener: (frame: ServerFrame) => void) => () => void
  watch: (listener: () => void) => () => void
  stats: () => SocketStats
}
type Options = { url?: string; open?: (url: string) => WebSocket }

// 계획 7 결정 D1: 브라우저 WebSocket은 헤더를 붙일 수 없어 쿼리로 보낸다. ADR-028: 같은 origin
export function socketUrl(userId: number, location: Pick<Location, 'protocol' | 'host'> = window.location): string {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
  return `${scheme}://${location.host}/ws?userId=${userId}`
}

export function createChatSocket(userId: number, options: Options = {}): ChatSocket {
  const url = options.url ?? socketUrl(userId)
  const open = options.open ?? ((target: string) => new WebSocket(target))
  const listeners = new Set<(frame: ServerFrame) => void>()
  const watchers = new Set<() => void>()
  let stats: SocketStats = { state: 'closed', reconnects: 0, received: 0, sent: 0, lastCloseCode: null }
  let current: WebSocket | null = null
  let timer: ReturnType<typeof setTimeout> | undefined
  let running = false

  function update(next: Partial<SocketStats>) {
    // useSyncExternalStore가 바뀐 것을 알도록 새 객체로 바꾼다
    stats = { ...stats, ...next }
    watchers.forEach((watcher) => watcher())
  }

  function connect() {
    const ws = open(url)
    current = ws
    update({ state: 'connecting' })
    // 닫은 뒤 늦게 오는 이전 연결의 이벤트가 새 연결의 상태를 바꾸지 않게 한다
    ws.onopen = () => {
      if (ws === current) update({ state: 'open' })
    }
    ws.onmessage = (event: MessageEvent) => {
      if (ws !== current) return
      update({ received: stats.received + 1 })
      let frame: ServerFrame
      try {
        frame = JSON.parse(String(event.data)) as ServerFrame
      } catch {
        return
      }
      listeners.forEach((listener) => listener(frame))
    }
    ws.onclose = (event: CloseEvent) => {
      if (ws !== current) return
      current = null
      update({ state: 'closed', lastCloseCode: event.code })
      if (!running) return
      // 계획 7 세부 8: 고정 1초 뒤 다시 연결한다. 지수 대기·지터는 F17(Step 3)에서,
      // 끊긴 동안 온 메시지는 따라잡지 않는다(F6, 장애 선행)
      timer = setTimeout(() => {
        update({ reconnects: stats.reconnects + 1 })
        connect()
      }, RECONNECT_DELAY_MS)
    }
  }

  return {
    start() {
      if (running) return
      running = true
      connect()
    },
    stop() {
      running = false
      clearTimeout(timer)
      const ws = current
      current = null
      ws?.close()
      update({ state: 'closed' })
    },
    send(roomId, content) {
      if (current?.readyState !== OPEN) return false
      current.send(JSON.stringify({ type: 'send', roomId, content }))
      update({ sent: stats.sent + 1 })
      return true
    },
    subscribe(listener) {
      listeners.add(listener)
      return () => { listeners.delete(listener) }
    },
    watch(watcher) {
      watchers.add(watcher)
      return () => { watchers.delete(watcher) }
    },
    stats: () => stats,
  }
}
```

- [x] **Step 4: 통과 확인** — `npx vitest run src/realtime/chatSocket.test.ts && npx eslint src/realtime` → PASS

- [x] **Step 5: 결과 보고 후 멈춤**

---

### 작업 3: fan-out (REST 전송 → 같은 방 멤버의 모든 탭에 push)

> 웨이브 2 · 선행: 1, 2 · 6과 동시 진행

**Files:**
- Create: `message/domain/{MessageSentEvent,DeliveryOrigin}.java`, `message/application/{MessagePusher,MessageFanout}.java`, `message/api/ws/{WsMessagePusher,WsFrameSender,MessageFrame}.java`
- Modify: `message/application/MessageService.java`, `message/api/MessageController.java`, `message/api/MessageResponse.java`(`from`을 `public`)
- Create (test): `support/ChatHttp.java`, `message/api/ws/ChatWebSocketContract.java`, `message/api/ws/MySqlChatWebSocketTest.java`, `message/api/ws/PostgresChatWebSocketTest.java`

**Interfaces:**
- Consumes: `WsSessionRegistry.sessionsOf(long)`(작업 1), `MembershipRepository.findUserIds(long)`(작업 2), `WsTestClient`(작업 1)
- Produces:
```java
// message/domain (Spring 없음)
public record DeliveryOrigin(String transport, long startedNanos) {
    public static DeliveryOrigin start(String transport);   // System.nanoTime()
}
public record MessageSentEvent(Message message, long startedNanos, String transport) { }
// message/application
public Message MessageService.send(long userId, long roomId, String content, DeliveryOrigin origin);
public Message MessageService.send(long userId, long roomId, String content);   // transport "internal" (실험 코드용)
public interface MessagePusher { void push(List<Long> userIds, Message message, DeliveryOrigin origin); }
public class MessageFanout { public void on(MessageSentEvent event); }   // AFTER_COMMIT
// message/api/ws
public class WsFrameSender { public void send(WebSocketSession session, TextMessage frame, DeliveryOrigin origin); }
record MessageFrame(String type, MessageResponse message)
// message/api
public static MessageResponse MessageResponse.from(Message message);
// test support
public final class ChatHttp {
    public ChatHttp(int port, JsonMapper json);
    public long createRoom(long userId, String name);
    public void join(long userId, long roomId);
    public HttpResponse<String> send(long userId, long roomId, String content);
    public HttpResponse<String> get(String path, long userId);
}
```
- transport 값: `rest`(REST 컨트롤러), `ws`(작업 4), `internal`(서비스 직접 호출)

- [x] **Step 1: HTTP 도우미** — `backend/src/test/java/jissuo/chat/support/ChatHttp.java`
```java
package jissuo.chat.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** 실제 포트로 REST를 부르는 테스트 도우미 (WebSocket 테스트는 RANDOM_PORT라 MockMvc를 쓰지 않는다). */
public final class ChatHttp {

    private final HttpClient client = HttpClient.newHttpClient();
    private final int port;
    private final JsonMapper json;

    public ChatHttp(int port, JsonMapper json) {
        this.port = port;
        this.json = json;
    }

    public long createRoom(long userId, String name) throws Exception {
        HttpResponse<String> response = post("/api/rooms", userId, json.writeValueAsString(Map.of("name", name)));
        return json.readTree(response.body()).at("/data/id").asLong();
    }

    public void join(long userId, long roomId) throws Exception {
        post("/api/rooms/" + roomId + "/members", userId, null);
    }

    public HttpResponse<String> send(long userId, long roomId, String content) throws Exception {
        return post("/api/rooms/" + roomId + "/messages", userId, json.writeValueAsString(Map.of("content", content)));
    }

    public HttpResponse<String> get(String path, long userId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-User-Id", Long.toString(userId)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, long userId, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-User-Id", Long.toString(userId));
        if (body == null) {
            request.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
```

- [x] **Step 2: 실패하는 테스트** — `backend/src/test/java/jissuo/chat/message/api/ws/ChatWebSocketContract.java`
```java
package jissuo.chat.message.api.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import jissuo.chat.support.ChatHttp;
import jissuo.chat.support.WsTestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 계획 7: WebSocket 수신·전송 계약. 두 DB에서 같은 결과를 보장한다 (JDBC 기본 저장소). */
abstract class ChatWebSocketContract {

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired MeterRegistry meters;

    final List<WsTestClient> clients = new ArrayList<>();
    ChatHttp http;
    long sender;
    long member;
    long stranger;
    long room;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.sql("DELETE FROM messages").update();
        jdbc.sql("DELETE FROM messages_b").update();
        jdbc.sql("DELETE FROM room_members").update();
        jdbc.sql("DELETE FROM rooms").update();
        jdbc.sql("DELETE FROM users").update();
        http = new ChatHttp(port, json);
        sender = addUser("보내는 사람");
        member = addUser("받는 사람");
        stranger = addUser("다른 사람");
        room = http.createRoom(sender, "실시간");
        http.join(member, room);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (WsTestClient client : clients) {
            client.close();
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> sessions() == 0);
    }

    @Test
    void REST로_보내면_같은_방_멤버의_모든_탭과_보낸_사람이_본문_전체를_받는다() throws Exception {
        WsTestClient memberTab1 = connect(member);
        WsTestClient memberTab2 = connect(member);
        WsTestClient senderTab = connect(sender);

        HttpResponse<String> response = http.send(sender, room, "안녕\n😀");

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode sent = json.readTree(response.body()).get("data");
        for (WsTestClient tab : List.of(memberTab1, memberTab2, senderTab)) {
            JsonNode frame = json.readTree(tab.next());
            assertThat(frame.get("type").asString()).isEqualTo("message");
            // 계획 7 결정 D3: REST 응답(MessageResponse)과 같은 모양
            assertThat(frame.get("message")).isEqualTo(sent);
        }
    }

    @Test
    void 비멤버와_다른_방_멤버는_받지_않는다() throws Exception {
        long otherRoom = http.createRoom(stranger, "다른 방");
        WsTestClient strangerTab = connect(stranger);
        WsTestClient memberTab = connect(member);

        http.send(sender, room, "우리 방");
        http.send(stranger, otherRoom, "다른 방 메시지");

        assertThat(json.readTree(memberTab.next()).at("/message/content").asString()).isEqualTo("우리 방");
        assertThat(json.readTree(strangerTab.next()).at("/message/content").asString()).isEqualTo("다른 방 메시지");
        assertThat(memberTab.poll(Duration.ofMillis(500))).isNull();
        assertThat(strangerTab.poll(Duration.ofMillis(500))).isNull();
    }

    @Test
    void 거절된_전송은_push하지_않는다() throws Exception {
        WsTestClient memberTab = connect(member);

        assertThat(http.send(stranger, room, "몰래").statusCode()).isEqualTo(403);

        assertThat(memberTab.poll(Duration.ofMillis(500))).isNull();
    }

    WsTestClient connect(long userId) throws Exception {
        int before = (int) sessions();
        WsTestClient client = WsTestClient.connect(port, userId);
        clients.add(client);
        // 클라이언트의 연결 완료와 서버의 저장소 등록은 순서가 보장되지 않는다
        await().atMost(Duration.ofSeconds(5)).until(() -> sessions() == before + 1);
        return client;
    }

    double sessions() {
        return meters.get("chat.ws.sessions").gauge().value();
    }

    long addUser(String nickname) {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO users (nickname, created_at) VALUES (:nickname, :at)")
                .param("nickname", nickname)
                .param("at", LocalDateTime.now(ZoneOffset.UTC))
                .update(keyHolder, "id");
        return keyHolder.getKey().longValue();
    }
}
```
  `MySqlChatWebSocketTest`·`PostgresChatWebSocketTest`:
```java
package jissuo.chat.message.api.ws;

import jissuo.chat.support.MySqlContainerSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
class MySqlChatWebSocketTest extends ChatWebSocketContract {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }
}
```
  (PostgreSQL은 `@ActiveProfiles("postgres")`, `PostgresContainerSupport.register`)
  - Jackson 3의 문자열 읽기는 `asString()`이다. 이름이 다르면 컴파일 오류를 보고 실제 이름으로 바꾼다.

- [x] **Step 3: 실패 확인** — `./gradlew test --tests 'jissuo.chat.message.api.ws.*ChatWebSocketTest'` → FAIL(프레임이 오지 않음: "5초 안에 프레임이 오지 않았다")

- [x] **Step 4: 구현**

`message/domain/DeliveryOrigin.java`
```java
package jissuo.chat.message.domain;

/**
 * 전달 시간을 재는 시작점 (계획 7 세부 7A). ThreadLocal은 비동기 전달로 바꿀 때 끊기므로(F30과 같은 이유) 값으로 넘긴다.
 * transport: rest | ws | internal(서비스를 직접 부르는 실험 코드)
 */
public record DeliveryOrigin(String transport, long startedNanos) {

    public static DeliveryOrigin start(String transport) {
        return new DeliveryOrigin(transport, System.nanoTime());
    }
}
```

`message/domain/MessageSentEvent.java`
```java
package jissuo.chat.message.domain;

/** 계획 7 세부 4·7A: fan-out 신호. 시작 시각과 통로를 실어 비동기로 바꿔도 전체 시간을 같은 뜻으로 잰다. */
public record MessageSentEvent(Message message, long startedNanos, String transport) {
}
```

`MessageService` — 기존 `send(long, long, String)`을 아래 두 메서드로 바꾼다(import `DeliveryOrigin`, `MessageSentEvent` 추가):
```java
    // 실험 코드(experiment/**)가 서비스를 직접 부른다. 전달 시간 지표에서 HTTP·WS와 섞이지 않게 통로를 따로 둔다.
    // 아래 메서드를 같은 객체 안에서 부르므로 트랜잭션은 이 메서드의 프록시가 연다
    @Transactional
    public Message send(long userId, long roomId, String content) {
        return send(userId, roomId, content, DeliveryOrigin.start("internal"));
    }

    @Transactional
    public Message send(long userId, long roomId, String content, DeliveryOrigin origin) {
        requireMembership(userId, roomId);
        Message saved = messages.save(roomId, userId, new MessageContent(content), clock.instant());
        // R7: 방 목록 정렬값은 메시지와 같은 트랜잭션에서 전진시킨다.
        rooms.advanceLastMessageId(roomId, saved.id());
        // 계획 7 세부 4: 롤백된 메시지를 보내지 않도록 리스너가 커밋 뒤에 받는다
        events.publishEvent(new MessageSentEvent(saved, origin.startedNanos(), origin.transport()));
        return saved;
    }
```

`message/application/MessagePusher.java`
```java
package jissuo.chat.message.application;

import java.util.List;
import jissuo.chat.message.domain.DeliveryOrigin;
import jissuo.chat.message.domain.Message;

/** 계획 7 세부 5: application은 WebSocket을 모른다. 구현은 message/api/ws에 있다. */
public interface MessagePusher {

    // origin은 push 단계 시간에 통로 태그를 붙이는 데 쓴다 (계획 7 세부 7A)
    void push(List<Long> userIds, Message message, DeliveryOrigin origin);
}
```

`message/application/MessageFanout.java`
```java
package jissuo.chat.message.application;

import jissuo.chat.message.domain.DeliveryOrigin;
import jissuo.chat.message.domain.Message;
import jissuo.chat.message.domain.MessageSentEvent;
import jissuo.chat.room.domain.MembershipRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class MessageFanout {

    private final MembershipRepository memberships;
    private final MessagePusher pusher;

    public MessageFanout(MembershipRepository memberships, MessagePusher pusher) {
        this.memberships = memberships;
        this.pusher = pusher;
    }

    // 계획 7 결정 D4: 커밋 뒤 보내는 스레드에서 동기로 보낸다. 한 명이 느리면 모두가 늦어지는 문제(F4)를
    // 재현하려고 비동기로 미리 바꾸지 않는다. 예외도 잡지 않는다(F44)
    @TransactionalEventListener
    public void on(MessageSentEvent event) {
        Message message = event.message();
        pusher.push(memberships.findUserIds(message.roomId()), message,
                new DeliveryOrigin(event.transport(), event.startedNanos()));
    }
}
```

`message/api/ws/MessageFrame.java`
```java
package jissuo.chat.message.api.ws;

import jissuo.chat.message.api.MessageResponse;

/** S→C {"type":"message","message":{...}} (계획 7 세부 3) */
record MessageFrame(String type, MessageResponse message) {

    static MessageFrame of(MessageResponse message) {
        return new MessageFrame("message", message);
    }
}
```

`message/api/ws/WsFrameSender.java`
```java
package jissuo.chat.message.api.ws;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import jissuo.chat.message.domain.DeliveryOrigin;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** 세션 하나에 프레임 하나를 보낸다. push 단계 시간(계획 7 세부 7A)을 세션마다 재려고 별도 빈으로 둔다. */
@Component
public class WsFrameSender {

    private final MeterRegistry meters;

    public WsFrameSender(MeterRegistry meters) {
        this.meters = meters;
    }

    // F4: 받는 쪽이 읽지 않으면 이 호출이 막혀 뒤의 세션과 보낸 사람의 응답이 함께 늦어진다
    // F43: 다른 스레드가 같은 세션에 쓰는 중이면 예외가 난다. 둘 다 재현 전이라 그대로 둔다
    public void send(WebSocketSession session, TextMessage frame, DeliveryOrigin origin) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(frame);
        } catch (IOException e) {
            // F44: 저장은 커밋됐다. AFTER_COMMIT 리스너 예외는 Spring이 로그에 남기고 요청자에게 전파하지 않는다
            throw new UncheckedIOException(e);
        }
        meters.counter("chat.ws.frames", "type", "message").increment();
    }
}
```

`message/api/ws/WsMessagePusher.java`
```java
package jissuo.chat.message.api.ws;

import java.util.List;
import jissuo.chat.message.api.MessageResponse;
import jissuo.chat.message.application.MessagePusher;
import jissuo.chat.message.domain.DeliveryOrigin;
import jissuo.chat.message.domain.Message;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

@Component
public class WsMessagePusher implements MessagePusher {

    private final WsSessionRegistry sessions;
    private final WsFrameSender sender;
    private final JsonMapper json;

    public WsMessagePusher(WsSessionRegistry sessions, WsFrameSender sender, JsonMapper json) {
        this.sessions = sessions;
        this.sender = sender;
        this.json = json;
    }

    @Override
    public void push(List<Long> userIds, Message message, DeliveryOrigin origin) {
        // 계획 7 결정 D3: 본문 전체를 한 번만 직렬화해 모든 탭에 같은 프레임을 보낸다
        TextMessage frame = new TextMessage(json.writeValueAsString(MessageFrame.of(MessageResponse.from(message))));
        for (long userId : userIds) {
            // F3: 스레드 안전하지 않은 목록을 복사하지 않고 그대로 순회한다 (재현 전)
            for (WebSocketSession session : sessions.sessionsOf(userId)) {
                sender.send(session, frame, origin);
            }
        }
    }
}
```

`MessageResponse.from`을 `public static`으로 바꾼다(다른 패키지 `message.api.ws`에서 쓴다).

`MessageController.send`를 바꾼다(import `jissuo.chat.message.domain.DeliveryOrigin` 추가):
```java
    @PostMapping("/api/rooms/{roomId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    // 계획 7 세부 7A: 작업 13의 AOP 프록시가 가로채도록 public으로 둔다
    public ApiResponse<MessageResponse> send(@CurrentUser AuthUser user, @PathVariable long roomId,
                                             @Valid @RequestBody SendMessageRequest request) {
        DeliveryOrigin origin = DeliveryOrigin.start("rest");
        return ApiResponse.ok(MessageResponse.from(messages.send(user.id(), roomId, request.content(), origin)));
    }
```

- [x] **Step 5: 통과 확인** — `./gradlew test --tests 'jissuo.chat.message.*' --tests 'jissuo.chat.ArchitectureTest'` → PASS (기존 `MessageApiContract` 4개 하위 클래스도 그대로 통과해야 한다)

- [x] **Step 6: 전체 확인** — `./gradlew test` → PASS. 실패하면 보고하고 멈춘다.
  - 기존 실험(`experiment/**`)은 이제 전송마다 커밋 뒤 `findUserIds` 조회가 한 번 늘어난다. 실험 결과를 계획 7 이전 값과 그대로 비교하지 않도록 작업 8에서 기록한다.

- [x] **Step 7: 결과 보고 후 멈춤**

---

### 작업 6: 프론트 수신 전환 (연결 공급자, transport 선택, 연결 상태 패널, Vite proxy)

> 웨이브 2 · 선행: 5 · 3과 동시 진행

**Files:**
- Create: `frontend/src/realtime/transport.ts`(+`.test.ts`), `frontend/src/realtime/useChatSocket.ts`(+`.test.tsx`), `frontend/src/test/fakeChatSocket.ts`, `frontend/src/components/ConnectionPanel.tsx`(+`.test.tsx`)
- Modify: `frontend/src/messages/useRoomMessages.ts`(+`.test.ts`), `frontend/src/pages/ChatRoomPage.tsx`(+`.test.tsx`), `frontend/src/App.tsx`(+`.test.tsx`), `frontend/vite.config.ts`

**Interfaces:**
- Consumes: `ChatSocket`, `SocketStats`, `ServerFrame`, `createChatSocket`(작업 5)
- Produces:
```ts
// realtime/transport.ts
export type Transport = 'websocket' | 'polling'
export function currentTransport(search?: string): Transport
// realtime/useChatSocket.ts
export const ChatSocketContext: React.Context<ChatSocket | null>
export function ChatSocketProvider(props: PropsWithChildren<{ userId: number; enabled: boolean }>): ReactElement
export function useChatSocket(): ChatSocket | null
export function useSocketStats(socket: ChatSocket | null): SocketStats | null
// messages/useRoomMessages.ts — RoomMessages에 추가
transport: Transport
connection: SocketStats | null
// test/fakeChatSocket.ts
export function fakeChatSocket(): { socket: ChatSocket; sent: { roomId: number; content: string }[]; push: (frame: ServerFrame) => void; disconnect: () => void }
```

- [x] **Step 1: 가짜 소켓** — `frontend/src/test/fakeChatSocket.ts`
```ts
import type { ChatSocket, ServerFrame, SocketStats } from '../realtime/chatSocket'

// 훅·화면 테스트용. 연결은 열려 있다고 보고 보낸 프레임을 모은다
export function fakeChatSocket() {
  const listeners = new Set<(frame: ServerFrame) => void>()
  const sent: { roomId: number; content: string }[] = []
  const stats: SocketStats = { state: 'open', reconnects: 0, received: 0, sent: 0, lastCloseCode: null }
  let connected = true
  const socket: ChatSocket = {
    start: () => {},
    stop: () => {},
    send: (roomId, content) => {
      if (!connected) return false
      sent.push({ roomId, content })
      return true
    },
    subscribe: (listener) => {
      listeners.add(listener)
      return () => { listeners.delete(listener) }
    },
    watch: () => () => {},
    stats: () => stats,
  }
  return {
    socket,
    sent,
    push: (frame: ServerFrame) => listeners.forEach((listener) => listener(frame)),
    disconnect: () => { connected = false },
  }
}
```

- [x] **Step 2: 실패하는 테스트**

`frontend/src/realtime/transport.test.ts`
```ts
import { describe, expect, it } from 'vitest'
import { currentTransport } from './transport'

describe('currentTransport', () => {
  it('기본은 websocket이고 ?transport=polling일 때만 polling', () => {
    expect(currentTransport('')).toBe('websocket')
    expect(currentTransport('?transport=polling')).toBe('polling')
    expect(currentTransport('?transport=websocket')).toBe('websocket')
    expect(currentTransport('?transport=other')).toBe('websocket')
  })
})
```

`frontend/src/realtime/useChatSocket.test.tsx`
```tsx
import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as chatSocket from './chatSocket'
import { ChatSocketProvider, useChatSocket } from './useChatSocket'
import { fakeChatSocket } from '../test/fakeChatSocket'

vi.mock('./chatSocket')

function Probe() {
  return <p>{useChatSocket() ? '소켓 있음' : '소켓 없음'}</p>
}

describe('ChatSocketProvider', () => {
  beforeEach(() => vi.resetAllMocks())

  it('enabled면 사용자 연결을 시작하고, 언마운트하면 멈춘다', () => {
    const fake = fakeChatSocket()
    const start = vi.spyOn(fake.socket, 'start')
    const stop = vi.spyOn(fake.socket, 'stop')
    vi.mocked(chatSocket.createChatSocket).mockReturnValue(fake.socket)
    const { unmount } = render(<ChatSocketProvider userId={3} enabled><Probe /></ChatSocketProvider>)
    expect(chatSocket.createChatSocket).toHaveBeenCalledWith(3)
    expect(start).toHaveBeenCalled()
    expect(screen.getByText('소켓 있음')).toBeInTheDocument()
    unmount()
    expect(stop).toHaveBeenCalled()
  })

  it('enabled가 아니면 연결하지 않고 소켓을 내주지 않는다 (계획 7 세부 10)', () => {
    const fake = fakeChatSocket()
    const start = vi.spyOn(fake.socket, 'start')
    vi.mocked(chatSocket.createChatSocket).mockReturnValue(fake.socket)
    render(<ChatSocketProvider userId={3} enabled={false}><Probe /></ChatSocketProvider>)
    expect(start).not.toHaveBeenCalled()
    expect(screen.getByText('소켓 없음')).toBeInTheDocument()
  })
})
```

`frontend/src/components/ConnectionPanel.test.tsx`
```tsx
import { render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ConnectionPanel } from './ConnectionPanel'

describe('ConnectionPanel', () => {
  it('연결 상태, 재연결 횟수, 프레임 수, 마지막 종료 코드를 보여 준다', () => {
    render(<ConnectionPanel stats={{ state: 'open', reconnects: 2, received: 7, sent: 3, lastCloseCode: 1006 }} />)
    const panel = within(screen.getByRole('complementary', { name: '연결 상태' }))
    expect(panel.getByText('연결됨')).toBeInTheDocument()
    expect(panel.getByText('2')).toBeInTheDocument()
    expect(panel.getByText('7')).toBeInTheDocument()
    expect(panel.getByText('3')).toBeInTheDocument()
    expect(panel.getByText('1006')).toBeInTheDocument()
  })

  it('종료 코드가 없으면 -', () => {
    render(<ConnectionPanel stats={{ state: 'connecting', reconnects: 0, received: 0, sent: 0, lastCloseCode: null }} />)
    expect(screen.getByText('연결 중')).toBeInTheDocument()
    expect(screen.getByText('-')).toBeInTheDocument()
  })
})
```

`useRoomMessages.test.ts` — 기존 `describe('useRoomMessages', ...)`의 `beforeEach`를 아래로 바꾸고(기존 테스트는 polling 모드 회귀가 된다), 파일 끝에 websocket 모드 `describe`를 추가한다:
```ts
import { createElement } from 'react'
import type { PropsWithChildren } from 'react'
import { ChatSocketContext } from '../realtime/useChatSocket'
import { fakeChatSocket } from '../test/fakeChatSocket'
import { afterEach } from 'vitest'   // 기존 import 줄에 합친다
```
```ts
  // 계획 7 세부 10: 기존 테스트는 ?transport=polling 회귀로 남긴다
  beforeEach(() => {
    vi.resetAllMocks()
    window.history.replaceState(null, '', '/?transport=polling')
  })
  afterEach(() => window.history.replaceState(null, '', '/'))
```
```ts
describe('useRoomMessages (websocket)', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    window.history.replaceState(null, '', '/')
  })

  function withSocket(socket: ReturnType<typeof fakeChatSocket>['socket']) {
    return ({ children }: PropsWithChildren) => createElement(ChatSocketContext.Provider, { value: socket }, children)
  }

  it('같은 방 push를 합치고 다른 방은 무시하며 폴링하지 않는다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const fake = fakeChatSocket()
    vi.mocked(chat.readMessages).mockResolvedValue(page([msg(1)]))
    const { result } = renderHook(() => useRoomMessages(1, 1), { wrapper: withSocket(fake.socket) })
    await waitFor(() => expect(result.current.status).toBe('ready'))
    expect(result.current.transport).toBe('websocket')

    act(() => fake.push({ type: 'message', message: msg(3) }))
    act(() => fake.push({ type: 'message', message: msg(2) }))
    act(() => fake.push({ type: 'message', message: { ...msg(4), roomId: 9 } }))
    expect(result.current.messages.map((m) => m.id)).toEqual([1, 2, 3])

    await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
    expect(chat.readMessages).toHaveBeenCalledTimes(1)
    expect(result.current.connection?.state).toBe('open')
    vi.useRealTimers()
  })

  it('ready가 되기 전(멤버 아님)에는 push를 합치지 않는다 (F49 관찰 대상)', async () => {
    const fake = fakeChatSocket()
    vi.mocked(chat.readMessages).mockRejectedValue(new ApiError(403, 'NOT_A_MEMBER', '멤버가 아닙니다.', { ...info, status: 403 }))
    const { result } = renderHook(() => useRoomMessages(1, 1), { wrapper: withSocket(fake.socket) })
    await waitFor(() => expect(result.current.status).toBe('notMember'))
    act(() => fake.push({ type: 'message', message: msg(5) }))
    expect(result.current.messages).toEqual([])
  })
})
```

`ChatRoomPage.test.tsx` — 기존 `beforeEach`에 `window.history.replaceState(null, '', '/?transport=polling')`을 넣고, 기존 `afterEach`를 `afterEach(() => { vi.useRealTimers(); window.history.replaceState(null, '', '/') })`로 바꾼다. 파일 끝에 추가:
```tsx
import { ChatSocketContext } from '../realtime/useChatSocket'
import { fakeChatSocket } from '../test/fakeChatSocket'
```
```tsx
describe('ChatRoomPage (websocket)', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(chat.listUsers).mockResolvedValue({ data: [], info })
    window.history.replaceState(null, '', '/')
  })

  it('push로 받은 메시지를 보이고, 연결 상태 패널을 보인다', async () => {
    const fake = fakeChatSocket()
    vi.mocked(chat.readMessages).mockResolvedValue(page([msg(10)]))
    render(<ChatSocketContext.Provider value={fake.socket}>
      <ChatRoomPage userId={1} roomId={1} title="잡담" onBack={vi.fn()} />
    </ChatSocketContext.Provider>)
    await screen.findByText('m10')

    act(() => fake.push({ type: 'message', message: msg(11, 3, '실시간') }))
    expect(screen.getByText('실시간')).toBeInTheDocument()
    expect(screen.queryByText('폴링 상태')).not.toBeInTheDocument()
    await userEvent.click(screen.getByText('연결 상태'))
    expect(within(screen.getByRole('complementary', { name: '연결 상태' })).getByText('연결됨')).toBeInTheDocument()
  })
})
```

`App.test.tsx` — `vi.mock` 줄 아래에 추가하고 `beforeEach`에 `window.history.replaceState(null, '', '/')`를 넣는다:
```tsx
import type { PropsWithChildren } from 'react'

vi.mock('./realtime/useChatSocket', () => ({
  ChatSocketProvider: ({ userId, enabled, children }: PropsWithChildren<{ userId: number; enabled: boolean }>) => (
    <div data-testid="socket" data-user={userId} data-enabled={String(enabled)}>{children}</div>
  ),
}))
```
```tsx
  it('사용자 연결을 열고, ?transport=polling이면 열지 않는다 (계획 7 세부 10)', () => {
    sessionStorage.setItem('chat.userId', '3')
    const { unmount } = render(<App />)
    expect(screen.getByTestId('socket')).toHaveAttribute('data-user', '3')
    expect(screen.getByTestId('socket')).toHaveAttribute('data-enabled', 'true')
    unmount()

    window.history.replaceState(null, '', '/?transport=polling')
    render(<App />)
    expect(screen.getByTestId('socket')).toHaveAttribute('data-enabled', 'false')
  })
```

- [x] **Step 3: 실패 확인** — `npx vitest run src/realtime src/components/ConnectionPanel.test.tsx src/messages/useRoomMessages.test.ts src/pages/ChatRoomPage.test.tsx src/App.test.tsx` → 새 테스트 FAIL(모듈 없음), 기존 polling 테스트는 PASS

- [x] **Step 4: 구현**

`frontend/src/realtime/transport.ts`
```ts
export type Transport = 'websocket' | 'polling'

// 계획 7 세부 10: 폴링은 Step 6 비교용으로 URL에서만 고른다. 기본은 websocket
export function currentTransport(search: string = window.location.search): Transport {
  return new URLSearchParams(search).get('transport') === 'polling' ? 'polling' : 'websocket'
}
```

`frontend/src/realtime/useChatSocket.ts`
```ts
import { createContext, createElement, useContext, useEffect, useState, useSyncExternalStore } from 'react'
import type { PropsWithChildren } from 'react'
import { createChatSocket } from './chatSocket'
import type { ChatSocket, SocketStats } from './chatSocket'

export const ChatSocketContext = createContext<ChatSocket | null>(null)

type Props = PropsWithChildren<{ userId: number; enabled: boolean }>

// 계획 7 세부 8: 방을 옮겨도 연결을 유지하도록 App 수준에 둔다. 방 화면은 ADR-082대로 key로 다시 만들어진다.
// 사용자가 바뀌면 App이 key로 다시 만든다
export function ChatSocketProvider({ userId, enabled, children }: Props) {
  const [socket] = useState(() => createChatSocket(userId))
  useEffect(() => {
    if (!enabled) return
    socket.start()
    return () => socket.stop()
  }, [socket, enabled])
  return createElement(ChatSocketContext.Provider, { value: enabled ? socket : null }, children)
}

export function useChatSocket(): ChatSocket | null {
  return useContext(ChatSocketContext)
}

const noSubscription = () => () => {}
const noStats = () => null

export function useSocketStats(socket: ChatSocket | null): SocketStats | null {
  return useSyncExternalStore(socket ? socket.watch : noSubscription, socket ? socket.stats : noStats)
}
```

`frontend/src/components/ConnectionPanel.tsx`
```tsx
import type { ConnectionState, SocketStats } from '../realtime/chatSocket'

const STATE_LABEL: Record<ConnectionState, string> = { connecting: '연결 중', open: '연결됨', closed: '끊김' }

type Props = { stats: SocketStats }

// 계획 7 세부 11: 폴링 패널(ADR-081)과 같은 자리에서 연결을 관찰한다. 종료 코드 1006은 비정상 종료다
export function ConnectionPanel({ stats }: Props) {
  return (
    <aside aria-label="연결 상태" className="panel">
      <dl>
        <dt>연결</dt>
        <dd>{STATE_LABEL[stats.state]}</dd>
        <dt>재연결</dt>
        <dd>{stats.reconnects}</dd>
        <dt>받은 프레임</dt>
        <dd>{stats.received}</dd>
        <dt>보낸 프레임</dt>
        <dd>{stats.sent}</dd>
        <dt>마지막 종료 코드</dt>
        <dd>{stats.lastCloseCode ?? '-'}</dd>
      </dl>
    </aside>
  )
}
```

`useRoomMessages.ts` — 바꾸는 곳만:
```ts
import { useChatSocket, useSocketStats } from '../realtime/useChatSocket'
import { currentTransport } from '../realtime/transport'
import type { Transport } from '../realtime/transport'
import type { SocketStats } from '../realtime/chatSocket'
```
```ts
export type RoomMessages = {
  // ...기존 필드 그대로...
  transport: Transport
  polling: PollingControls
  connection: SocketStats | null
}
```
  함수 본문 맨 위에:
```ts
  const [transport] = useState(currentTransport)
  const socket = useChatSocket()
  const connection = useSocketStats(socket)
```
  `usePolling` 호출을 바꾼다:
```ts
  const stats = usePolling({ enabled: transport === 'polling' && status === 'ready' && !paused, intervalMs, poll })
```
  `usePolling` 호출 아래에 추가:
```ts
  useEffect(() => {
    if (transport !== 'websocket' || socket === null || status !== 'ready') return
    // 계획 7 세부 9: 다른 방 메시지는 무시한다(방 목록 자동 갱신 없음, ADR-084). 내 메시지도 push로 받는다.
    // 구독 전에 온 push는 받지 않는다(F49, 장애 선행)
    return socket.subscribe((frame) => {
      if (frame.type !== 'message' || frame.message.roomId !== roomId) return
      setMessages((current) => mergeMessages(current, [frame.message]))
    })
  }, [transport, socket, status, roomId])
```
  `return` 객체에 `transport,`와 `connection,`을 추가한다.

`ChatRoomPage.tsx` — `<details className="debug">` 블록을 바꾼다(import `ConnectionPanel` 추가):
```tsx
            {/* ADR-114: 관측용 패널(ADR-081)은 남기되 평소에는 접어 둔다. 계획 7 세부 11: 통로에 따라 내용이 다르다 */}
            <details className="debug">
              <summary>{room.transport === 'polling' ? '폴링 상태' : '연결 상태'}</summary>
              {room.transport === 'polling' ? (
                <PollingPanel stats={room.polling.stats} cursor={room.polling.cursor} intervalMs={room.polling.intervalMs}
                  paused={room.polling.paused} onIntervalChange={room.polling.setIntervalMs} onTogglePause={room.polling.togglePause} />
              ) : (
                room.connection && <ConnectionPanel stats={room.connection} />
              )}
            </details>
```

`App.tsx` — import 두 줄을 추가하고 `NicknameProvider` 안쪽을 감싼다:
```tsx
import { currentTransport } from './realtime/transport'
import { ChatSocketProvider } from './realtime/useChatSocket'
```
```tsx
    <NicknameProvider key={userId}>
      {/* 계획 7 세부 8·10: 사용자 연결은 탭마다 하나. polling 모드에서는 열지 않는다 */}
      <ChatSocketProvider key={userId} userId={userId} enabled={currentTransport() === 'websocket'}>
        {/* 기존 <div className="app" ...> 전체를 그대로 이 안에 둔다 */}
      </ChatSocketProvider>
    </NicknameProvider>
```

`vite.config.ts`의 `proxy`에 추가:
```ts
      // 계획 7 세부 12: WebSocket 업그레이드도 같은 주소로 받아 백엔드로 넘긴다 (ADR-028)
      '/ws': { target: 'ws://localhost:8080', ws: true, xfwd: true },
```

- [x] **Step 5: 통과 확인** — `npx vitest run && npx tsc -b && npm run lint` → PASS (기존 테스트 포함)

- [x] **Step 6: 결과 보고 후 멈춤**

---

### 작업 4: WS 전송 처리 (send 프레임 → `MessageService.send`, 오류 프레임)

> 웨이브 3 · 선행: 3 · 7과 동시 진행

**Files:**
- Create: `message/api/ws/{ChatFrameHandler,FrameOutcome,SendFrame,ErrorFrame}.java`
- Modify: `message/api/ws/ChatWebSocketHandler.java`(프레임 위임)
- Create (test): `message/api/ws/ChatFrameHandlerTest.java`
- Modify (test): `message/api/ws/ChatWebSocketContract.java`(WS 전송 계약 추가)

**Interfaces:**
- Consumes: `MessageService.send(long, long, String, DeliveryOrigin)`(작업 3), `SendMessageRequest`(입력 규칙 재사용, ADR-045·048·052), `ErrorCode`
- Produces:
```java
public class ChatFrameHandler {
    public FrameOutcome handle(WebSocketSession session, String payload) throws IOException;
}
public record FrameOutcome(String type, Long roomId, String result) {   // result: "ok" 또는 ErrorCode 이름
    public static FrameOutcome ok(String type, Long roomId);
    public static FrameOutcome rejected(String type, Long roomId, ErrorCode code);
}
record SendFrame(String type, Long roomId, String content)
record ErrorFrame(String type, Long roomId, String code, String message)
```
- 받은 프레임 `type` 값: 올바른 send는 `send`, JSON이 아니거나 모르는 type은 `invalid`(카운터·로그 태그)

- [ ] **Step 1: 실패하는 테스트**

`backend/src/test/java/jissuo/chat/message/api/ws/ChatFrameHandlerTest.java` (DB 없는 단위 테스트)
```java
package jissuo.chat.message.api.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import jissuo.chat.auth.AuthUser;
import jissuo.chat.auth.QueryUserIdHandshakeInterceptor;
import jissuo.chat.common.ChatException;
import jissuo.chat.common.ErrorCode;
import jissuo.chat.message.application.MessageService;
import jissuo.chat.message.domain.DeliveryOrigin;
import jissuo.chat.message.domain.Message;
import jissuo.chat.message.domain.MessageContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

class ChatFrameHandlerTest {

    final MessageService messages = mock(MessageService.class);
    final JsonMapper json = JsonMapper.builder().build();
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final ChatFrameHandler handler = new ChatFrameHandler(messages, json,
            Validation.buildDefaultValidatorFactory().getValidator(), meters);
    final WebSocketSession session = mock(WebSocketSession.class);

    @BeforeEach
    void setUp() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(QueryUserIdHandshakeInterceptor.ATTRIBUTE, new AuthUser(7));
        when(session.getAttributes()).thenReturn(attributes);
    }

    @Test
    void send_프레임은_서비스에_ws_통로로_넘기고_응답_프레임은_보내지_않는다() throws Exception {
        when(messages.send(eq(7L), eq(3L), eq("안녕"), any(DeliveryOrigin.class)))
                .thenReturn(new Message(1, 3, 7, new MessageContent("안녕"), Instant.now()));

        FrameOutcome outcome = handler.handle(session, "{\"type\":\"send\",\"roomId\":3,\"content\":\"안녕\"}");

        assertThat(outcome).isEqualTo(FrameOutcome.ok("send", 3L));
        ArgumentCaptor<DeliveryOrigin> origin = ArgumentCaptor.forClass(DeliveryOrigin.class);
        verify(messages).send(eq(7L), eq(3L), eq("안녕"), origin.capture());
        assertThat(origin.getValue().transport()).isEqualTo("ws");
        // 계획 7 세부 3: 응답 짝 맞춤 없이 보낸 사람도 message push로 받는다
        verify(session, never()).sendMessage(any());
        assertThat(meters.get("chat.ws.frames").tag("type", "send").counter().count()).isEqualTo(1);
    }

    @Test
    void 서비스가_거절하면_보낸_세션에만_error_프레임을_보내고_연결은_유지한다() throws Exception {
        when(messages.send(anyLong(), anyLong(), anyString(), any(DeliveryOrigin.class)))
                .thenThrow(new ChatException(ErrorCode.NOT_A_MEMBER));

        FrameOutcome outcome = handler.handle(session, "{\"type\":\"send\",\"roomId\":3,\"content\":\"안녕\"}");

        assertThat(outcome).isEqualTo(FrameOutcome.rejected("send", 3L, ErrorCode.NOT_A_MEMBER));
        assertThat(sentFrame()).isEqualTo(
                "{\"type\":\"error\",\"roomId\":3,\"code\":\"NOT_A_MEMBER\",\"message\":\"이 채팅방의 멤버가 아닙니다.\"}");
        verify(session, never()).close(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{", "[]", "{\"type\":\"hello\"}", "{\"type\":\"send\",\"content\":\"안녕\"}",
            "{\"type\":\"send\",\"roomId\":\"abc\",\"content\":\"안녕\"}",
            "{\"type\":\"send\",\"roomId\":3}", "{\"type\":\"send\",\"roomId\":3,\"content\":\"\"}",
            "{\"type\":\"send\",\"roomId\":3,\"content\":\"a\\u0000b\"}"})
    void 잘못된_프레임은_INVALID_REQUEST_error를_보내고_서비스를_부르지_않는다(String payload) throws Exception {
        FrameOutcome outcome = handler.handle(session, payload);

        assertThat(outcome.result()).isEqualTo("INVALID_REQUEST");
        assertThat(json.readTree(sentFrame()).get("code").asString()).isEqualTo("INVALID_REQUEST");
        verify(messages, never()).send(anyLong(), anyLong(), anyString(), any(DeliveryOrigin.class));
    }

    private String sentFrame() throws Exception {
        ArgumentCaptor<TextMessage> frame = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(frame.capture());
        return frame.getValue().getPayload();
    }
}
```
  - `ErrorFrame`의 JSON 필드 순서는 record 선언 순서(`type, roomId, code, message`)다. 순서가 다르게 나오면 문자열 비교 대신 필드별 비교로 바꾸고 보고한다.

`ChatWebSocketContract`에 추가:
```java
    @Test
    void WS로_보내면_저장되고_같은_방_멤버와_보낸_사람이_push로_받는다() throws Exception {
        WsTestClient senderTab = connect(sender);
        WsTestClient memberTab = connect(member);

        senderTab.send("{\"type\":\"send\",\"roomId\":" + room + ",\"content\":\"웹소켓으로\"}");

        JsonNode toMember = json.readTree(memberTab.next());
        JsonNode toSender = json.readTree(senderTab.next());
        assertThat(toMember.at("/message/content").asString()).isEqualTo("웹소켓으로");
        assertThat(toSender.get("message")).isEqualTo(toMember.get("message"));
        long id = toMember.at("/message/id").asLong();
        // REST 조회로도 같은 메시지가 보인다 (저장 경로가 같다)
        JsonNode page = json.readTree(http.get("/api/rooms/" + room + "/messages", member).body());
        assertThat(page.at("/data/messages/0/id").asLong()).isEqualTo(id);
    }

    @Test
    void 비멤버의_WS_전송은_보낸_세션에만_NOT_A_MEMBER이고_연결은_유지된다() throws Exception {
        WsTestClient strangerTab = connect(stranger);
        WsTestClient memberTab = connect(member);

        strangerTab.send("{\"type\":\"send\",\"roomId\":" + room + ",\"content\":\"몰래\"}");

        JsonNode error = json.readTree(strangerTab.next());
        assertThat(error.get("type").asString()).isEqualTo("error");
        assertThat(error.get("code").asString()).isEqualTo("NOT_A_MEMBER");
        assertThat(error.get("roomId").asLong()).isEqualTo(room);
        assertThat(memberTab.poll(Duration.ofMillis(500))).isNull();
        assertThat(strangerTab.isOpen()).isTrue();
    }

    @Test
    void 없는_방으로_보내면_REST와_같이_NOT_A_MEMBER다() throws Exception {
        // 서비스는 멤버 행으로만 판단하므로(R5) 없는 방도 멤버가 아니다. ROOM_NOT_FOUND는 입장에서만 나온다
        WsTestClient senderTab = connect(sender);

        senderTab.send("{\"type\":\"send\",\"roomId\":999999,\"content\":\"없는 방\"}");

        assertThat(json.readTree(senderTab.next()).get("code").asString()).isEqualTo("NOT_A_MEMBER");
    }

    @Test
    void 잘못된_JSON은_INVALID_REQUEST이고_연결을_유지해_다음_전송이_된다() throws Exception {
        WsTestClient senderTab = connect(sender);

        senderTab.send("{");
        assertThat(json.readTree(senderTab.next()).get("code").asString()).isEqualTo("INVALID_REQUEST");
        senderTab.send("{\"type\":\"send\",\"roomId\":" + room + ",\"content\":\"그다음\"}");

        assertThat(json.readTree(senderTab.next()).at("/message/content").asString()).isEqualTo("그다음");
    }
```

- [ ] **Step 2: 실패 확인** — `./gradlew test --tests 'jissuo.chat.message.api.ws.*'` → 컴파일 실패(`ChatFrameHandler` 없음)

- [ ] **Step 3: 구현**

`message/api/ws/SendFrame.java`, `ErrorFrame.java`, `FrameOutcome.java`
```java
package jissuo.chat.message.api.ws;

/** C→S {"type":"send","roomId":1,"content":"..."} (계획 7 세부 3). 응답 짝 맞춤 id는 두지 않는다(ADR-034, F33) */
record SendFrame(String type, Long roomId, String content) {
}
```
```java
package jissuo.chat.message.api.ws;

import jissuo.chat.common.ErrorCode;

/** S→C 오류. 보낸 세션에만 보낸다 (계획 7 세부 3). 문구는 REST의 ApiResponse와 같다 */
record ErrorFrame(String type, Long roomId, String code, String message) {

    static ErrorFrame of(Long roomId, ErrorCode code) {
        return new ErrorFrame("error", roomId, code.name(), code.message());
    }
}
```
```java
package jissuo.chat.message.api.ws;

import jissuo.chat.common.ErrorCode;

/** 프레임 처리 결과. WS 접속 로그(계획 7 세부 7B)가 반환값으로 결과를 알도록 둔다 */
public record FrameOutcome(String type, Long roomId, String result) {

    public static FrameOutcome ok(String type, Long roomId) {
        return new FrameOutcome(type, roomId, "ok");
    }

    public static FrameOutcome rejected(String type, Long roomId, ErrorCode code) {
        return new FrameOutcome(type, roomId, code.name());
    }
}
```

`message/api/ws/ChatFrameHandler.java`
```java
package jissuo.chat.message.api.ws;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Validator;
import java.io.IOException;
import jissuo.chat.auth.AuthUser;
import jissuo.chat.common.ChatException;
import jissuo.chat.common.ErrorCode;
import jissuo.chat.message.api.SendMessageRequest;
import jissuo.chat.message.application.MessageService;
import jissuo.chat.message.domain.DeliveryOrigin;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 받은 텍스트 프레임 하나를 처리한다. 핸들러 안의 메서드로 두면 같은 객체 안 호출이라 AOP가 가로채지 못하므로
 * 별도 빈의 public 메서드로 둔다 (계획 7 세부 7A·7B).
 */
@Component
public class ChatFrameHandler {

    private final MessageService messages;
    private final JsonMapper json;
    private final Validator validator;
    private final MeterRegistry meters;

    public ChatFrameHandler(MessageService messages, JsonMapper json, Validator validator, MeterRegistry meters) {
        this.messages = messages;
        this.json = json;
        this.validator = validator;
        this.meters = meters;
    }

    public FrameOutcome handle(WebSocketSession session, String payload) throws IOException {
        DeliveryOrigin origin = DeliveryOrigin.start("ws");
        AuthUser user = ChatWebSocketHandler.userOf(session);
        SendFrame frame;
        try {
            frame = json.readValue(payload, SendFrame.class);
        } catch (JacksonException e) {
            return reject(session, "invalid", null, ErrorCode.INVALID_REQUEST);
        }
        // ADR-045·048·052: REST 본문과 같은 입력 규칙을 한 곳(SendMessageRequest)에서 검사한다
        if (frame == null || !"send".equals(frame.type()) || frame.roomId() == null
                || !validator.validate(new SendMessageRequest(frame.content())).isEmpty()) {
            return reject(session, "invalid", frame == null ? null : frame.roomId(), ErrorCode.INVALID_REQUEST);
        }
        count("send");
        try {
            messages.send(user.id(), frame.roomId(), frame.content(), origin);
            return FrameOutcome.ok("send", frame.roomId());
            // 서비스 본문의 예외는 잡지 않는다. AFTER_COMMIT push 예외(F44)는 리스너 경계에서 로그에 남는다
        } catch (ChatException e) {
            return reject(session, "send", frame.roomId(), e.errorCode());
        }
    }

    private FrameOutcome reject(WebSocketSession session, String type, Long roomId, ErrorCode code) throws IOException {
        if ("invalid".equals(type)) {
            count("invalid");
        }
        // 계획 7 세부 3: 오류는 보낸 세션에만 보내고 연결은 유지한다.
        // 같은 세션에 다른 스레드가 push하는 중이면 겹쳐 쓰기 예외가 날 수 있다(F43, 재현 전)
        session.sendMessage(new TextMessage(json.writeValueAsString(ErrorFrame.of(roomId, code))));
        count("error");
        return FrameOutcome.rejected(type, roomId, code);
    }

    private void count(String type) {
        meters.counter("chat.ws.frames", "type", type).increment();
    }
}
```

`ChatWebSocketHandler` — 생성자에 `ChatFrameHandler frames`를 받고 `handleMessage`를 바꾼다:
```java
    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        // 계획 7 세부 3: 텍스트 프레임만 쓴다. 바이너리·pong은 무시한다(ping/pong 없음, F5)
        if (message instanceof TextMessage text) {
            frames.handle(session, text.getPayload());
        }
    }
```

- [ ] **Step 4: 통과 확인** — `./gradlew test --tests 'jissuo.chat.message.*' --tests 'jissuo.chat.ArchitectureTest'` → PASS

- [ ] **Step 5: 결과 보고 후 멈춤**

---

### 작업 7: 프론트 전송 전환 (소켓 send, 오류 프레임)

> 웨이브 3 · 선행: 6 · 4와 동시 진행

**Files:**
- Modify: `frontend/src/messages/useRoomMessages.ts`(+`.test.ts`), `frontend/src/pages/ChatRoomPage.test.tsx`

**Interfaces:**
- Consumes: `ChatSocket.send`, `ServerFrame`의 `error`(작업 5), `CONNECTION_ERROR`(`api/client.ts`)
- Produces: `RoomMessages.send`의 시그니처는 그대로 `(content: string) => Promise<boolean>`

- [ ] **Step 1: 실패하는 테스트** — `useRoomMessages.test.ts`의 `describe('useRoomMessages (websocket)')`에 추가:
```ts
  it('send는 소켓으로 보내고 true, REST는 부르지 않으며 내 메시지는 push로만 합친다', async () => {
    const fake = fakeChatSocket()
    vi.mocked(chat.readMessages).mockResolvedValue(page([msg(1)]))
    const { result } = renderHook(() => useRoomMessages(1, 1), { wrapper: withSocket(fake.socket) })
    await waitFor(() => expect(result.current.status).toBe('ready'))

    let ok = false
    await act(async () => { ok = await result.current.send('안녕') })
    expect(ok).toBe(true)
    expect(fake.sent).toEqual([{ roomId: 1, content: '안녕' }])
    expect(chat.sendMessage).not.toHaveBeenCalled()
    // 계획 7 세부 3: 낙관적 표시 없음(F33). 서버 push가 와야 보인다
    expect(result.current.messages.map((m) => m.id)).toEqual([1])
    act(() => fake.push({ type: 'message', message: msg(2, 1) }))
    expect(result.current.messages.map((m) => m.id)).toEqual([1, 2])
  })

  it('연결이 열려 있지 않으면 연결 오류 문구를 보이고 false', async () => {
    const fake = fakeChatSocket()
    fake.disconnect()
    vi.mocked(chat.readMessages).mockResolvedValue(page([]))
    const { result } = renderHook(() => useRoomMessages(1, 1), { wrapper: withSocket(fake.socket) })
    await waitFor(() => expect(result.current.status).toBe('ready'))
    let ok = true
    await act(async () => { ok = await result.current.send('안녕') })
    expect(ok).toBe(false)
    expect(result.current.error).toBe(CONNECTION_ERROR)
  })

  it('이 방의 NOT_A_MEMBER 오류 프레임은 notMember, 다른 오류는 서버 문구, 다른 방 오류는 무시', async () => {
    const fake = fakeChatSocket()
    vi.mocked(chat.readMessages).mockResolvedValue(page([]))
    const { result } = renderHook(() => useRoomMessages(1, 1), { wrapper: withSocket(fake.socket) })
    await waitFor(() => expect(result.current.status).toBe('ready'))

    act(() => fake.push({ type: 'error', roomId: 9, code: 'NOT_A_MEMBER', message: '멤버 아님' }))
    expect(result.current.status).toBe('ready')
    act(() => fake.push({ type: 'error', roomId: null, code: 'INVALID_REQUEST', message: '요청 값이 올바르지 않습니다.' }))
    expect(result.current.error).toBe('요청 값이 올바르지 않습니다.')
    act(() => fake.push({ type: 'error', roomId: 1, code: 'NOT_A_MEMBER', message: '이 채팅방의 멤버가 아닙니다.' }))
    expect(result.current.status).toBe('notMember')
  })
```
  (import에 `CONNECTION_ERROR`를 `../api/client`에서 추가)

  `ChatRoomPage.test.tsx`의 `describe('ChatRoomPage (websocket)')`에 추가:
```tsx
  it('보내기는 소켓으로 보내고 입력창을 비우며, 거절 프레임이 오면 입장 버튼으로 바뀐다', async () => {
    const fake = fakeChatSocket()
    vi.mocked(chat.readMessages).mockResolvedValue(page([]))
    render(<ChatSocketContext.Provider value={fake.socket}>
      <ChatRoomPage userId={1} roomId={1} title="잡담" onBack={vi.fn()} />
    </ChatSocketContext.Provider>)
    await userEvent.type(await screen.findByLabelText('메시지'), '안녕')
    await userEvent.click(screen.getByRole('button', { name: '보내기' }))
    expect(fake.sent).toEqual([{ roomId: 1, content: '안녕' }])
    expect(screen.getByLabelText('메시지')).toHaveValue('')

    act(() => fake.push({ type: 'error', roomId: 1, code: 'NOT_A_MEMBER', message: '이 채팅방의 멤버가 아닙니다.' }))
    expect(await screen.findByRole('button', { name: '입장' })).toBeInTheDocument()
  })
```

- [ ] **Step 2: 실패 확인** — `npx vitest run src/messages/useRoomMessages.test.ts src/pages/ChatRoomPage.test.tsx` → 새 테스트 FAIL(REST `sendMessage`로 보냄)

- [ ] **Step 3: 구현** — `useRoomMessages.ts`
  - import 줄을 `import { ApiError, CONNECTION_ERROR, errorMessage } from '../api/client'`로 바꾼다.
  - 작업 6의 구독 effect를 바꾼다:
```ts
  useEffect(() => {
    if (transport !== 'websocket' || socket === null || status !== 'ready') return
    // 계획 7 세부 9: 다른 방 메시지는 무시한다(방 목록 자동 갱신 없음, ADR-084). 내 메시지도 push로 받는다.
    // 구독 전에 온 push는 받지 않는다(F49, 장애 선행)
    return socket.subscribe((frame) => {
      if (frame.type === 'message') {
        if (frame.message.roomId === roomId) setMessages((current) => mergeMessages(current, [frame.message]))
        return
      }
      if (frame.roomId !== null && frame.roomId !== roomId) return
      // ADR-084: 멤버 여부는 서버의 인가 결과로 판단한다 (REST 403과 같은 처리)
      if (frame.code === 'NOT_A_MEMBER') {
        setStatus('notMember')
        return
      }
      setError(frame.message)
    })
  }, [transport, socket, status, roomId])
```
  - `send`의 맨 앞에 추가:
```ts
    if (transport === 'websocket') {
      // 계획 7 세부 3: 응답 짝 맞춤이 없어 성공은 "보냈다"까지만 안다. 결과는 message push나 error 프레임으로 온다.
      // 전송 중 비활성화·낙관적 표시는 하지 않는다(ADR-034, F33)
      if (socket?.send(roomId, content)) {
        setError(null)
        return true
      }
      setError(CONNECTION_ERROR)
      return false
    }
```

- [ ] **Step 4: 통과 확인** — `npx vitest run && npx tsc -b && npm run lint` → PASS

- [ ] **Step 5: 결과 보고 후 멈춤**

---

### 작업 13: 전달 시간 측정(AOP)과 WS 접속 로그

> 웨이브 4 · 선행: 3, 4

**Files:**
- Modify: `backend/build.gradle.kts`, `src/main/resources/application.yml`, `application-bench.yml`
- Create: `common/metrics/{DeliveryStage,DeliveryTimingAspect}.java`, `message/api/ws/WsAccessLogAspect.java`
- Modify (애너테이션만): `message/api/MessageController.java`, `message/application/MessageService.java`, `message/application/MessageFanout.java`, `message/api/ws/WsFrameSender.java`, `message/api/ws/ChatFrameHandler.java`
- Create (test): `common/metrics/DeliveryTimingAspectTest.java`, `common/metrics/DeliveryMetricsTest.java`, `message/api/ws/WsAccessLogAspectTest.java`, `message/api/ws/WsAccessLogTest.java`
- Modify (test): `observe/BenchProfileTest.java`

**Interfaces:**
- Consumes: `DeliveryOrigin`, `MessageSentEvent`(작업 3), `FrameOutcome`, `ChatFrameHandler.handle`(작업 4), `ChatWebSocketHandler.afterConnectionEstablished/afterConnectionClosed`(작업 1)
- Produces:
```java
@Target(METHOD) @Retention(RUNTIME)
public @interface DeliveryStage {
    String value();                      // receive | save | fanout | push
    String transport() default "";       // 인자에 DeliveryOrigin·MessageSentEvent가 없는 진입점만 지정
    boolean total() default false;       // 끝날 때 chat.delivery.total도 기록
}
// 지표: chat.delivery.stage{stage,transport}, chat.delivery.total{transport} (히스토그램)
// Prometheus: chat_delivery_stage_seconds_*, chat_delivery_total_seconds_*
// 로그: WS_ACCESS (event=connect|frame|close, sessionId, frameType, roomId, result, durationMs, closeCode / MDC requestId, userId)
```

| 붙이는 곳 | 애너테이션 |
|---|---|
| `MessageController.send` | `@DeliveryStage(value = "receive", transport = "rest")` |
| `ChatFrameHandler.handle` | `@DeliveryStage(value = "receive", transport = "ws")` |
| `MessageService.send(long, long, String, DeliveryOrigin)` | `@DeliveryStage("save")` |
| `MessageFanout.on` | `@DeliveryStage(value = "fanout", total = true)` |
| `WsFrameSender.send` | `@DeliveryStage("push")` |

- [ ] **Step 1: 의존성** — `build.gradle.kts`에 추가:
```kotlin
	implementation("org.springframework.boot:spring-boot-starter-aspectj")
```
  `./gradlew dependencies --configuration runtimeClasspath | grep -i aspect`로 해석되는지 확인한다. 이 이름이 없으면(Spring Boot 4.1.1의 스타터 목록에서) 실제 이름을 찾아 쓰고 보고한다. `aspectjweaver`는 이미 JPA 경로로 들어와 있으므로, 스타터를 넣기 전과 후에 `@Aspect` 자동 프록시가 이미 켜져 있었는지도 함께 보고한다(예상: 이미 켜져 있음, 측정 전).

- [ ] **Step 2: 실패하는 테스트**

`backend/src/test/java/jissuo/chat/common/metrics/DeliveryTimingAspectTest.java`
```java
package jissuo.chat.common.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import jissuo.chat.message.domain.DeliveryOrigin;
import jissuo.chat.message.domain.Message;
import jissuo.chat.message.domain.MessageContent;
import jissuo.chat.message.domain.MessageSentEvent;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

class DeliveryTimingAspectTest {

    final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    static class Stages {
        @DeliveryStage(value = "receive", transport = "rest")
        public void receive() {
        }

        @DeliveryStage("save")
        public void save(long userId, DeliveryOrigin origin) {
        }

        @DeliveryStage(value = "fanout", total = true)
        public void fanout(MessageSentEvent event) {
        }

        @DeliveryStage("push")
        public void fail(DeliveryOrigin origin) {
            throw new IllegalStateException("push 실패");
        }
    }

    Stages proxy() {
        AspectJProxyFactory factory = new AspectJProxyFactory(new Stages());
        factory.setProxyTargetClass(true);
        factory.addAspect(new DeliveryTimingAspect(meters));
        return factory.getProxy();
    }

    @Test
    void 단계_시간을_애너테이션의_통로나_인자의_통로로_기록한다() {
        Stages stages = proxy();

        stages.receive();
        stages.save(7, DeliveryOrigin.start("ws"));

        assertThat(meters.get("chat.delivery.stage").tags("stage", "receive", "transport", "rest").timer().count())
                .isEqualTo(1);
        assertThat(meters.get("chat.delivery.stage").tags("stage", "save", "transport", "ws").timer().count())
                .isEqualTo(1);
        assertThat(meters.find("chat.delivery.total").timer()).isNull();
    }

    @Test
    void fanout이_끝나면_진입_시각부터의_전체_시간을_기록한다() {
        long started = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(50);
        MessageSentEvent event = new MessageSentEvent(
                new Message(1, 1, 1, new MessageContent("a"), Instant.now()), started, "rest");

        proxy().fanout(event);

        assertThat(meters.get("chat.delivery.stage").tags("stage", "fanout", "transport", "rest").timer().count())
                .isEqualTo(1);
        assertThat(meters.get("chat.delivery.total").tags("transport", "rest").timer().totalTime(TimeUnit.MILLISECONDS))
                .isGreaterThanOrEqualTo(50);
    }

    @Test
    void 예외가_나도_시간을_기록하고_예외는_그대로_올린다() {
        Stages stages = proxy();

        assertThatThrownBy(() -> stages.fail(DeliveryOrigin.start("rest"))).hasMessage("push 실패");

        assertThat(meters.get("chat.delivery.stage").tags("stage", "push", "transport", "rest").timer().count())
                .isEqualTo(1);
    }
}
```

`backend/src/test/java/jissuo/chat/message/api/ws/WsAccessLogAspectTest.java`
```java
package jissuo.chat.message.api.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import jissuo.chat.auth.AuthUser;
import jissuo.chat.auth.QueryUserIdHandshakeInterceptor;
import jissuo.chat.common.ErrorCode;
import jissuo.chat.common.RequestLogContextFilter;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

class WsAccessLogAspectTest {

    final WsAccessLogAspect aspect = new WsAccessLogAspect();
    final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    final WebSocketSession session = mock(WebSocketSession.class);

    @BeforeEach
    void setUp() {
        appender.start();
        logger().addAppender(appender);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(QueryUserIdHandshakeInterceptor.ATTRIBUTE, new AuthUser(7));
        when(session.getAttributes()).thenReturn(attributes);
        when(session.getId()).thenReturn("s-1");
    }

    @AfterEach
    void tearDown() {
        logger().detachAppender(appender);
        MDC.clear();
    }

    @Test
    void 프레임마다_새_요청_ID를_MDC에_넣고_끝나면_지우며_결과를_한_줄로_남긴다() throws Throwable {
        AtomicReference<String> seen = new AtomicReference<>();
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenAnswer(invocation -> {
            seen.set(MDC.get(RequestLogContextFilter.REQUEST_ID));
            assertThat(MDC.get(RequestLogContextFilter.USER_ID)).isEqualTo("7");
            return FrameOutcome.rejected("send", 3L, ErrorCode.NOT_A_MEMBER);
        });

        aspect.frame(pjp, session);
        aspect.frame(pjp, session);

        assertThat(appender.list).hasSize(2);
        ILoggingEvent first = appender.list.get(0);
        assertThat(value(first, "event")).isEqualTo("frame");
        assertThat(value(first, "sessionId")).isEqualTo("s-1");
        assertThat(value(first, "frameType")).isEqualTo("send");
        assertThat(value(first, "roomId")).isEqualTo("3");
        assertThat(value(first, "result")).isEqualTo("NOT_A_MEMBER");
        assertThat(value(first, "durationMs")).isNotNull();
        assertThat(first.getMDCPropertyMap()).containsEntry("userId", "7").containsKey("requestId");
        assertThat(first.getMDCPropertyMap().get("requestId"))
                .isNotEqualTo(appender.list.get(1).getMDCPropertyMap().get("requestId"));
        assertThat(seen.get()).isNotNull();
        assertThat(MDC.get(RequestLogContextFilter.REQUEST_ID)).isNull();
        assertThat(MDC.get(RequestLogContextFilter.USER_ID)).isNull();
    }

    @Test
    void 종료는_종료_코드를_남긴다() throws Throwable {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);

        aspect.close(pjp, session, CloseStatus.GOING_AWAY);

        assertThat(value(appender.list.get(0), "event")).isEqualTo("close");
        assertThat(value(appender.list.get(0), "closeCode")).isEqualTo("1001");
        assertThat(value(appender.list.get(0), "result")).isEqualTo("ok");
    }

    private static String value(ILoggingEvent event, String key) {
        return event.getKeyValuePairs().stream().filter(pair -> pair.key.equals(key))
                .map(pair -> String.valueOf(pair.value)).findFirst().orElse(null);
    }

    private static Logger logger() {
        return (Logger) LoggerFactory.getLogger("WS_ACCESS");
    }
}
```

`backend/src/test/java/jissuo/chat/common/metrics/DeliveryMetricsTest.java` (통합)
```java
package jissuo.chat.common.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import jissuo.chat.support.ChatHttp;
import jissuo.chat.support.MySqlContainerSupport;
import jissuo.chat.support.WsTestClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMetrics
@ActiveProfiles("mysql")
class DeliveryMetricsTest {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired MeterRegistry meters;

    @Test
    void REST와_WS_전송_뒤_단계별_전체_시간이_통로별로_보인다() throws Exception {
        ChatHttp http = new ChatHttp(port, json);
        long sender = addUser();
        long room = http.createRoom(sender, "지표");
        try (WsTestClient tab = WsTestClient.connect(port, sender)) {
            // 서버 등록 전에 보내면 push가 없어 push 단계 지표가 생기지 않는다
            await().atMost(Duration.ofSeconds(5)).until(() -> meters.get("chat.ws.sessions").gauge().value() >= 1);
            http.send(sender, room, "rest");
            tab.next();
            tab.send("{\"type\":\"send\",\"roomId\":" + room + ",\"content\":\"ws\"}");
            tab.next();
        }

        String body = scrape();
        for (String transport : new String[] {"rest", "ws"}) {
            for (String stage : new String[] {"receive", "save", "fanout", "push"}) {
                assertThat(hasLine(body, "chat_delivery_stage_seconds_count", "stage=\"" + stage + "\"",
                        "transport=\"" + transport + "\"")).as(stage + "/" + transport).isTrue();
            }
            assertThat(hasLine(body, "chat_delivery_total_seconds_count", "transport=\"" + transport + "\""))
                    .as("total/" + transport).isTrue();
        }
        // 계획 7 세부 7A: p50/p95/p99를 Prometheus에서 계산할 수 있게 버킷을 낸다
        assertThat(body).contains("chat_delivery_total_seconds_bucket");
        assertThat(hasLine(body, "chat_ws_frames_total", "type=\"send\"")).isTrue();
    }

    private static boolean hasLine(String body, String name, String... labels) {
        return body.lines().filter(line -> line.startsWith(name + "{"))
                .anyMatch(line -> java.util.Arrays.stream(labels).allMatch(line::contains));
    }

    private String scrape() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus")).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private long addUser() {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO users (nickname, created_at) VALUES ('지표', :at)")
                .param("at", LocalDateTime.now(ZoneOffset.UTC))
                .update(keyHolder, "id");
        return keyHolder.getKey().longValue();
    }
}
```
  - Prometheus 이름이 예상(`chat_delivery_total_seconds_*`)과 다르게 나오면(예: `_total` 접미사 처리) 실제 출력 일부를 보고하고 멈춘다. 지표 이름을 바꿀지는 사용자와 정한다.

`backend/src/test/java/jissuo/chat/message/api/ws/WsAccessLogTest.java` (통합)
```java
package jissuo.chat.message.api.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.List;
import jissuo.chat.support.MySqlContainerSupport;
import jissuo.chat.support.WsTestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
class WsAccessLogTest {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }

    @LocalServerPort int port;

    final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void setUp() {
        appender.start();
        logger().addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger().detachAppender(appender);
    }

    @Test
    void 접속_프레임_종료를_한_줄씩_남기고_프레임마다_요청_ID가_다르다() throws Exception {
        try (WsTestClient tab = WsTestClient.connect(port, 7)) {
            tab.send("{");
            tab.next();
            tab.send("{\"type\":\"send\",\"roomId\":999999,\"content\":\"없는 방\"}");
            tab.next();
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> events().size() >= 4);

        List<ILoggingEvent> events = events();
        assertThat(events).extracting(e -> value(e, "event")).containsExactly("connect", "frame", "frame", "close");
        assertThat(value(events.get(1), "frameType")).isEqualTo("invalid");
        assertThat(value(events.get(1), "result")).isEqualTo("INVALID_REQUEST");
        assertThat(value(events.get(2), "frameType")).isEqualTo("send");
        assertThat(value(events.get(2), "result")).isEqualTo("NOT_A_MEMBER");
        assertThat(value(events.get(3), "closeCode")).isEqualTo("1000");
        assertThat(events).allSatisfy(e -> assertThat(e.getMDCPropertyMap()).containsEntry("userId", "7"));
        assertThat(events.stream().map(e -> e.getMDCPropertyMap().get("requestId")).distinct()).hasSize(4);
    }

    private List<ILoggingEvent> events() {
        synchronized (appender) {
            return List.copyOf(appender.list);
        }
    }

    private static String value(ILoggingEvent event, String key) {
        return event.getKeyValuePairs().stream().filter(pair -> pair.key.equals(key))
                .map(pair -> String.valueOf(pair.value)).findFirst().orElse(null);
    }

    private static Logger logger() {
        return (Logger) LoggerFactory.getLogger("WS_ACCESS");
    }
}
```

`BenchProfileTest`의 첫 테스트에 한 줄 추가:
```java
        assertThat(LocalProfileTest.level("WS_ACCESS")).isEqualTo(Level.OFF);
```

- [ ] **Step 3: 실패 확인** — `./gradlew test --tests 'jissuo.chat.common.metrics.*' --tests 'jissuo.chat.message.api.ws.WsAccess*' --tests 'jissuo.chat.observe.BenchProfileTest'` → 컴파일 실패(`DeliveryStage` 없음)

- [ ] **Step 4: 구현**

`common/metrics/DeliveryStage.java`
```java
package jissuo.chat.common.metrics;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 전달 단계 표시 (계획 7 세부 7A). domain은 Spring을 모르므로 domain 클래스에는 붙이지 않는다.
 * 프록시가 가로채야 하므로 다른 빈에서 부르는 public 메서드에만 붙인다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DeliveryStage {

    /** receive | save | fanout | push */
    String value();

    /** 인자에 DeliveryOrigin·MessageSentEvent가 없는 진입점에서만 지정한다 */
    String transport() default "";

    /** 끝날 때 진입 시각부터의 전체 시간(chat.delivery.total)도 기록한다 */
    boolean total() default false;
}
```

`common/metrics/DeliveryTimingAspect.java`
```java
package jissuo.chat.common.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import jissuo.chat.message.domain.DeliveryOrigin;
import jissuo.chat.message.domain.MessageSentEvent;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 계획 7 결정 D4·세부 7A: 동기 전달의 단계별 시간을 재서 이후 비동기 전달과 같은 지표로 비교한다.
 * 트랜잭션 프록시보다 바깥에서 재므로 save에는 커밋과 (동기라서) fanout이 들어간다. 단계는 겹친다:
 * receive ⊃ save ⊃ fanout ⊃ push. WS 접속 로그(WsAccessLogAspect)보다는 안쪽이다.
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class DeliveryTimingAspect {

    private final MeterRegistry meters;

    public DeliveryTimingAspect(MeterRegistry meters) {
        this.meters = meters;
    }

    @Around(value = "@annotation(stage)", argNames = "pjp,stage")
    public Object time(ProceedingJoinPoint pjp, DeliveryStage stage) throws Throwable {
        DeliveryOrigin origin = originOf(pjp.getArgs());
        String transport = !stage.transport().isEmpty() ? stage.transport()
                : origin != null ? origin.transport() : "unknown";
        long started = System.nanoTime();
        try {
            return pjp.proceed();
        } finally {
            long ended = System.nanoTime();
            Timer.builder("chat.delivery.stage").tag("stage", stage.value()).tag("transport", transport)
                    .register(meters).record(ended - started, TimeUnit.NANOSECONDS);
            // 메서드가 언제 반환하든 "진입 → 마지막 push 완료"라서 비동기로 바꿔도 같은 뜻이다
            if (stage.total() && origin != null) {
                Timer.builder("chat.delivery.total").tag("transport", transport)
                        .register(meters).record(ended - origin.startedNanos(), TimeUnit.NANOSECONDS);
            }
        }
    }

    private static DeliveryOrigin originOf(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof DeliveryOrigin origin) {
                return origin;
            }
            if (arg instanceof MessageSentEvent event) {
                return new DeliveryOrigin(event.transport(), event.startedNanos());
            }
        }
        return null;
    }
}
```

`message/api/ws/WsAccessLogAspect.java`
```java
package jissuo.chat.message.api.ws;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import jissuo.chat.auth.AuthUser;
import jissuo.chat.common.RequestLogContextFilter;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * 계획 7 결정 D5·세부 7B: WebSocket 접속·프레임·종료는 HTTP 필터를 거치지 않으므로 ACCESS 대신 이 로그를 남긴다.
 * 이벤트마다 서버가 새 요청 ID를 만든다(RequestLogContextFilter와 같은 규칙). userId·requestId는 MDC로만 넣는다
 * (ECS는 MDC와 key-value에 같은 필드가 있으면 직렬화를 거부한다).
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WsAccessLogAspect {

    private static final Logger WS_ACCESS = LoggerFactory.getLogger("WS_ACCESS");

    @Around("execution(* jissuo.chat.message.api.ws.ChatWebSocketHandler.afterConnectionEstablished(..)) && args(session)")
    public Object connect(ProceedingJoinPoint pjp, WebSocketSession session) throws Throwable {
        return logged(pjp, session, "connect", null);
    }

    @Around("execution(* jissuo.chat.message.api.ws.ChatWebSocketHandler.afterConnectionClosed(..)) && args(session, status)")
    public Object close(ProceedingJoinPoint pjp, WebSocketSession session, CloseStatus status) throws Throwable {
        return logged(pjp, session, "close", status);
    }

    @Around("execution(* jissuo.chat.message.api.ws.ChatFrameHandler.handle(..)) && args(session, ..)")
    public Object frame(ProceedingJoinPoint pjp, WebSocketSession session) throws Throwable {
        return logged(pjp, session, "frame", null);
    }

    private Object logged(ProceedingJoinPoint pjp, WebSocketSession session, String event, CloseStatus status)
            throws Throwable {
        MDC.put(RequestLogContextFilter.REQUEST_ID, UUID.randomUUID().toString());
        AuthUser user = ChatWebSocketHandler.userOf(session);
        if (user != null) {
            MDC.put(RequestLogContextFilter.USER_ID, String.valueOf(user.id()));
        }
        long started = System.nanoTime();
        Object result = null;
        String outcome = "exception";
        try {
            result = pjp.proceed();
            outcome = result instanceof FrameOutcome frame ? frame.result() : "ok";
            return result;
        } finally {
            LoggingEventBuilder log = WS_ACCESS.atInfo().setMessage("ws_access")
                    .addKeyValue("event", event)
                    .addKeyValue("sessionId", session.getId())
                    .addKeyValue("result", outcome)
                    .addKeyValue("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            if (result instanceof FrameOutcome frame) {
                log = log.addKeyValue("frameType", frame.type()).addKeyValue("roomId", frame.roomId());
            }
            if (status != null) {
                log = log.addKeyValue("closeCode", status.getCode());
            }
            log.log();
            MDC.remove(RequestLogContextFilter.REQUEST_ID);
            MDC.remove(RequestLogContextFilter.USER_ID);
        }
    }
}
```
  - 단위 테스트의 `pjp.proceed()` 기본 반환값(`null`)이면 `result="ok"`가 된다(종료 테스트가 이것을 쓴다).

애너테이션을 위 표대로 붙인다(import `jissuo.chat.common.metrics.DeliveryStage`). `MessageService`에서는 4인자 `send`에만 붙인다(3인자는 같은 객체 안 호출이라 붙여도 가로채지 못한다).

`application.yml` — `management.metrics.distribution.percentiles-histogram`과 `logging.level`을 바꾼다:
```yaml
    distribution:
      percentiles-histogram:
        http.server.requests: true
        # 계획 7 세부 7A: 단계별·전체 전달 시간의 p50/p95/p99를 Prometheus에서 계산한다
        chat.delivery: true
logging:
  level:
    ACCESS: INFO
    WS_ACCESS: INFO
```
`application-bench.yml`의 `logging.level`에 추가:
```yaml
    WS_ACCESS: "OFF"
```

- [ ] **Step 5: 통과 확인** — Step 3과 같은 명령 → PASS. 이어서 `./gradlew test` 전체 → PASS(ArchUnit 포함. `common`이 `message.domain`을 아는 것은 현재 규칙에 걸리지 않는다).

- [ ] **Step 6: 가설 메모** — aspect 오버헤드(F48)는 이 작업에서 측정하지 않는다. 작업 8에서 failure-lab에 가설로 적는다.

- [ ] **Step 7: 결과 보고 후 멈춤**

---

### 작업 8: E2E, 브라우저 확인, 기록

> 웨이브 5 · 선행: 1~7, 13

**Files:**
- Modify: `frontend/e2e/chat.spec.ts`
- Modify: `docs/adr/2026-10-08.md`(실행한 날짜의 파일), `docs/journal/2026-10-08.md`(실행한 날짜), `docs/failure-lab.md`, `docs/design/architecture.md`(인증·관측·같은 주소 절), `docs/README.md`(현재 위치), `CLAUDE.md`(현재 위치, 명령어의 `?transport=polling` 한 줄), 이 계획서의 진행 상태·체크박스
- 코드 주석의 `계획 7 세부 #n`, `계획 7 결정 Dn`을 ADR 번호로 바꾼다(`grep -rn '계획 7' backend/src frontend/src`)

- [ ] **Step 1: E2E 갱신** — `frontend/e2e/chat.spec.ts`
  - 상수와 도우미를 바꾼다:
```ts
// 기본 폴링 주기 2초(ADR-083)보다 넉넉하게 기다린다
const POLL_TIMEOUT = 10_000
// WebSocket push는 폴링 주기와 무관하다. 폴링 기본 주기(2초)보다 짧게 잡지 않는 이유는 CI·첫 컴파일 지연이다
const PUSH_TIMEOUT = 5_000

type Watch = { polls: string[]; frames: string[] }

// 계획 7: 폴링 요청이 생기지 않는지와 받은 WS 프레임을 함께 본다. Vite HMR 소켓은 제외한다
function watch(page: Page): Watch {
  const result: Watch = { polls: [], frames: [] }
  page.on('request', (request) => {
    if (/\/api\/rooms\/\d+\/messages\?after=/.test(request.url())) result.polls.push(request.url())
  })
  page.on('websocket', (ws) => {
    if (!ws.url().includes('/ws?userId=')) return
    ws.on('framereceived', (frame) => result.frames.push(String(frame.payload)))
  })
  return result
}

async function newUser(browser: Browser, nickname: string, base = '/'): Promise<{ page: Page; seen: Watch }> {
  const page = await (await browser.newContext()).newPage()
  const seen = watch(page)
  await page.goto(base)
  await page.getByLabel('닉네임').fill(nickname)
  await page.getByRole('button', { name: '새 사용자로 시작' }).click()
  await expect(page.getByRole('button', { name: '방 만들기' })).toBeVisible()
  return { page, seen }
}

async function enter(page: Page, roomHash: string, base = '/') {
  await page.goto(`${base}${roomHash}`)
  await page.getByRole('button', { name: '입장' }).click()
  await expect(page.getByLabel('메시지')).toBeVisible()
}
```
  - 기존 테스트에서 `const a = await newUser(...)`를 `const { page: a } = await newUser(...)`로 바꾼다(b도 같다). 첫 테스트는 아래로 바꾼다:
```ts
test('두 사용자가 WebSocket으로 대화하고 폴링 요청은 없으며, 다시 입장하면 이전 메시지가 보이지 않는다 (R4)', async ({ browser }) => {
  const suffix = Date.now().toString(36)
  const { page: a } = await newUser(browser, `a-${suffix}`)
  const { page: b, seen } = await newUser(browser, `b-${suffix}`)

  await a.getByLabel('방 이름').fill(`e2e-${suffix}`)
  await a.getByRole('button', { name: '방 만들기' }).click()
  await expect(a).toHaveURL(/#\/rooms\/\d+$/)
  const roomHash = new URL(a.url()).hash

  await enter(b, roomHash)
  await send(a, '안녕 B')
  await expect(b.getByRole('list', { name: '대화' })).toContainText('안녕 B', { timeout: PUSH_TIMEOUT })
  expect(seen.polls).toEqual([])
  expect(seen.frames.some((frame) => frame.includes('"type":"message"') && frame.includes('안녕 B'))).toBe(true)

  await b.getByRole('button', { name: '나가기' }).click()
  await expect(b).toHaveURL(/#\/rooms$/)
  await send(a, '나간 뒤 메시지')

  await enter(b, roomHash)
  await send(b, '다시 왔어')
  await expect(a.getByRole('list', { name: '대화' })).toContainText('다시 왔어', { timeout: PUSH_TIMEOUT })
  await expect(b.getByRole('list', { name: '대화' })).not.toContainText('안녕 B')
  await expect(b.getByRole('list', { name: '대화' })).not.toContainText('나간 뒤 메시지')
})

test('?transport=polling이면 기존 폴링으로 대화한다 (Step 6 비교용 회귀)', async ({ browser }) => {
  const base = '/?transport=polling'
  const suffix = Date.now().toString(36)
  const { page: a } = await newUser(browser, `pa-${suffix}`, base)
  const { page: b, seen } = await newUser(browser, `pb-${suffix}`, base)

  await a.getByLabel('방 이름').fill(`poll-${suffix}`)
  await a.getByRole('button', { name: '방 만들기' }).click()
  await expect(a).toHaveURL(/#\/rooms\/\d+$/)
  await enter(b, new URL(a.url()).hash, base)
  await send(a, '폴링으로')

  await expect(b.getByRole('list', { name: '대화' })).toContainText('폴링으로', { timeout: POLL_TIMEOUT })
  expect(seen.polls.length).toBeGreaterThan(0)
  expect(seen.frames).toEqual([])
  await b.getByText('폴링 상태').click()
  await expect(b.locator('.panel')).toContainText('after 커서')
})
```
  - 마지막 테스트(스크롤)의 `{ timeout: POLL_TIMEOUT }`은 `PUSH_TIMEOUT`으로, 패널 확인 세 줄을 아래로 바꾼다:
```ts
  await a.getByText('연결 상태').click()
  await expect(a.locator('.panel')).toBeVisible()
  await expect(a.locator('.panel')).toContainText('연결됨')
  await expect(a.locator('.panel')).toContainText('받은 프레임')
```
  - 실행: DB를 띄운 뒤(`docker compose -f infra/compose.db.yml up -d --wait`) `cd frontend && npm run e2e`. 실패하면 원인(특히 Vite proxy 뒤 Origin 검사 403 여부)을 보고하고 멈춘다. 테스트 기대를 임의로 느슨하게 하지 않는다.

- [ ] **Step 2: 브라우저 확인** — 백엔드 `./gradlew bootRun --args='--spring.profiles.active=local,mysql'`, 프론트 `npm run dev`. 결과는 일지에 "측정"으로 적는다.
  1. 두 탭(두 사용자)에서 한쪽이 보내면 다른 쪽에 바로 보인다. "연결 상태" 패널의 받은 프레임 수가 늘고, Network의 `messages?after=` 요청은 늘지 않는다.
  2. 개발자 도구 Network → WS → `/ws?userId=` 연결의 Messages에서 `send`·`message` 프레임 JSON을 본다. 비멤버 방에서 보내기(다른 탭에서 나간 뒤)로 `error` 프레임을 본다.
  3. 백엔드를 재시작한다. 패널이 "끊김"(종료 코드 1006 또는 1001) → 약 1초 뒤 "연결 중"·"연결됨", 재연결 횟수 1. 재시작하는 동안 다른 사용자가 REST로 보낸 메시지(예: `curl`)는 새로고침 전까지 보이지 않는다(F6 예고, 측정).
  4. `?transport=polling`으로 열면 "폴링 상태" 패널과 기존 동작 그대로.
  5. `backend/logs/app.json`에서 `WS_ACCESS` 행(connect·frame·close, requestId, userId)과 핸드셰이크의 `ACCESS` 행(`/ws`, 101)을 찾는다. `/actuator/prometheus`에서 `chat_ws_sessions`, `chat_delivery_stage_seconds{stage,transport}`, `chat_delivery_total_seconds{transport}`를 본다.

- [ ] **Step 3: 장애 가설 기록** — `docs/failure-lab.md` 표와 본문에 F43~F49(위 "예상되는 문제" 표)를 가설로 추가한다. F50은 코드 리뷰 뒤 먼저 기록했으므로 재현 상태와 결정 항목을 유지한다. 작업 3 Step 6의 "기존 실험 조건 변화(전송마다 멤버 조회 1회 추가)"를 기록한다.

- [ ] **Step 4: ADR 기록** — `docs/adr/<실행한 날짜>.md`에 "계획 7: WebSocket 서버 1대" 절을 추가하고 다음 빈 번호(ADR-129 예상)부터 기록한다(결정 / 이유 / 포기한 것). 순서: 사용자 결정 D1~D6(**특히 D5 AOP 로그 범위**: 접속·프레임·종료 로그와 전달 시간만 AOP로 하고 ACCESS·AUDIT·예외 로그는 그대로 둔 이유 세 가지 — 필터 밖 401·404 누락, 감사의 커밋 시점, 예외 중복 기록), 세부 #1~#12와 #7A·#7B(보완 내용 포함).

- [ ] **Step 5: 설계 문서** — `docs/design/architecture.md`
  - 인증 절: WebSocket 핸드셰이크의 쿼리 `userId` 추출(`QueryUserIdHandshakeInterceptor`), 실패 시 401과 감사, 프레임마다 다시 검사하지 않음.
  - 관측 절: `WS_ACCESS` 로그 필드, 이벤트마다 새 요청 ID, bench OFF. 지표 `chat.ws.sessions`, `chat.ws.frames{type}`, `chat.delivery.stage{stage,transport}`, `chat.delivery.total{transport}`와 단계가 겹친다는 점.
  - "같은 주소로 서비스하는 이유" 절의 "WebSocket 전달은 Step 2에서 `ws: true` 설정을 추가한다"를 실제 상태로 바꾸고 Step 2 Origin 관찰 결과를 적는다.
  - `docs/README.md`, `CLAUDE.md` 현재 위치를 "계획 7(WebSocket 1대) 기능 완료, 장애 재현(F3~F6) 진행 전"으로 바꾸고, `CLAUDE.md` 명령어 절의 프론트 항목에 "`?transport=polling`이면 기존 폴링" 한 줄을 추가한다. 일지에 작업 결과·브라우저 확인을 적는다.

- [ ] **Step 6: 최종 확인** — `cd backend && ./gradlew test`, `cd frontend && npx vitest run && npx tsc -b && npm run lint && npm run e2e`. 출력과 함께 보고하고 멈춘다.

---

> **작업 9~12 공통**: 실험은 `@Tag("experiment")`이고 `./gradlew experimentTest --tests 'jissuo.chat.experiment.ws.*'`로 실행한다. DB와 무관한 문제라 MySQL 한 곳에서만 한다. 결과는 `ExperimentResults`(`build/experiment-results/*.csv`)에 남기고, 웨이브가 끝나면 **측정값과 예상을 나눠** 보고한 뒤 멈춘다. 해결책은 재현 뒤 사용자와 정하며 이 계획서에는 넣지 않는다(작업 9의 `ConcurrentHashMap` 안내만 예외). 재현 결과는 `failure-lab.md`와 일지에, 결정은 ADR에 남긴다.

### 작업 9: F3 재현 — 세션 저장소 동시성

> 웨이브 6 · 선행: 8

**Files:**
- Create: `backend/src/test/java/jissuo/chat/experiment/ws/SessionRegistryExperiment.java`

**Interfaces:**
- Consumes: `WsSessionRegistry`(작업 1), `WsTestClient`, `Concurrently`, `ExperimentResults`

- [ ] **Step 1: 실험 작성**
```java
package jissuo.chat.experiment.ws;

import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import jissuo.chat.experiment.support.Concurrently;
import jissuo.chat.experiment.support.ExperimentResults;
import jissuo.chat.message.api.ws.WsSessionRegistry;
import jissuo.chat.support.MySqlContainerSupport;
import jissuo.chat.support.WsTestClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.WebSocketSession;

/** F3: 일반 HashMap·ArrayList 세션 저장소에서 동시 접속·종료·순회가 겹치면 예외·유실·남는 세션이 생기는지 잰다. */
@Tag("experiment")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
class SessionRegistryExperiment {

    static final String HEADER = "mode,threads,seconds,operations,errors,errorTypes,lostAfterAdd,leftover";

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }

    @LocalServerPort int port;
    @Autowired WsSessionRegistry registry;
    @Autowired MeterRegistry meters;

    @Test
    @Timeout(120)
    void 저장소를_직접_동시에_바꾸고_순회한다() throws Exception {
        // 실제 연결과 겹치지 않는 사용자 id. 스레드 0은 push처럼 순회만, 나머지는 자기 세션을 붙였다 뗀다
        long userId = Long.MAX_VALUE - 1;
        int threads = 8;
        var roles = new AtomicInteger();
        ThreadLocal<Integer> role = ThreadLocal.withInitial(roles::getAndIncrement);
        ThreadLocal<WebSocketSession> own = ThreadLocal.withInitial(() -> mock(WebSocketSession.class));
        var operations = new AtomicLong();
        var lost = new AtomicLong();

        List<Throwable> errors = Concurrently.run(threads, Duration.ofSeconds(10), () -> {
            if (role.get() == 0) {
                for (WebSocketSession session : registry.sessionsOf(userId)) {
                    session.getId();
                }
            } else {
                WebSocketSession session = own.get();
                registry.add(userId, session);
                if (!registry.sessionsOf(userId).contains(session)) {
                    lost.incrementAndGet();
                }
                registry.remove(userId, session);
            }
            operations.incrementAndGet();
        });

        ExperimentResults.record("ws-session-registry", HEADER, String.join(",", "direct",
                Integer.toString(threads), "10", Long.toString(operations.get()), Integer.toString(errors.size()),
                errorTypes(errors), Long.toString(lost.get()), Integer.toString(registry.sessionsOf(userId).size())));
    }

    @Test
    @Timeout(180)
    void 같은_사용자가_동시에_많이_접속했다_끊는다() throws Exception {
        long userId = 4242;   // ADR-005: 형식만 맞으면 연결된다
        int clients = 200;
        double before = gauge();
        List<WsTestClient> connected = new ArrayList<>();
        var errors = new ConcurrentLinkedQueue<Throwable>();
        try (var pool = Executors.newFixedThreadPool(32)) {
            List<Future<WsTestClient>> futures = new ArrayList<>();
            for (int i = 0; i < clients; i++) {
                futures.add(pool.submit(() -> WsTestClient.connect(port, userId)));
            }
            for (Future<WsTestClient> future : futures) {
                try {
                    connected.add(future.get());
                } catch (Exception e) {
                    errors.add(e);
                }
            }
        }
        Thread.sleep(2000);   // 서버 쪽 등록이 끝나기를 기다린다 (측정 조건, 고정)
        double afterConnect = gauge() - before;
        try (var pool = Executors.newFixedThreadPool(32)) {
            for (WsTestClient client : connected) {
                pool.submit(() -> {
                    client.close();
                    return null;
                });
            }
        }
        Thread.sleep(5000);
        ExperimentResults.record("ws-session-registry", HEADER, String.join(",", "connections",
                Integer.toString(clients), "-", Integer.toString(connected.size()), Integer.toString(errors.size()),
                errorTypes(List.copyOf(errors)), Double.toString(clients - afterConnect),
                Double.toString(gauge() - before)));
    }

    private double gauge() {
        return meters.get("chat.ws.sessions").gauge().value();
    }

    private static String errorTypes(List<Throwable> errors) {
        Map<String, Long> types = errors.stream().collect(
                Collectors.groupingBy(e -> e.getClass().getSimpleName(), TreeMap::new, Collectors.counting()));
        return types.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(";"));
    }
}
```
  - 두 번째 실험에서 `lostAfterAdd` 칸은 "연결 뒤 게이지가 기대보다 적은 수", `leftover`는 "모두 닫은 뒤 남은 수"다. 서버 쪽 예외는 `app.json`의 `ERROR` 행 수로 따로 센다.

- [ ] **Step 2: 실행** — `./gradlew experimentTest --tests 'jissuo.chat.experiment.ws.SessionRegistryExperiment'`를 3번 반복하고 CSV를 모은다. 예상(측정 전): direct 모드에서 `ConcurrentModificationException`·`ArrayIndexOutOfBoundsException`·`NullPointerException` 일부와 `leftover > 0`, connections 모드는 재현 빈도가 낮을 수 있다.

- [ ] **Step 3: 멈추고 보고** — 측정값과 예상을 나눠 보고한다. 원인 분석은 사용자와 함께 한다.

- [ ] **Step 4: 보완안 안내 (승인 전 적용하지 않는다)** — 아래를 7단계 형식으로 다듬어 제안한다.
  1. 한 줄 요약: 세션 저장소를 `ConcurrentHashMap<Long, Set<WebSocketSession>>`(+ `ConcurrentHashMap.newKeySet()`)로 바꾸고 붙이기·떼기를 `compute`로 원자적으로 한다.
  2. 문제: 측정한 예외 수·남은 세션 수.
  3. 화면의 변화: 다른 탭이 메시지를 못 받거나(유실), 닫힌 탭이 게이지에 남는 일이 없어진다.
  4. 추천과 이유: 사용자당 탭 수가 작아 집합 순회 비용이 낮고, 읽기(push)가 잠금 없이 진행된다.
  5. 비용과 위험: 순회 중 추가·삭제가 보일 수도 안 보일 수도 있다(약한 일관성). 같은 세션 동시 쓰기(F43)는 해결하지 않는다.
  6. 기술 근거: `HashMap`·`ArrayList`는 동시 수정을 보장하지 않는다. `ConcurrentHashMap.compute`는 키 단위로 원자적이다.
  7. 결정할 사항: 적용 여부, 목록 대신 집합으로 바꿔도 되는지.
  - 적용안 코드(승인 뒤에만):
```java
    private final Map<Long, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    public void add(long userId, WebSocketSession session) {
        sessions.computeIfAbsent(userId, id -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void remove(long userId, WebSocketSession session) {
        // 비었는지 확인하고 지우는 사이에 다른 탭이 붙지 않도록 같은 키 안에서 한 번에 한다
        sessions.computeIfPresent(userId, (id, tabs) -> {
            tabs.remove(session);
            return tabs.isEmpty() ? null : tabs;
        });
    }

    public Collection<WebSocketSession> sessionsOf(long userId) {
        return sessions.getOrDefault(userId, Set.of());
    }

    public int count() {
        return sessions.values().stream().mapToInt(Set::size).sum();
    }
```
  (`sessionsOf`의 반환형이 `Collection`으로 바뀌므로 `WsMessagePusher`·실험 코드의 지역 변수 타입도 함께 바꾼다.) 적용 뒤 같은 실험을 3번 다시 돌려 전후를 비교하고 기록한다.

---

### 작업 10: F4 재현 — 느린 클라이언트

> 웨이브 7 · 선행: 9

**Files:**
- Create: `backend/src/test/java/jissuo/chat/experiment/ws/{SlowConsumerExperiment,StalledWsClient}.java`

**Interfaces:**
- Consumes: `ChatHttp`, `WsTestClient`, `ExperimentFixtures`, 지표 `chat.delivery.stage{stage=push}`·`chat.delivery.total{transport=rest}`(작업 13)
- Produces: `StalledWsClient(int port, long userId)` — 핸드셰이크만 하고 프레임을 읽지 않는 클라이언트(작업 11에서도 참고)

- [ ] **Step 1: 읽지 않는 클라이언트**
```java
package jissuo.chat.experiment.ws;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ThreadLocalRandom;

/** F4: 핸드셰이크만 하고 이후 프레임을 읽지 않는다. 수신 버퍼를 작게 잡아 서버 쓰기가 빨리 막히게 한다. */
final class StalledWsClient implements AutoCloseable {

    private final Socket socket = new Socket();

    StalledWsClient(int port, long userId) throws IOException {
        socket.setReceiveBufferSize(4096);
        socket.connect(new InetSocketAddress("localhost", port), 5000);
        byte[] nonce = new byte[16];
        ThreadLocalRandom.current().nextBytes(nonce);
        String request = "GET /ws?userId=" + userId + " HTTP/1.1\r\n"
                + "Host: localhost:" + port + "\r\n"
                + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + Base64.getEncoder().encodeToString(nonce) + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        // 101 응답 헤더까지만 읽고 그 뒤로는 읽지 않는다
        InputStream in = socket.getInputStream();
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("핸드셰이크 중 연결이 닫혔다: " + head);
            }
            head.append((char) b);
        }
        if (!head.toString().startsWith("HTTP/1.1 101")) {
            throw new IOException("핸드셰이크 실패: " + head);
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
```

- [ ] **Step 2: 실험 작성**
```java
package jissuo.chat.experiment.ws;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import jissuo.chat.experiment.support.ExperimentFixtures;
import jissuo.chat.experiment.support.ExperimentResults;
import jissuo.chat.support.ChatHttp;
import jissuo.chat.support.MySqlContainerSupport;
import jissuo.chat.support.WsTestClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** F4: 읽지 않는 수신자 한 명이 있을 때 보낸 사람의 응답과 다른 수신자의 도착이 함께 늦어지는지 잰다. */
@Tag("experiment")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
class SlowConsumerExperiment {

    static final String ROWS = "phase,index,status,restMs,fastArrivalMs";
    static final String SUMMARY = "phase,messages,restP50Ms,restMaxMs,fastMaxMs,pushMeanMs,pushMaxMs,totalMeanMs,totalMaxMs,non201";
    // 한 번에 많이 쌓이도록 메시지당 약 3KB(한글 1000자, 최대 길이)
    static final String CONTENT = "가".repeat(1000);

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired MeterRegistry meters;

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void 느린_수신자_유무로_전달_시간을_비교한다() throws Exception {
        var fixtures = new ExperimentFixtures(jdbc);
        ChatHttp http = new ChatHttp(port, json);
        long sender = fixtures.user("sender");
        long fast = fixtures.user("fast");
        long slow = fixtures.user("slow");
        long room = http.createRoom(sender, "slow-consumer");
        http.join(fast, room);
        http.join(slow, room);

        try (WsTestClient fastTab = WsTestClient.connect(port, fast)) {
            Thread.sleep(500);
            run("baseline", 50, http, sender, room, fastTab, false);
            try (StalledWsClient stalled = new StalledWsClient(port, slow)) {
                Thread.sleep(500);
                // 버퍼가 차서 응답이 2초를 넘으면 그 뒤 10건까지만 더 보내고 멈춘다
                run("stalled", 400, http, sender, room, fastTab, true);
            }
        }
    }

    private void run(String phase, int limit, ChatHttp http, long sender, long room, WsTestClient fastTab,
                     boolean stopAfterSlow) throws Exception {
        Snapshot push = Snapshot.of(timer("chat.delivery.stage", "stage", "push", "transport", "rest"));
        Snapshot total = Snapshot.of(timer("chat.delivery.total", "transport", "rest"));
        List<Long> rest = new ArrayList<>();
        long fastMax = 0;
        int non201 = 0;
        int afterSlow = -1;
        for (int i = 0; i < limit && afterSlow != 0; i++) {
            long started = System.nanoTime();
            HttpResponse<String> response = http.send(sender, room, CONTENT);
            long restMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            String arrived = fastTab.poll(java.time.Duration.ofSeconds(30));
            long fastMs = arrived == null ? -1 : TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            rest.add(restMs);
            fastMax = Math.max(fastMax, fastMs);
            if (response.statusCode() != 201) {
                non201++;
            }
            ExperimentResults.record("ws-slow-consumer-rows", ROWS, String.join(",", phase, Integer.toString(i),
                    Integer.toString(response.statusCode()), Long.toString(restMs), Long.toString(fastMs)));
            if (stopAfterSlow && afterSlow < 0 && restMs > 2000) {
                afterSlow = 10;
            } else if (afterSlow > 0) {
                afterSlow--;
            }
        }
        rest.sort(null);
        ExperimentResults.record("ws-slow-consumer", SUMMARY, String.join(",", phase, Integer.toString(rest.size()),
                Long.toString(rest.get(rest.size() / 2)), Long.toString(rest.getLast()), Long.toString(fastMax),
                push.meanSince(), push.max(), total.meanSince(), total.max(), Integer.toString(non201)));
    }

    private Timer timer(String name, String... tags) {
        return meters.timer(name, tags);
    }

    /** 단계 시간은 누적 타이머라서 구간 평균은 시작 시점과의 차이로 구한다. max는 Micrometer의 최근 구간 최댓값이다 */
    record Snapshot(Timer timer, long count, double totalMs) {
        static Snapshot of(Timer timer) {
            return new Snapshot(timer, timer.count(), timer.totalTime(TimeUnit.MILLISECONDS));
        }

        String meanSince() {
            long n = timer.count() - count;
            return n == 0 ? "-" : String.format("%.1f", (timer.totalTime(TimeUnit.MILLISECONDS) - totalMs) / n);
        }

        String max() {
            return String.format("%.1f", timer.max(TimeUnit.MILLISECONDS));
        }
    }
}
```
  - `meters.timer(name, tags)`는 작업 13에서 등록한 타이머를 같은 태그로 찾는다(없으면 새로 만들어 0으로 시작한다).

- [ ] **Step 3: 실행** — `./gradlew experimentTest --tests 'jissuo.chat.experiment.ws.SlowConsumerExperiment'`. 예상(측정 전): baseline은 REST p50이 수십 ms 이하. stalled는 몇십~몇백 건 뒤 서버 송신 버퍼가 차서 push가 막히고, 보낸 사람의 REST 응답과 빠른 수신자의 도착이 함께 늦어진다. Tomcat 전송 시간 초과(기본값 확인 필요)로 push 예외가 나면 Spring의 `afterCompletion`에서 기록되고 REST는 저장 성공 201일 것으로 예상한다(F44). 전송 실패가 난 세션 뒤의 수신자는 push를 못 받을 수 있다(F50).

- [ ] **Step 4: 멈추고 보고** — CSV 요약, 응답이 늦어지기 시작한 순번, HTTP 상태별 건수, `app.json`의 예외 종류, DB 저장 여부와 빠른 수신자의 누락 여부를 측정값으로 보고한다. 해결 후보(비동기 전달, 세션별 송신 큐·시간 제한 등)는 7단계로 제안만 한다. 비동기를 고르면 이 실험과 같은 지표(`chat.delivery.stage{push}`, `chat.delivery.total`, REST 응답 시간)로 동기와 비교하는 작업을 따로 계획한다.

---

### 작업 11: F5 재현 — half-open 좀비 연결

> 웨이브 8 · 선행: 10

**Files:**
- Create: `backend/src/test/java/jissuo/chat/experiment/ws/{HalfOpenExperiment,SilentDropProxy}.java`

- [ ] **Step 1: 조용히 끊는 TCP 프록시**
```java
package jissuo.chat.experiment.ws;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * F5: 연결을 서버로 그대로 잇다가 freeze() 뒤에는 양쪽 모두 아무것도 전달하지 않고 소켓도 닫지 않는다.
 * 케이블이 빠진 것처럼 서버는 FIN도 RST도 받지 못한다.
 */
final class SilentDropProxy implements AutoCloseable {

    private final ServerSocket server = new ServerSocket(0);
    private final int target;
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean frozen;

    SilentDropProxy(int target) throws IOException {
        this.target = target;
        pool.submit(this::accept);
    }

    int port() {
        return server.getLocalPort();
    }

    void freeze() {
        frozen = true;
    }

    private Void accept() {
        try {
            while (!server.isClosed()) {
                Socket client = server.accept();
                Socket upstream = new Socket("localhost", target);
                sockets.add(client);
                sockets.add(upstream);
                pool.submit(() -> pump(client, upstream));
                pool.submit(() -> pump(upstream, client));
            }
        } catch (IOException e) {
            // close()로 서버 소켓을 닫으면 여기서 끝난다
        }
        return null;
    }

    private Void pump(Socket from, Socket to) throws Exception {
        byte[] buffer = new byte[8192];
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            while (true) {
                while (frozen) {
                    // 읽지도 쓰지도 않고 소켓을 열어 둔다
                    Thread.sleep(50);
                }
                int n = in.read(buffer);
                if (n < 0) {
                    return null;
                }
                while (frozen) {
                    Thread.sleep(50);
                }
                out.write(buffer, 0, n);
                out.flush();
            }
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
        for (Socket socket : sockets) {
            socket.close();
        }
        pool.shutdownNow();
    }
}
```

- [ ] **Step 2: 실험 작성**
```java
package jissuo.chat.experiment.ws;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;
import jissuo.chat.experiment.support.ExperimentFixtures;
import jissuo.chat.experiment.support.ExperimentResults;
import jissuo.chat.support.ChatHttp;
import jissuo.chat.support.MySqlContainerSupport;
import jissuo.chat.support.WsTestClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** F5: ping/pong이 없으면 조용히 끊긴 연결을 서버가 알아채지 못해 세션이 남는지 본다. */
@Tag("experiment")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
class HalfOpenExperiment {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired MeterRegistry meters;

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void 조용히_끊긴_연결은_세션_게이지에서_줄지_않는다() throws Exception {
        var fixtures = new ExperimentFixtures(jdbc);
        ChatHttp http = new ChatHttp(port, json);
        long sender = fixtures.user("sender");
        long zombie = fixtures.user("zombie");
        long room = http.createRoom(sender, "half-open");
        http.join(zombie, room);
        double before = gauge();

        try (SilentDropProxy proxy = new SilentDropProxy(port)) {
            WsTestClient client = WsTestClient.connect(proxy.port(), zombie);
            Thread.sleep(500);
            proxy.freeze();
            // 클라이언트 쪽은 닫았지만 프록시가 서버로 전달하지 않는다
            client.close();
            long frozenAt = System.nanoTime();
            for (int seconds : new int[] {0, 10, 30, 60}) {
                long wait = TimeUnit.SECONDS.toMillis(seconds) - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - frozenAt);
                Thread.sleep(Math.max(0, wait));
                ExperimentResults.record("ws-half-open", "phase,elapsedSeconds,extraSessions",
                        "idle," + seconds + "," + (gauge() - before));
            }
            // 남은 세션으로 push하면 프록시 버퍼가 찰 때까지는 성공하고 그 뒤로는 막힌다(F4와 연결)
            for (int i = 0; i < 30; i++) {
                long started = System.nanoTime();
                HttpResponse<String> response = http.send(sender, room, "가".repeat(1000));
                ExperimentResults.record("ws-half-open-push", "index,status,restMs,extraSessions",
                        i + "," + response.statusCode() + ","
                                + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) + "," + (gauge() - before));
            }
        }
    }

    private double gauge() {
        return meters.get("chat.ws.sessions").gauge().value();
    }
}
```
  - 측정 시점은 freeze 뒤 0·10·30·60초다.

- [ ] **Step 3: 실행·보고** — `./gradlew experimentTest --tests 'jissuo.chat.experiment.ws.HalfOpenExperiment'`. 예상(측정 전): 60초 동안 `extraSessions`가 1로 남는다(Tomcat의 WebSocket 유휴 시간 제한 기본값이 없다고 알고 있음, 확인 필요). push는 처음 몇 건 성공하다가 버퍼가 차면 막힌다. 측정값과 예상을 나눠 보고하고 멈춘다. 해결 후보(서버 ping·유휴 시간 제한·클라이언트 heartbeat)는 7단계로 제안만 한다.

---

### 작업 12: F6 재현 — 재연결 시 메시지 유실

> 웨이브 9 · 선행: 11

**Files:**
- Create: `backend/src/test/java/jissuo/chat/experiment/ws/ReconnectLossExperiment.java`

- [ ] **Step 1: 실험 작성**
```java
package jissuo.chat.experiment.ws;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import jissuo.chat.experiment.support.ExperimentFixtures;
import jissuo.chat.experiment.support.ExperimentResults;
import jissuo.chat.support.ChatHttp;
import jissuo.chat.support.MySqlContainerSupport;
import jissuo.chat.support.WsTestClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** F6: 끊겨 있던 동안 보낸 메시지를 다시 연결한 뒤에도 받지 못하는지 본다 (따라잡기 없음, 계획 7 세부 8). */
@Tag("experiment")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mysql")
class ReconnectLossExperiment {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }

    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired MeterRegistry meters;

    @Test
    void 끊긴_동안_보낸_메시지는_다시_연결해도_push로_오지_않는다() throws Exception {
        var fixtures = new ExperimentFixtures(jdbc);
        ChatHttp http = new ChatHttp(port, json);
        long sender = fixtures.user("sender");
        long member = fixtures.user("member");
        long room = http.createRoom(sender, "reconnect");
        http.join(member, room);
        double before = gauge();

        WsTestClient first = WsTestClient.connect(port, member);
        Thread.sleep(500);
        first.close();
        Thread.sleep(500);
        long missed = idOf(http.send(sender, room, "끊긴 동안").body());
        WsTestClient second = WsTestClient.connect(port, member);
        Thread.sleep(500);
        long after = idOf(http.send(sender, room, "다시 연결한 뒤").body());

        List<Long> received = new ArrayList<>();
        for (String frame; (frame = second.poll(Duration.ofSeconds(3))) != null; ) {
            received.add(json.readTree(frame).at("/message/id").asLong());
        }
        second.close();
        JsonNode stored = json.readTree(http.get("/api/rooms/" + room + "/messages", member).body()).at("/data/messages");
        boolean missedStored = false;
        for (JsonNode message : stored) {
            missedStored |= message.get("id").asLong() == missed;
        }
        ExperimentResults.record("ws-reconnect-loss",
                "missedId,afterId,receivedIds,missedReceived,afterReceived,missedStored,extraSessions",
                String.join(",", Long.toString(missed), Long.toString(after), received.toString().replace(',', ' '),
                        Boolean.toString(received.contains(missed)), Boolean.toString(received.contains(after)),
                        Boolean.toString(missedStored), Double.toString(gauge() - before)));
    }

    private long idOf(String body) {
        return json.readTree(body).at("/data/id").asLong();
    }

    private double gauge() {
        return meters.get("chat.ws.sessions").gauge().value();
    }
}
```

- [ ] **Step 2: 실행** — `./gradlew experimentTest --tests 'jissuo.chat.experiment.ws.ReconnectLossExperiment'`. 예상(측정 전): `missedReceived=false`, `afterReceived=true`, `missedStored=true`(저장은 됐고 REST 조회에는 보인다).

- [ ] **Step 3: 브라우저 관찰** — 두 탭(A, B 사용자, 같은 방)에서 B의 개발자 도구 Network를 Offline으로 바꾸거나 백엔드를 재시작하는 동안 A가 보낸다. B가 다시 연결된 뒤 그 메시지가 보이지 않고, 새로고침하면 보인다는 것을 확인한다. 최초 조회와 구독 사이 틈(F49)도 같은 방식으로 관찰할 수 있으면 함께 적는다. 결과는 "측정"으로 일지에 적는다.

- [ ] **Step 4: 멈추고 보고** — 측정값과 예상을 나눠 보고한다. 해결 후보(재연결 뒤 마지막 id로 `after` 조회해 합치기, 서버 쪽 재전송 등)는 7단계로 제안만 한다.

---

## 검증 요약

| 무엇을 | 어떻게 |
|---|---|
| 백엔드 전체 | `cd backend && ./gradlew test` (ArchUnit 포함 전부 통과) |
| 장애 실험 | `cd backend && ./gradlew experimentTest --tests 'jissuo.chat.experiment.ws.*'` (작업 9~12, 웨이브마다 하나씩) |
| 프론트 | `cd frontend && npx vitest run && npx tsc -b && npm run lint && npm run e2e` |
| 핸드셰이크 인증 | `QueryUserIdHandshakeInterceptorTest`, `WebSocketHandshakeTest`(401·감사 `path=/ws`·게이지) |
| fan-out·WS 전송 | `ChatWebSocketContract`(MySQL·PostgreSQL): 같은 방 멤버의 모든 탭·보낸 사람 수신, 비멤버·다른 방 미수신, 오류 프레임, 잘못된 JSON 뒤 연결 유지 |
| 멤버 id 조회 | `MembershipRepositoryContract`(JDBC·JPA × 두 DB) |
| 전달 시간·WS 로그 | `DeliveryTimingAspectTest`, `WsAccessLogAspectTest`, `DeliveryMetricsTest`, `WsAccessLogTest`, `BenchProfileTest`(WS_ACCESS OFF) |
| 폴링 회귀 | 기존 `useRoomMessages`·`ChatRoomPage` 테스트를 `?transport=polling`으로, E2E 1건 |
| 브라우저 | 두 탭(두 사용자)에서 연결 상태 패널의 받은 프레임이 늘고 `messages?after=` 요청은 늘지 않는 채로 즉시 수신, 개발자 도구 WS 프레임 확인, 백엔드 재시작 시 1초 뒤 재연결과 그 사이 메시지 미수신(F6 예고) |
| 메트릭 | `/actuator/prometheus`에서 `chat_ws_sessions`, `chat_delivery_stage_seconds{stage,transport}`, `chat_delivery_total_seconds{transport}` |

## 범위 밖
서버 2대·nginx(P8), Redis(P9), STOMP 비교, k6 WebSocket 부하·W1~W5 성능 비교(ADR-128, 나머지 계획 뒤), 방 목록 실시간 갱신, 입장/퇴장 시스템 메시지, 재연결 따라잡기·ping/pong·비동기 전달 등 장애 해결책(재현 뒤 사용자와 결정).

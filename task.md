# 구현 태스크 (V1)

> 설계 근거: `docs/policy.md`(정책) · `docs/domain-model.md`(데이터 모델) · `docs/api-design.md`(API/Redis/WebSocket 계약)
> 원본 설계 문서: Notion `chat-server / 채팅서버 설계 문서` (01~10)

## 원칙

- 구현 중 새로운 정책·상태·동시성 문제가 발견되면 **코드에서 임의로 결정하지 않는다.** 해당 설계 문서(Notion 또는 `docs/`)로 돌아가 결정을 먼저 확정한 뒤 구현한다.
- 각 단계는 이전 단계가 동작하는 상태에서 시작한다. 뒷 단계 API 계약을 성급하게 먼저 고정하지 않는다.
- 정확성(불변식) 먼저, 성능 최적화는 실제 병목이 측정된 뒤에만 (`docs/api-design.md` 참고).

## 현재 상태

- `back/`: Spring Boot + Gradle 스캐폴딩 완료, 빌드 확인됨(`./gradlew build`).
- `front/`: Vite 스캐폴딩 완료, 빌드 확인됨(`npm run build`).
- `docker-compose.yml`: PostgreSQL 16 + Redis 7 기동 확인됨(`docker compose up -d`).
- 도메인 코드, API, WebSocket, Redis 연동 전부 미구현 — 아래 순서대로 시작.

## 구현 순서

### 1. 채팅방 생성 API
- `POST /api/chat-rooms` — `docs/api-design.md` 참고.
- `ChatRoom` 엔티티 + `ChatRoomMember` 엔티티, DDL 적용 (`docs/domain-model.md`의 DDL/인덱스).
- `ChatRoom` 생성 + 생성자 `ChatRoomMember` 생성을 하나의 트랜잭션으로 처리.
- 테스트: 생성자 멤버 저장 실패 시 전체 롤백되는지.

### 2. 채팅방 입장 API
- `POST /api/chat-rooms/{roomId}/join`.
- `ChatRoom` row 비관적 락(`SELECT ... FOR UPDATE`) 하에서 closed 여부 확인 → active membership 존재 시 성공 수렴, 없으면 INSERT.
- 활성 membership partial unique index(`uq_chat_room_member_active`) 적용 확인.
- 테스트: 닫힌 방 Join → 409. 동시 Join에서 활성 membership 1개만 남는지.

### 3. 채팅방 퇴장 API
- `POST /api/chat-rooms/{roomId}/leave`.
- 같은 `ChatRoom` row lock 하에서 `leftAt` 기록 → 활성 멤버 0명이면 `closedAt` 기록.
- 존재하지 않는 방 → 404. 활성 멤버 아님(이미 퇴장) → 멱등 성공.
- 테스트: 마지막 두 멤버 동시 퇴장 시 `closedAt`이 정확히 한 번 기록되는지 (Join/Leave 동시 경쟁 케이스 포함).

### 4. 채팅방 목록 API
- `GET /api/chat-rooms?cursor=&limit=`.
- Keyset(Cursor) pagination 쿼리 구현 (`docs/api-design.md`의 keyset 쿼리).
- `ix_chat_room_member_active_by_user` 인덱스로 조회.
- 테스트: 빈 목록 → 200 + `[]`. 메시지 없는 방의 정렬(`createdAt` 대체) 확인.

### 5. 메시지 전송 API
- `POST /api/chat-rooms/{roomId}/messages`.
- active Membership 확인 → Message INSERT → `lastMessageAt` GREATEST 갱신, 전부 한 트랜잭션.
- Redis publish는 이 단계에서는 없어도 됨 (6단계 이후 연결) — 커밋 성공 = API 성공.
- 테스트: 현재 멤버 아니면 403. send-vs-leave 동시 경쟁 시나리오. 동시 메시지 저장 시 `lastMessageAt` 후퇴 안 하는지.

### 6. 메시지 조회 API
- `GET /api/chat-rooms/{roomId}/messages?beforeMessageId=&limit=`.
- `ix_message_room_id` 인덱스 활용 쿼리.
- 테스트: 퇴장한 사용자는 403. `beforeMessageId` 없을 때 최신 구간 조회.

### 7. WebSocket 연결 / Session
- `/ws/chat` endpoint, Cookie 기반 Handshake 인증, Origin allowlist.
- Handshake에서 Spring Security 인증 결과로 Principal을 WebSocketSession에 귀속.
- SUBSCRIBE/UNSUBSCRIBE 이벤트 처리 + Local Room Session Registry (roomId → Set<Session>).
- 테스트: 미인증/허용 안 된 Origin 연결 거부. SUBSCRIBE 시 DB Membership 재검증.

### 8. Redis 접속 위치 (Target Server Routing 비교용)
- `chat:location:user:{userId}` Hash (`sessionId → serverId`), 서버 liveness key (`chat:server:{serverId}:alive`, heartbeat 5초/TTL 15초).
- 이 단계는 Room Channel 기본 경로에는 필요 없음 — 비교 실험용으로 별도 구현.

### 9. Redis Pub/Sub (Room Channel — V1 기본 라우팅)
- 로컬 room session 수 `0→1`에 `SUBSCRIBE chat:room:{roomId}`, `1→0`에 `UNSUBSCRIBE`.
- 메시지 커밋 후 `PUBLISH chat:room:{roomId}`. 수신 서버는 Local Registry 후보 조회 → DB active Membership 최종 검증 → WebSocket 전송.
- 테스트: Redis publish 실패해도 HTTP send/DB Message 유지. 퇴장 직후 stale registry가 남아도 전달 안 됨.

### 10. 재연결 / Catch-up
- 클라이언트가 room별 `lastMessageId` 전달 → SUBSCRIBE 처리 순서(`docs/api-design.md`)대로 실시간 경로 먼저 열고 DB catch-up.
- `messageId` 기준 dedupe + sort는 클라이언트 책임(서버는 catch-up 쿼리만 제공).
- 테스트: SUBSCRIBE 직전/직후 메시지 경쟁에서 누락/중복 없는지. Redis reconnect 뒤 구독 복구.

### 11. 전체 흐름 결합
- 프론트(`front/`)에서 로그인 → 목록 → 생성/입장 → WebSocket 연결 → 메시지 송수신 → 재연결까지 end-to-end 연결.
- 2개 이상 서버 인스턴스로 분산 시나리오 통합 테스트 (같은 방, 다른 서버에 연결된 두 사용자 간 메시지 전달).

### 12. 테스트 / 검증
- `docs/api-design.md`의 "구현 시 핵심 테스트 체크리스트" 전체 통과 확인.
- `docs/domain-model.md`의 핵심 불변식이 동시성 테스트로 실제 보장되는지 확인.
- (선택, 학습 목적) Room Channel vs Target Server Routing 정량 비교: p95 delivery latency, routing work/message, throughput — Notion `10. 최종 아키텍처 · 검증`의 지표 정의 참고.

## 참고하지 않아도 되는 것 (V1 스코프 아님)

1:1 채팅, 파일/이미지 메시지, 메시지 수정/삭제, 읽음 처리, Push Notification, 방장/관리자 권한, 초대/비밀번호/승인형 방, 최대 인원 제한, Kafka, 메시지 idempotency key, WebSocket 강제 재인증, 자동 WS 재연결, Chat Server 자체 User 테이블. (`docs/policy.md` 참고)

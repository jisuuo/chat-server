# API · Redis · WebSocket 설계 (V1)

> 출처: Notion `09. API · DB 상세 설계`, `10. 최종 아키텍처 · 검증` (설계 확정)
> 데이터 모델은 `docs/domain-model.md`, 규칙 근거는 `docs/policy.md` 참고.

## HTTP API

| 기능 | Method / Path | 핵심 Request | 성공 | 주요 실패 |
|---|---|---|---|---|
| 채팅방 생성 | `POST /api/chat-rooms` | `{ name }` | `201`, `{ roomId, name, createdAt }` | 400 / 401 |
| 내 채팅방 목록 | `GET /api/chat-rooms?cursor=&limit=` | cursor=`(sortAt,roomId)` opaque encoding, 기본 20/최대 100 | `200`, items + nextCursor | 400 / 401 |
| 채팅방 입장 | `POST /api/chat-rooms/{roomId}/join` | 없음 | `200`/`204`, 중복 요청도 성공 수렴 | 401 / 404 / 409(closed) |
| 채팅방 퇴장 | `POST /api/chat-rooms/{roomId}/leave` | 없음 | `200`/`204`, 중복 요청도 성공 수렴 | 401 / 404 |
| 메시지 전송 | `POST /api/chat-rooms/{roomId}/messages` | `{ content }` | DB commit 기준 `201`, message 식별/시간 반환 | 400 / 401 / 403 / 404 |
| 메시지 목록 | `GET /api/chat-rooms/{roomId}/messages?beforeMessageId=&limit=` | 기본 50/최대 100 | `200`, `{messageId,senderId,senderName,content,createdAt}[]` | 400 / 401 / 403 / 404 |

- 채팅방 **단건 조회 API는 V1에서 만들지 않는다** (현재 유스케이스에 필요 없음. 딥링크 등 필요성이 확인되면 추가).
- `createdBy`, `senderId`는 Request body로 받지 않는다. 인증된 Principal의 `userId`가 기준이다.
- 메시지 전송 HTTP 성공은 **DB Message 저장 + `lastMessageAt` 갱신 트랜잭션 commit**으로 정의한다. 이후 Redis publish/WS 전달 실패는 이 트랜잭션을 되돌리지 않는다.
- 오류 body 공통 형태: `{ code, message, details? }`. 클라이언트 분기는 HTTP status + 안정적인 `code` 기준.
- 공통 오류 매핑: 인증 없음 → 401, 방 없음 → 404, 현재 멤버 아니라 권한 없음 → 403, 요청값 검증 실패 → 400, 닫힌 방 Join → 409 (방은 존재하므로 404가 아니라 상태 충돌).

### Validation

- `ChatRoom.name`: required, trim 후 blank 금지, 최대 100자.
- `Message.content`: required, trim 후 blank 금지, 최대 2,000자.
- pagination `limit`: 1 이상. room list 기본 20/최대 100, message list 기본 50/최대 100. 초과 시 400.
- 유효하지 않은 cursor 형식은 400.

### 채팅방 목록 Response

- 항목: `roomId`, `name`, `lastMessage`, `lastMessageAt` (`memberCount`는 V1 미포함 — 사용처 없음).
- 메시지 없는 방은 `lastMessage = null`, `lastMessageAt = null`.
- 정렬: `sortAt = lastMessageAt ?? createdAt`, `ORDER BY sortAt DESC, chatRoomId DESC`.

**Keyset 조회 쿼리 (개념)**:

```sql
SELECT r.id, r.name, r.last_message_at,
       COALESCE(r.last_message_at, r.created_at) AS sort_at
FROM chat_room_member m
JOIN chat_room r ON r.id = m.chat_room_id
WHERE m.user_id = :userId
  AND m.left_at IS NULL
  AND (
       :cursorSortAt IS NULL
       OR (COALESCE(r.last_message_at, r.created_at), r.id) < (:cursorSortAt, :cursorRoomId)
  )
ORDER BY COALESCE(r.last_message_at, r.created_at) DESC, r.id DESC
LIMIT :limitPlusOne;
```

- `limit + 1`건을 읽어 다음 페이지 존재 여부를 판단하고, 응답에는 `limit`건만 노출한다.
- `sortAt`은 mutable이므로 페이지 이동 중 새 메시지가 오면 duplicate/miss 가능성을 V1에서 허용한다 (snapshot pagination 안 함).
- `lastMessage` 본문은 목록 N건에 대해 N+1 개별 쿼리를 만들지 않도록 lateral/subquery 또는 batch query로 조회한다.

### 메시지 목록 쿼리 (개념)

```sql
SELECT id, sender_id, content, created_at
FROM message
WHERE chat_room_id = :roomId
  AND (:beforeMessageId IS NULL OR id < :beforeMessageId)
ORDER BY id DESC
LIMIT :limitPlusOne;
```

- DB에서 최신→과거 DESC로 가져온 뒤 필요하면 응답 계층에서 화면 순서로 재정렬한다.
- `id` gap은 정상이다. `id + 1` 존재를 가정하지 않는다.

### 메시지 Response

- `senderId`와 함께 `senderName`을 포함한다: `{ messageId, senderId, senderName, content, createdAt }`.
- 사용자 이름 조회는 `docs/domain-model.md`의 "외부 사용자 경계" 참고 (batch resolve, per-message 원격 호출 금지).

## WebSocket

- Endpoint: `/ws/chat` (`wss://.../ws/chat`).
- Handshake 인증은 **Cookie 기반**, 기존 Spring Security 인증 계층 재사용 (HandshakeHandler는 JWT를 직접 검증하지 않음). 인증된 Principal을 WebSocketSession에 귀속한다.
- Origin은 신뢰 가능한 frontend allowlist와 비교해 허용되지 않은 출처는 거부한다.
- V1은 handshake 시점 인증만 한다. 연결 후 토큰 만료/로그아웃을 기존 소켓에 즉시 반영하는 active revocation은 V1 제외.
- HTTP Join/Leave = DB Membership 변경, WS SUBSCRIBE/UNSUBSCRIBE = 실시간 수신 상태 변경. 서로 다른 상태다.

**Client → Server**:
```json
{ "type": "SUBSCRIBE", "roomId": 123 }
{ "type": "UNSUBSCRIBE", "roomId": 123 }
```

**Server → Client**:
```json
{
  "type": "MESSAGE",
  "roomId": 123,
  "messageId": 501,
  "senderId": "user-10",
  "senderName": "display name",
  "content": "hello",
  "createdAt": "2026-09-04T12:34:56Z"
}
```

**SUBSCRIBE 처리 순서**:
1. DB active Membership 검증 성공 후 Local Room Registry에 session 등록.
2. 해당 room의 로컬 session 수 `0 → 1`이면 Redis `SUBSCRIBE chat:room:{roomId}`.
3. 클라이언트가 보낸 `lastMessageId` 기준 DB catch-up.
4. catch-up 결과와 실시간(live) 이벤트를 `messageId` 기준 dedupe + sort.
   (실시간 경로를 먼저 열고 과거 gap을 DB로 메워 subscribe 사이 유실 구간을 줄인다.)

**WebSocket 종료 시**: 해당 physical session의 모든 room registry 참조를 제거한다. 로컬 session 수가 `1 → 0`이 되면 Redis `UNSUBSCRIBE`. **DB `leftAt`은 변경하지 않는다** (연결 종료 ≠ 채팅방 퇴장).

## Redis

**Room Channel (V1 기본 라우팅)**:
- 채널명: `chat:room:{roomId}`.
- Message commit 후 해당 채널에 1회 publish. 실제 local realtime session이 있는 서버만 동적으로 subscribe.

**Target Server Routing (비교 실험용, 기본 아님)**:
```
Key   = chat:location:user:{userId}
Field = {sessionId}
Value = {serverId}
```
- 한 사용자의 여러 탭/기기/서버 연결을 동시에 표현한다. session 종료 시 해당 field만 제거 (다른 연결 위치는 유지).
- 대상 사용자 위치를 읽은 뒤 `serverId`를 dedupe하여 살아 있는 대상 서버 채널(`chat:server:{serverId}`)에 publish.

**서버 생존 (Heartbeat/TTL)**:
- Key: `chat:server:{serverId}:alive`. heartbeat 5초, TTL 15초.
- TargetServerRouting은 위치가 존재해도 liveness key가 유효한 서버만 대상으로 사용한다.
- 비정상 종료 후 최대 약 15초 stale window는 V1에서 허용.

**장애 처리**:
- Redis는 realtime best-effort 경로이며 Message SSOT는 PostgreSQL이다.
- Redis publish 실패로 이미 성공한 DB 트랜잭션을 rollback하지 않는다.
- Redis 복구 뒤 유실 이벤트를 자동 republish하지 않는다. 다음 room 재진입/WS reconnect/명시적 refresh에서 DB catch-up으로 복구.
- Redis client 재연결 시 현재 Local Room Registry 기준으로 필요한 room channel들을 다시 subscribe한다.
- Redis client는 Spring Boot 기본 Lettuce 계열 사용, command timeout 2초 수준, auto-reconnect 사용 (운영 튜닝값이며 정책값 아님).

## 실시간 Membership 검증 쿼리

정확성 기준(고정): **Local Room Registry는 후보만 찾고, DB active Membership이 최종 권한 판단을 한다.**

```sql
-- 개별 검증
SELECT 1 FROM chat_room_member
WHERE chat_room_id = :roomId AND user_id = :userId AND left_at IS NULL;

-- Batch 검증 (비교 구현)
SELECT user_id FROM chat_room_member
WHERE chat_room_id = :roomId AND user_id IN (:candidateUserIds) AND left_at IS NULL;
```

어느 방식을 최종 채택할지는 부하 테스트 수치(DB query count/message, validation latency p95, throughput)로 결정한다 (`task.md`의 라우팅/검증 비교 단계 참고).

## 구현 시 핵심 테스트 체크리스트

- [ ] ChatRoom 생성 + creator Membership이 하나의 트랜잭션으로 원자적인지
- [ ] 동일 사용자의 동시 Join에서 active membership이 1개만 남는지
- [ ] 마지막 멤버 동시 Leave에서 `closedAt`이 정확히 한 번 terminal 상태로 수렴하는지
- [ ] closed room Join이 409인지
- [ ] send-vs-leave 경쟁이 lock/commit 순서에 따라 정책대로 결정되는지
- [ ] 동시 Message 저장에서 `lastMessageAt`이 뒤로 가지 않는지
- [ ] Redis publish 실패 후에도 HTTP send/DB Message가 유지되는지
- [ ] SUBSCRIBE 직전/직후 message 경쟁에서 catch-up + live merge로 누락/중복이 정리되는지
- [ ] 퇴장 완료 뒤 stale Local Registry가 남아도 최종 DB Membership 검증 때문에 WS 전달되지 않는지
- [ ] 여러 session 중 하나 disconnect 시 다른 session 위치/구독이 삭제되지 않는지
- [ ] Redis reconnect 뒤 살아 있는 local room subscription이 복구되는지

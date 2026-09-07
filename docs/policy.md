# 정책 (V1)

> 출처: Notion `chat-server / 채팅서버 설계 문서 / 02. 정책` (설계 확정)
> 이 문서는 "구현자가 임의로 판단하면 안 되는 규칙"만 담는다. 판단 기준: **이 정책이 없으면 구현자가 개발 중 임의로 판단하게 되는가?** YES면 정책, NO면 API/DB 상세 설계나 구현 단계에서 다룬다.

## 채팅방

1. 그룹 채팅만 지원한다 (1:1 전용 정책은 V1 제외).
2. 방 이름은 필수다.
3. 로그인 사용자 누구나 채팅방을 생성할 수 있다 (별도 관리자 권한 없음).
4. 생성자는 생성과 동시에 자동 입장한다. `ChatRoom` 생성 + 생성자 `ChatRoomMember` 생성은 하나의 트랜잭션이다.
5. 방 이름 중복을 허용한다. 실제 식별자는 `chatRoomId`다.
6. 최대 인원 제한은 없다.
7. 모든 방은 공개다 (초대/비밀번호/승인 정책 없음).
8. 방장/관리자 개념은 없다 (역할 기반 권한 정책 V1 제외).
9. 마지막 멤버가 나가면 방은 **닫힌 상태**가 된다. 방/메시지 데이터는 보존하지만 활성 목록에서 숨기고, `chatRoomId`를 알아도 재입장은 허용하지 않는다. 즉 현재 멤버가 1명 이상일 때만 활성 상태다.

## 멤버십 · 권한

1. 재입장하면 전체 과거 메시지를 조회할 수 있다.
2. 중복 입장 요청은 성공 처리한다 (원하는 최종 상태가 이미 달성된 멱등 요청으로 본다).
3. 중복 퇴장 요청도 성공 처리한다.
4. 현재 방 멤버만 메시지를 전송할 수 있다.
5. 현재 방 멤버만 메시지를 조회할 수 있다. 퇴장 이력이 있어도 현재 멤버가 아니면 조회 불가.
6. 퇴장 이력은 `leftAt`으로 보존한다.
7. 재입장 시 새로운 `ChatRoomMember` row를 생성한다 (기존 row를 덮어쓰지 않고 참여 구간을 보존).
8. 활성 membership 중복은 Application + DB 양쪽에서 방어한다. Application은 정상 흐름/친화적 응답, DB는 동시성 상황의 최종 정합성 방어선이다.

## 메시지 · 실시간

1. 빈 메시지를 금지한다 (null/empty/whitespace-only 불허).
2. 메시지 최대 길이는 1,000자다 (API 상세 설계에서 2,000자로 재확정됨 — `docs/api-design.md` 참고).
3. DB 저장 후 실시간 전달한다. DB를 Source of Truth로 둔다.
4. DB 저장 성공 + 실시간 전달 실패여도 메시지 전송 API는 성공이다. 실시간 실패는 저장 실패가 아니다.
5. 원래 방 생성자는 `ChatRoom.createdBy`로 기록한다 (권한이 아니라 감사/이력 정보).
6. `createdBy`는 Request Body가 아니라 서버 인증 사용자(Principal)에서 결정한다.
7. 송신자를 포함한 현재 멤버 전원에게 WebSocket으로 전달한다 (송신자/수신자의 화면 반영 경로를 하나로 맞춘다).
8. V1 전달 보장은 **DB 저장 성공까지**다. 상대 기기 도착/읽음은 보장하지 않는다.
9. 재연결 시 마지막 `messageId` 이후를 DB에서 catch-up한다.
10. DB catch-up + WebSocket 메시지는 `messageId`로 병합·중복 제거·정렬한다.

### 메시지 전송 성공 경계

- 메시지 전송 API의 최소 성공 기준은 메시지의 **DB 영속 저장 완료**다.
- DB 저장이 성공하면 HTTP 응답을 성공으로 반환하고, Redis Pub/Sub 및 WebSocket 실시간 전달은 별도의 후속 경로로 처리한다.
- 실시간 전달 실패는 이미 저장된 메시지 전송 성공을 뒤집지 않는다.
- 단, DB 저장 직후 서버가 종료되어 실시간 전달 자체가 시작되지 못할 수 있다. V1에서는 재연결 catch-up으로 복구하며, 더 강한 실시간 이벤트 전달 보장이 필요해지면 Outbox 등의 방식을 재검토한다.

### 메시지 전송 멱등성

- V1에서는 **네트워크 재시도로 인해 동일 내용의 메시지가 중복 저장될 수 있음을 허용**한다.
- 서버는 메시지 내용만으로 사용자의 의도적 반복 전송과 네트워크 재시도에 따른 중복 전송을 구분할 수 없으므로 내용 비교 기반 중복 제거는 하지 않는다.
- `clientMessageId` 같은 멱등성 키, DB UNIQUE 제약, 동일 키 재사용 충돌 정책은 V1에서 제외한다.
- 재검토 조건: 중복 메시지가 실제 운영 이슈가 되면 `clientMessageId + UNIQUE` 기반 멱등성을 고도화한다.

## 채팅방 목록

- 개인 채팅방 목록에는 **현재 사용자가 멤버로 속한 방만** 노출한다 (공개 여부와 개인 목록 노출 여부는 별개).
- 기본 정렬은 최근 활동순이다. `sortAt = lastMessageAt ?? createdAt`, `ORDER BY sortAt DESC, chatRoomId DESC`.
- 목록에는 방 이름, 마지막 메시지 내용, 마지막 메시지 시간을 포함한다. 말줄임 등 표시 책임은 프론트엔드가 처리한다 (서버는 원문 그대로 반환).
- Cursor Pagination을 사용한다. Cursor의 논리적 기준은 `sortAt + chatRoomId` (동일 `sortAt`의 tie-breaker로 `chatRoomId` 사용).
- 현재 속한 방이 0개면 `200 OK` + 빈 목록을 반환한다 (0건은 컬렉션 조회의 정상 결과이지 에러가 아니다).

## 메시지 조회

- 현재 활성 멤버만 메시지를 조회할 수 있다. 퇴장한 사용자는 과거 참여 이력이 있어도 조회 권한이 없다.
- 메시지 목록은 `messageId` 기반 Cursor Pagination(`beforeMessageId`)으로 조회한다.

## 인증 · 인가 (요약 — 상세는 06. 보안 문서 참고)

- 채팅방 생성자, 메시지 송신자는 클라이언트가 보낸 값이 아니라 **서버가 인증한 Principal**을 사용한다.
- WebSocket Handshake에서 Cookie 기반으로 인증하고, 인증된 Principal을 WebSocketSession에 귀속한다. 이후 클라이언트가 보내는 `userId`는 신원 판단에 쓰지 않는다.
- 방 접근 권한(메시지 송수신, Room Subscribe)의 최종 Source of Truth는 DB `ChatRoomMember(leftAt IS NULL)`다. Local Room Session Registry는 후보 탐색용일 뿐 권한 판단 근거가 아니다.
- WebSocket Handshake에서 Origin allowlist를 적용해 허용되지 않은 출처의 연결을 거부한다.
- 연결 중 토큰 만료/로그아웃은 기존 WebSocket에 즉시 반영하지 않는다 (재연결 시에만 재인증). V1에서 의도적으로 포기.

## 동시성 규칙 요약 (상세는 05. 실패·동시성 문서 참고)

- 같은 채팅방의 Join/Leave는 `ChatRoom` row에 비관적 락(`SELECT ... FOR UPDATE`)을 걸어 직렬화한다. 마지막 멤버 퇴장 → `closedAt` 기록, 닫힌 방 Join → 거부(409)를 하나의 락 안에서 보장한다.
- 활성 membership은 서비스 검사 + DB partial unique index(`(chat_room_id, user_id) WHERE left_at IS NULL`) 이중 방어로 최대 1개만 허용한다.
- `ChatRoom.lastMessageAt`은 `GREATEST(old, new)`로 단조 증가만 허용한다 (동시 메시지 저장 시 과거 값으로 후퇴 금지).
- 메시지 전송이 멤버십 검사를 먼저 통과했다면, 이후 거의 동시에 처리된 퇴장이 있어도 해당 전송은 허용한다. 반대로 퇴장이 먼저 커밋되면 이후 전송은 거부한다.
- 실시간 fan-out 직전에는 Local Registry 후보와 무관하게 DB 활성 멤버십을 반드시 재검증한다 (퇴장한 사용자에게 메시지가 새지 않도록).

## V1 제외 범위 (참고: `01. 문제·요구사항·범위·유스케이스`)

**포함**: 그룹 채팅, 채팅방 생성/목록/입장/퇴장, 메시지 전송/조회, DB 영속화, WebSocket 실시간 전달, 멀티 인스턴스 간 사용자 위치 확인 및 메시지 전달, 재연결 시 DB 기반 누락 메시지 복구.

**제외**: 1:1 전용 기능, 파일/이미지 메시지, 메시지 수정/삭제, 읽음 처리, Push Notification, 방장/관리자 권한, 초대/비밀번호/승인형 방, 최대 인원 제한, Kafka, Exactly Once, 메시지 전송 idempotency key, WebSocket active token revocation, 자동 WS 재연결, Chat Server 자체 User master.

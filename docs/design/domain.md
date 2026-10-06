# 도메인 정의

> 누적 문서. 요구사항에서 용어와 규칙을 뽑고, 규칙을 함께 지키는 단위(경계)와 의존 방향을 정한다 (ADR-036 ~ 039).
> 규칙이 바뀌면 이 문서를 먼저 고치고, 담당 클래스와 테스트를 맞춘다.

## 1. 용어 (유비쿼터스 언어)

코드의 클래스와 메서드 이름은 이 용어를 그대로 쓴다.

| 용어 | 코드 이름 | 뜻 | 근거 |
|---|---|---|---|
| 사용자 | `User`, `AuthUser` | 채팅을 하는 사람. 지금은 `X-User-Id` 번호로만 식별 | ADR-005, 031 |
| 채팅방 | `Room` | 대화가 일어나는 공개 공간. 멤버가 0명이어도 남는다 | ADR-012 |
| 멤버십 | `Membership` | 사용자가 방에 **가입한 관계**. 입장하면 생기고 나가면 사라진다. 입장 경계를 가진다 | ADR-007, 010 |
| 입장 경계 | `JoinBoundary` | 이 멤버가 어느 메시지부터 볼 수 있는지 표시하는 지점. 메시지 번호 기준과 시각 기준 두 구현 (F18) | ADR-008 |
| 메시지 | `Message` | 멤버가 방에 남긴 글. 번호 순서가 곧 대화 순서 | ADR-017 |
| 메시지 내용 | `MessageContent` | 1~1000자의 메시지 본문 | ADR-032 |
| 커서 | `MessageCursor` | "여기서부터 이어서"를 가리키는 메시지 번호 (`after`, `before`) | ADR-017 |

## 2. 규칙

| # | 규칙 | 담당 | 최종 판정 | 근거 |
|---|---|---|---|---|
| R1 | 방을 만든 사람은 자동으로 그 방의 멤버가 된다 | `RoomService`(생성과 입장을 한 트랜잭션) | 트랜잭션 | ADR-017 |
| R2 | 한 사용자는 한 방에 멤버로 최대 한 번만 있을 수 있다 | `Membership` | **DB UNIQUE** `(room_id, user_id)` | ADR-010 |
| R3 | 멤버만 그 방에 메시지를 보내고 볼 수 있다 | `MessageService`(멤버십 조회 후 진행) | 멤버십 행의 존재 | ADR-011 |
| R4 | 멤버는 자기 입장 경계 이후의 메시지만 볼 수 있다 | `Membership`, `JoinBoundary` | 조회 조건 | ADR-008 |
| R5 | 나가면 멤버십이 사라진다. 방은 남는다 | `RoomService` | 행 삭제 | ADR-010, 012 |
| R6 | 방 이름은 1~50자, 메시지 내용은 1~1000자 | `RoomName`, `MessageContent` | 값 객체 생성 시 | ADR-032 |
| R7 | 방 목록은 마지막 메시지가 최근인 순서로 보인다 | `MessageService`가 전송 시 방의 마지막 메시지 번호 갱신 | 조건부 UPDATE | ADR-016 |

담당 클래스와 검증 테스트의 연결은 구현하면서 이 표에 "테스트" 열을 추가해 채운다.

## 3. 경계 (애그리거트)

### 나누는 기준

| # | 질문 | 같은 묶음이면 |
|---|---|---|
| 1 | 이 규칙을 지키려면 어떤 데이터들이 **같은 순간에** 맞아야 하나? | 한 트랜잭션에서 함께 저장 (가장 중요) |
| 2 | 함께 생기고 함께 사라지나? (생명주기) | 같은 묶음 후보 |
| 3 | 얼마나 자주, 얼마나 많이 바뀌나? | 많이 바뀌는 것을 묶으면 잠금 경합 → 분리 |
| 4 | 한 번에 불러와야 하는 양이 감당 가능한가? | 수만 건이면 분리 |

### 적용 결과

| | 채팅방 `Room` | 멤버십 `Membership` | 메시지 `Message` |
|---|---|---|---|
| 함께 맞아야 하는 규칙 | R6(방 이름), R7(정렬 기준 보관) | R2, R4, R5 | R6(내용) |
| 생명주기 | 만들면 계속 남음 | 입장 때 생기고 나가면 사라짐 | 보내면 생기고 계속 남음 |
| 변경 빈도 | 거의 없음 (마지막 메시지 번호 제외) | 가끔 | 매우 잦음 |
| 양 | 방 1개 | 방마다 수십~수만 | 방마다 수백만 |
| 패키지 | `room` | `room` | `message` |

- 애그리거트끼리는 **id로만** 참조한다 (`Message`는 `Room` 객체가 아니라 `roomId`를 가진다).
- 멤버십을 방 안에 넣지 않는 이유: 넣으면 멤버 한 명이 들어올 때마다 방의 멤버 전체를 불러와야 한다.

### 경계에 걸친 규칙과 타협

| 규칙 | 엄밀히는 | 실제 선택 | 대가 |
|---|---|---|---|
| R2 한 방에 한 번만 | 방의 멤버 전체를 봐야 하므로 멤버십이 방 안에 있어야 함 | DB UNIQUE 제약에 맡김 (기준 4 우선) | 없음. 동시 입장도 DB가 막음 |
| R3 멤버만 전송 | 멤버십과 메시지가 같은 순간에 맞아야 함 | 메시지 서비스가 "멤버십 확인 → 저장" 순서로 처리 | 확인과 저장 사이에 나가기가 끝나면 나간 사람의 메시지가 저장될 수 있음 (F23, 예상, 미검증, 장애 대응 규칙에 따라 고치지 않음) |

## 4. 패키지와 계층 (ADR-038)

```
jissuo.chat
 ├─ room/                 채팅방 + 멤버십
 │   ├─ domain/           Room, RoomName, Membership, JoinBoundary, RoomRepository, MembershipRepository
 │   ├─ application/      RoomService (생성, 목록, 입장, 나가기)
 │   ├─ infra/jdbc/       JdbcRoomRepository, JdbcMembershipRepository
 │   └─ api/              RoomController, 요청/응답 DTO
 ├─ message/
 │   ├─ domain/           Message, MessageContent, MessageCursor, MessageRepository
 │   ├─ application/      MessageService (전송, 조회)
 │   ├─ infra/jdbc/       JdbcMessageRepository (스키마 A), JdbcMessageRepositoryB (스키마 B)
 │   └─ api/              MessageController, 요청/응답 DTO
 ├─ user/                 사용자 식별, 개발용 생성 API (같은 4계층)
 ├─ auth/                 인증 (기술 관심사, 도메인 아님)
 ├─ audit/                감사 (기술 관심사, 이벤트 수신)
 └─ common/               ApiResponse, ErrorCode, 전역 예외 처리
```

| 계층 | 하는 일 | 하지 않는 일 |
|---|---|---|
| `domain/` | 용어와 규칙, 저장소 인터페이스 (ADR-036) | DB, Spring, HTTP를 모름 |
| `application/` | 요청 하나의 흐름, 트랜잭션 범위, 여러 애그리거트에 걸친 규칙 (R1, R3, R5, R7) | 규칙 자체를 판단하지 않고 도메인에 물어봄 |
| `infra/` | DB 접근 (`JdbcClient`) | 규칙 판단 |
| `api/` | HTTP 요청/응답 변환, `ApiResponse` | 규칙 판단 |

## 5. 의존 방향 (ADR-039)

**기준: 판단이 필요한 쪽이 판단을 가진 쪽을 의존하되, 화살표는 항상 "자주 바뀌는 쪽 → 덜 바뀌는 쪽"을 향한다.** 두 조건이 어긋나면 인터페이스나 이벤트로 방향을 뒤집는다. 방향은 중요도가 아니라 변경 빈도로 정한다.

```
         ┌────────── common ──────────┐  (모두가 의존)
 auth ←─ api ─→ application ─→ domain ←─ infra
  message.application ──────→ room.domain   (기능 사이: 상대의 domain만)
  audit ···(이벤트 수신)···> room, message   (감사는 듣기만)
```

| 규칙 | 내용 | 이유 |
|---|---|---|
| 계층 | `api → application → domain ← infra`. `domain`은 아무것도 의존하지 않는다 | 규칙이 가장 안 바뀌고, HTTP와 DB 기술이 가장 자주 바뀐다 |
| 기능 사이 | `message → room`만 허용. 상대 기능은 `domain/`만 부른다 (`RoomService` 호출 금지) | 메시지가 멤버십 판단(R3)과 방 갱신(R7)을 필요로 하고, 메시지가 더 자주 바뀐다 (Step 2~5). 서비스끼리 부르면 트랜잭션과 순환이 꼬인다 |
| 방 목록 갱신 (R7) | 지금은 메시지 서비스가 `RoomRepository`로 직접 갱신 | 단순함. Step 2~4에서 "메시지 보냄" 이벤트가 필요해지면 이벤트 방식을 다시 판단한다 |
| 감사 | 방과 메시지가 이벤트를 발행하고 `audit`이 듣는다 (커밋 후, ADR-023) | 감사를 빼거나 바꿔도 도메인 코드가 바뀌지 않는다 |
| 인증 | `api`가 `auth`를 의존. `auth`는 도메인을 모른다 | 인증은 다른 서비스에서도 같다 |
| 사용자 | `room`은 `user` 패키지를 의존하지 않는다. `userId` 값과 DB FK로만 연결 | 없는 사용자 판정은 FK 위반으로 한다 (ADR-031) |

### 자동 검사 (ArchUnit)

위 규칙을 JUnit 테스트로 검사한다. 규칙을 어기는 코드는 테스트가 실패한다. 이것은 장애를 미리 고치는 것이 아니라 정한 규칙을 확인하는 테스트다.

```java
noClasses().that().resideInAPackage("..room..")
    .should().dependOnClassesThat().resideInAPackage("..message..");
noClasses().that().resideInAPackage("..domain..")
    .should().dependOnClassesThat().resideInAnyPackage("..infra..", "..api..", "..application..", "org.springframework..");
```

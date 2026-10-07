# 계획 6: 채팅 UI/UX 개편 + JDBC → JPA 전환 구현 계획 (고도화 P6)

> **실행하는 에이전트에게**: 작업은 아래 "실행 순서와 병렬화"의 **웨이브 단위로 사용자 승인을 받고** 시작한다. 같은 웨이브의 작업은 동시에 진행할 수 있다. 웨이브가 끝나면 통합 확인을 하고 결과(테스트 출력 포함)를 보고한 뒤 멈춘다. **커밋하지 않는다** (사용자가 요청할 때만). 단계는 체크박스(`- [ ]`)로 추적한다.

**목표:** 지금의 디버그 위주 화면을 일반 채팅 앱처럼 바꾼다. 방 목록 사이드바 + 대화 화면, 내/남 말풍선, 시간·날짜 구분선, 하단 고정 입력창, 읽던 위치를 지키는 스크롤을 갖춘다. 수신은 아직 폴링이다.

**추가 목표 (백엔드 트랙, 2026-10-07 사용자 요청):** 저장소 구현을 `JdbcClient`에서 JPA로 바꾸고, 바꾸면서 **실제로 나아진 점과 나빠진 점을 측정해서** 확인한다. 방식은 ADR-024·025(구현체 두 개를 두고 설정 `chat.repository=jdbc|jpa`로 고르기, 공통 계약 테스트, 성능 비교 F21)를 따른다.

**구조:** 메시지 상태(최초 조회·폴링·전송·이전 메시지·입장·나가기)를 `useRoomMessages` 훅 하나로 모은다. 다음 계획(P7 WebSocket)에서는 이 훅의 수신 부분만 바꾸고 화면은 그대로 둔다. 화면은 표시용 순수 함수(`chatItems.ts`), 스크롤 훅(`useChatScroll.ts`), 표시 컴포넌트(`MessageList`, `Composer`)로 나눠 각각 단위 테스트한다.

**기술:** 계획 4와 같다. React 19.3.0, Vite 8.3.3, TypeScript 6.0.3, Vitest 5.0.3 + jsdom + Testing Library, Playwright 1.63.0. 프론트에는 새 라이브러리를 추가하지 않는다. 백엔드 트랙만 `spring-boot-starter-data-jpa`(Spring Boot 4.1.1이 버전 관리, Hibernate)를 추가한다.

## Context
- 고도화 개요(2026-10-07 승인): P6 UI 개편 → P7 WebSocket 1대 → P8 서버 여러 대(F7 재현) → P9 Redis 사용자 채널 Pub/Sub. 이 계획은 P6이다.
- 조사한 현재 화면의 불편한 점
  - 오른쪽 240px 폴링 패널이 항상 보인다.
  - 모든 메시지가 같은 줄 형식(`사용자 #N 내용`)이라 내 메시지와 남의 메시지를 구분할 수 없다. 시간이 보이지 않는다.
  - 새 메시지가 오면 위로 올려 읽던 중이어도 맨 아래로 끌려 내려간다. "이전 메시지 더 보기"를 누르면 보던 위치가 밀린다.
  - 대화 목록 높이가 `60vh`로 고정돼 있고, 입력창이 한 줄 `input`이다. Enter와 줄바꿈을 구분하지 않는다.
  - 방 목록과 채팅방이 다른 화면이라 방을 옮길 때마다 뒤로 가야 한다. 목록에 `#2 · 마지막 메시지 #5` 같은 디버그 문구가 보인다.
  - 오류 문구에 `(NOT_A_MEMBER)` 같은 코드가 그대로 노출된다.
- 이미 결정된 것 (그대로 지킨다): ADR-082(hash 라우팅, 방을 옮기면 `key`로 다시 만듦, sessionStorage), ADR-083(폴링 예약·커서는 조회 응답으로만 전진), ADR-084(방 목록 자동 갱신 없음, 멤버 판단은 403으로), ADR-085(입력 검사는 서버 한 곳), ADR-086(5173 고정, proxy).

## 지켜야 할 조건
- **장애 선행 (ADR-034)**: F33(메시지 전송 연속 제출로 중복 메시지 생성)과 F32(방 만들기 중복 제출)를 화면에서 미리 막지 않는다. **전송 중 버튼 비활성화나 낙관적 표시를 넣지 않는다.** F22, F27도 보정하지 않는다. 개편 중 발견한 위험은 `docs/failure-lab.md`에 가설로만 적는다.
- 폴링 동작(ADR-083)은 바꾸지 않는다. `usePolling.ts`, `merge.ts`는 수정하지 않는다.
- 백엔드 변경은 작업 6A의 사용자 조회 API와 백엔드 트랙(작업 8~13, JPA 구현체 추가)뿐이다. 기존 API 동작은 바꾸지 않고, `chat.repository`의 기본값은 비교가 끝날 때까지 `jdbc`로 둔다. `./gradlew test`가 그대로 통과해야 한다.
- JPA 전환에서 예상되는 문제(아래 가설 H1~H6)도 ADR-034대로 **미리 피해 가지 않는다.** 가장 흔한 방식(Spring Data JPA `save()`, 쓰기 지연 그대로)으로 구현하고, 계약 테스트가 실패하면 멈추고 보고한 뒤 사용자와 함께 분석한다.
- E2E가 쓰는 이름은 유지한다: label `닉네임`, `방 이름`, `메시지` / 버튼 `새 사용자로 시작`, `방 만들기`, `입장`, `보내기`, `나가기` / `list` 이름 `대화` / URL `#/rooms/{id}`.
- CSS는 `src/styles.css` 한 파일(계획 4 세부 5). 색은 `:root` 변수로 두고 `prefers-color-scheme: dark`에서 바꾼다.
- 타입만 가져올 때는 `import type`. 주석은 "왜"만 쓰고, 이 계획의 세부를 근거로 하면 작업 중에는 `계획 6 세부 #n`으로 적고 작업 7에서 ADR 번호로 바꾼다.
- 측정·관찰 결과를 적을 때 "예상"과 "측정"을 구분한다.

## 이 계획에서 새로 정하는 세부 (검토 필요, 승인되면 작업 7에서 ADR로 기록)

> ADR-101~104는 계획 5b가 이미 썼다. 작업 7은 기록하는 시점의 **다음 빈 번호부터** 쓴다.
| # | 항목 | 제안 | 이유 |
|---|---|---|---|
| 1 | 화면 구성 | 넓은 화면: 왼쪽 방 목록 사이드바(280px) + 오른쪽 대화. 640px 이하: 방이 선택되면 대화만, 아니면 목록만 | 방을 옮길 때 뒤로 가지 않는다. 모바일은 한 화면씩 |
| 2 | 상태 훅 | 메시지 상태를 `useRoomMessages(userId, roomId)`로 모은다 | P7에서 수신 방식만 바꾼다 |
| 3 | 말풍선 | 내 메시지는 오른쪽·강조색, 남의 메시지는 왼쪽. 같은 사람이 연속으로 보내면 이름을 한 번만 보인다. 내 메시지에는 이름을 보이지 않는다 | 일반 채팅 앱의 관례 |
| 4 | 시간 표시 | 메시지마다 `오후 3:05` 형식(브라우저 시간대, `ko-KR`). 날짜가 바뀌면 `2026년 10월 7일 수요일` 구분선 | `createdAt`(UTC ISO)을 쓰고 있지 않았다 |
| 5 | 스크롤 | 맨 아래(80px 이내)를 보고 있을 때만 새 메시지에 따라 내려간다. 위로 올려 읽는 중이면 "새 메시지 N개" 버튼을 보이고, 누르면 맨 아래로 간다. 내가 보낸 메시지는 항상 맨 아래로 간다. "이전 메시지 더 보기"로 위에 붙이면 늘어난 높이만큼 보정해 보던 메시지를 그대로 둔다 | 읽던 위치를 잃지 않는다 |
| 6 | 이전 메시지 | "이전 메시지 더 보기" 버튼을 대화 목록 맨 위 안쪽에 둔다. 자동 무한 스크롤은 계속 하지 않는다(계획 4 세부 12) | 버튼이 목록 밖에 있어 어색했다 |
| 7 | 입력창 | 여러 줄 `textarea`. Enter = 전송, Shift+Enter = 줄바꿈. 한글 조합 중(`isComposing`) Enter는 전송하지 않는다. 빈 입력이면 보내기 버튼만 끈다. **전송 중 비활성화는 하지 않는다** (ADR-034, F33) | 조합 중 Enter를 처리하지 않으면 마지막 글자가 중복 전송되는 브라우저 동작이 있다 |
| 8 | 폴링 패널 | 대화 화면 오른쪽 위 `<details>` "폴링 상태" 안으로 접어 둔다. 내용과 동작은 그대로 | 관측 도구(ADR-081)는 남기되 평소에는 가린다 |
| 9 | 방 목록 표시 | 방 이름만 보이고 열린 방은 `aria-current="page"`로 강조. `#id`, `마지막 메시지 #id` 문구는 뺀다. 새로고침·더 보기·방 만들기는 그대로, 자동 갱신 없음(ADR-084) | 디버그 문구 제거 |
| 10 | 방 만든 뒤 | 입력창을 비우고 목록을 첫 페이지부터 다시 읽은 뒤 만든 방을 연다 | 사이드바가 항상 보이므로 만든 방이 목록에 없으면 어색하다. 계획 4에서 "그대로 두고 관찰"했던 "방금 만든 방이 목록 맨 아래에 보이는 문제"는 이 새로고침으로 순서가 서버 정렬대로 바뀐다. 관찰 결과는 작업 7에서 기록 |
| 11 | 채팅방 제목 | 사이드바가 읽은 목록에서 방 이름을 찾고, 없으면(링크로 바로 열었는데 목록 첫 페이지에 없음) `방 #id` | 방 하나를 조회하는 API가 없다 |
| 12 | 오류 문구 | 서버의 한국어 메시지만 보인다(코드는 숨김). 네트워크 오류·JSON이 아닌 응답(`UNKNOWN`)은 "서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요." 코드는 개발자 도구의 응답 본문에서 본다(폴링 패널은 `e.message`만 보여 준다) | 사용자에게 코드는 의미가 없다 |
| 13 | 작성자 이름 | **사용자 결정 B (2026-10-07)**: 사용자 조회 API `GET /api/users?ids=1,2,3`(최대 100개)를 추가하고, 화면은 처음 보는 id만 모아 조회해 탭 안에서 기억한다. 받기 전·실패 시에는 `사용자 #id` | 아래 "결정 기록" 참고 |

## 결정 기록: 작성자 이름 (사용자 결정 2026-10-07, B 채택)

**작성자 이름을 어떻게 보일까** (계획 4에서는 "사용자 #id만"으로 정했고 F26 확인을 미뤘다)

| 선택지 | 화면 | 비용과 위험 |
|---|---|---|
| A. `사용자 #id` 유지 | 말풍선 위에 `사용자 #3` | 백엔드 변경 없음. 채팅 앱처럼 보이지 않는다 |
| **B. 사용자 조회 API 추가 (채택)** `GET /api/users?ids=1,2,3` → 화면이 처음 보는 id만 모아 조회하고 탭 안에서 기억 | 말풍선 위에 닉네임 | `user` 패키지 안에서만 바뀐다. `message`가 `user`를 모르는 의존 규칙(ArchUnit)을 지킨다. 닉네임이 생기므로 F26(보이지 않는 서식 문자로 같아 보이는 닉네임)을 화면에서 확인할 수 있게 된다 |
| C. 메시지 응답에 `senderNickname`을 JOIN해서 포함 | 말풍선 위에 닉네임 | 조회가 한 번이지만 `message`가 `users` 테이블을 알아야 해서 의존 규칙과 충돌한다. ADR 변경이 필요하다 |

B를 채택했다. 의존 규칙을 바꾸지 않고, 방을 처음 열 때 한 번 조회하면 이후에는 새로 보이는 사람만 조회한다.

## 백엔드 트랙: JDBC → JPA 전환 (2026-10-07 추가)

**한 줄 요약:** 저장소 4개(`User`, `Room`, `Membership`, `Message`)에 JPA 구현체를 추가하고, 같은 계약 테스트와 같은 부하 조건으로 JDBC 구현과 비교해 나아진 점과 나빠진 점을 수치로 남긴다.

**이미 결정된 것 (그대로 지킨다):** ADR-024(서비스는 저장소 인터페이스에만 의존), ADR-025(구현체 두 개 + `chat.repository` 설정, 공통 계약 테스트, 전환 뒤 성능 비교 F21, **Step 1 실험이 끝난 뒤 진행**), ADR-036(저장소 인터페이스는 `domain/`), ADR-038(도메인은 프레임워크를 모른다. 엔티티 분리 여부는 JPA 전환 때 결정).

### 결정할 사항 (승인 필요, 승인되면 작업 13에서 ADR로 기록)

| # | 항목 | 추천 | 다른 선택지 | 이유 |
|---|---|---|---|---|
| J1 | 교체인가 공존인가 | **공존.** `Jpa...` 구현체를 추가하고 `chat.repository=jpa`로 고른다. 기본값은 비교가 끝날 때까지 `jdbc` | JDBC 구현을 지우고 JPA만 남김 | "나아진 점"을 확인하려면 같은 서버·같은 테스트에서 둘을 번갈아 돌려야 한다. JDBC 삭제는 비교 결과를 보고 따로 정한다 |
| J2 | 시작 시점 | **계획 5b의 공식 측정이 끝난 뒤 작업 8 시작.** 프론트 웨이브는 기다리지 않는다 | 지금 바로 시작 | JPA 의존성을 넣으면 `chat.repository=jdbc`여도 Hibernate가 기동하고 트랜잭션 관리자가 `JpaTransactionManager`로 바뀔 것으로 **예상**한다(작업 8에서 측정). 5b 측정 중에 넣으면 5b 결과의 조건이 바뀐다. ADR-025의 순서와도 맞다 |
| J3 | 엔티티와 도메인 객체 (ADR-038에서 미룬 결정) | **분리.** `infra/jpa/`에 `XxxEntity`를 두고 어댑터가 도메인 record로 바꾼다 | 도메인 객체에 `@Entity`를 붙임 | 도메인 객체가 모두 `record`라 엔티티가 될 수 없다(JPA 엔티티는 기본 생성자와 final이 아닌 필드가 필요). `domain`이 프레임워크를 모른다는 ArchUnit 규칙도 지킨다 |
| J4 | 구현 방식 | **Spring Data JPA** 인터페이스(`SpringDataXxxRepository`) + 도메인 저장소를 구현하는 어댑터(`JpaXxxRepository`). 조건부 UPDATE와 방 목록 정렬은 `@Query`(JPQL) | `EntityManager` 직접 사용 | 실무에서 가장 흔한 방식이고, "코드가 줄어드는가"를 확인하려는 목적에 맞다 |
| J5 | 메시지 스키마·입장 경계 조합 | **계획 5a·5b에서 고른 스키마(A 또는 B)만** JPA로 구현한다. 입장 경계는 `id`·`time` 둘 다. 고르지 않은 스키마로 `jpa`를 켜면 기동에 실패시킨다 | 4개 조합 모두 구현 | B(복합 PK + 자동 증가)는 JPA로 표현하기 어렵다(ADR-024의 이유, 가설 H4). 고른 스키마가 B면 그대로 시도하고 실패를 기록한다 |
| J6 | 스키마 원본 | **Flyway 그대로**, `spring.jpa.hibernate.ddl-auto=validate` | `none` | 엔티티와 테이블이 어긋나면 기동할 때 잡힌다. 이것도 비교 항목이다 |
| J7 | Open Session In View | **끈다** (`spring.jpa.open-in-view=false`) | 기본값(켬) | 켜 두면 요청이 끝날 때까지 DB 연결을 잡아 Hikari 대기(5b C2에서 측정한 지표)가 JDBC와 다른 조건이 된다 |

### "나아진 점" 확인 방법 (작업 13에서 측정, 결과는 `docs/reports/`에 둔다)

나아진 점만 찾지 않고 **나빠진 점도 같은 표에 적는다.** 아래 "예상"은 측정 전 추측이다.

| 관점 | 지표 | 방법 | 예상 |
|---|---|---|---|
| 코드량 | `infra` 저장소 코드 줄 수, 직접 쓴 SQL 문자열 수, 행 → 객체 변환 코드 수 | 작업 8에서 JDBC 기준선을 재고, 작업 13에서 JPA를 같은 방법으로 잰다 | 단순 저장·조회는 줄고, 커서 조회·조건부 UPDATE는 비슷하거나 늘어난다 |
| 스키마 정합 | 엔티티와 테이블이 어긋날 때 언제 알게 되는가 | `ddl-auto=validate` 기동 결과 | JDBC는 실행할 때, JPA는 기동할 때 |
| 정확성 | 같은 계약 테스트·API 테스트 통과 여부 | 두 DB × 두 구현 | 가설 H1~H6 중 일부가 실패한다 |
| 실행 SQL 수 | 입장·전송·최신 조회·방 목록 요청 하나당 실행된 SQL 수 | `@Tag("experiment")` 테스트에서 `DataSource`를 감싸 `prepareStatement` 호출을 센다(두 구현에 같은 방법) | JPA가 같거나 많다(H1의 SELECT 후 INSERT 등) |
| 성능 (F21) | W1~W5 p99·TPS·오류율 | 계획 5b의 k6 스크립트와 같은 조건, `chat.repository`만 바꿈 | JPA가 같거나 조금 느리다 |
| 기동 | 기동 시간, 힙 사용량 | 기동 로그, `/actuator/prometheus`의 `jvm_memory_used_bytes` | JPA가 느리고 크다 |

### 예상되는 문제 (가설, 작업 8에서 `failure-lab.md`에 다음 빈 F 번호로 기록. 미리 고치지 않는다)

| # | 가설 | 어디서 드러날지 |
|---|---|---|
| H1 | `Membership`처럼 id를 직접 넣는 엔티티를 `save()`하면, Spring Data가 새 엔티티가 아니라고 보고 `merge`(SELECT 후 UPDATE)를 한다. 이미 멤버여도 중복 키 오류가 나지 않아 `ALREADY_MEMBER`(R2, ADR-019)가 사라지고 입장 경계가 덮어써질 수 있다 | `MembershipRepositoryContract`의 중복 입장 테스트 |
| H2 | 쓰기 지연 때문에 제약 위반(중복 키, FK)이 저장소 메서드가 아니라 **커밋 시점**에 터진다. 서비스 트랜잭션 안에서는 어댑터의 `try/catch`가 잡지 못해 `ChatException`이 아닌 500이 된다 | 저장소 계약 테스트는 통과하고 **API 테스트**에서만 실패 |
| H3 | `IDENTITY` 전략은 `persist` 즉시 INSERT하므로 쓰기 지연·배치 저장 이점이 없다. 대신 엔티티 생성·변경 감지 비용만 늘어난다 | 성능(W1), 실행 SQL 수 |
| H4 | 스키마 B의 복합 PK `(room_id, id)` 중 `id`만 자동 증가시키는 매핑을 Hibernate가 지원하지 않는다 | 스키마 B를 고른 경우 기동 또는 저장 |
| H5 | 조건부 UPDATE(ADR-016)를 엔티티를 읽고 값을 바꾸는 방식(변경 감지)으로 하면 동시 전송에서 작은 번호가 큰 번호를 덮어쓴다. 이 계획은 JPQL 벌크 UPDATE로 SQL 의미를 유지하므로 재현되지 않을 것으로 예상한다. 대신 벌크 UPDATE가 영속성 컨텍스트에 이미 올라온 `RoomEntity`와 어긋날 수 있다 | `RoomRepositoryContract`, F20 측정 |
| H6 | JPA 경로의 예외 변환은 JDBC와 다를 수 있다. 중복 키가 `DuplicateKeyException`이 아니라 일반 `DataIntegrityViolationException`으로 오면, JDBC 구현에서 옮긴 `catch` 순서 때문에 "이미 멤버"가 "인증 실패(401)"로 보인다 | `MembershipRepositoryContract`의 중복 입장 테스트(H1과 함께 확인) |

## 파일 구조
```
frontend/src/
 ├─ messages/
 │   ├─ useRoomMessages.ts        (새) 최초 조회·폴링·전송·이전 메시지·입장·나가기 상태
 │   ├─ useRoomMessages.test.ts   (새)
 │   ├─ chatItems.ts              (새) 메시지 → 날짜 구분선·말풍선 항목 (순수 함수)
 │   ├─ chatItems.test.ts         (새)
 │   ├─ useChatScroll.ts          (새) 맨 아래 따라가기, 새 메시지 수, 위에 붙일 때 위치 보정
 │   └─ useChatScroll.test.tsx    (새)
 ├─ components/
 │   ├─ MessageList.tsx(+test)    (새) 대화 목록, 이전 메시지 버튼, "새 메시지 N개"
 │   ├─ Composer.tsx(+test)       (새) textarea 입력창
 │   └─ PollingPanel.tsx          (그대로)
 ├─ pages/
 │   ├─ ChatRoomPage.tsx(+test)   (수정) 위 조각을 조립
 │   └─ RoomListPage.tsx(+test)   (수정) 사이드바로
 ├─ api/client.ts(+test)          (수정) errorMessage 문구
 ├─ users/useNicknames.ts(+test)  (새) 작성자 닉네임 조회·기억
 ├─ App.tsx(+test)                (수정) 사이드바 + 대화 레이아웃
 └─ styles.css                    (다시 씀)
backend/
 ├─ user/{api/UserController, application/UserService, domain/UserRepository, infra/jdbc/JdbcUserRepository}
 └─ (백엔드 트랙) {user,room,message}/infra/jpa/   (새) XxxEntity, SpringDataXxxRepository, JpaXxxRepository
    테스트: {user,room,message}/infra/XxxRepositoryContract (Jdbc...Contract를 옮겨 이름 변경, 인터페이스로 주입)
            {user,room,message}/infra/jpa/{MySql,Postgres}Jpa...Test (새), experiment/SqlCountExperiment (새)
```

## 실행 순서와 병렬화

**의존 관계** (선행 작업이 끝나야 시작할 수 있다)

| 작업 | 선행 | 고치거나 만드는 파일 | 이유 |
|---|---|---|---|
| 1 `useRoomMessages` | 없음 | `messages/useRoomMessages.*`, `pages/ChatRoomPage.tsx` | |
| 2 `chatItems` | 없음 | `messages/chatItems.*` | |
| 3 `useChatScroll` | 없음 | `messages/useChatScroll.*` | 기존 `merge.ts`의 `lastId`만 쓴다 |
| 4 `MessageList`·`Composer` | (2의 타입) | `components/MessageList.*`, `components/Composer.*` | `ChatItem`을 `import type`으로만 쓴다. 타입은 이 계획서에 고정돼 있어 동시에 진행하고, 타입 검사는 웨이브 끝에 한다 |
| 6A 사용자 조회 API (백엔드) | 없음 | `backend/.../user/**` | 프론트는 목(mock)으로 테스트하므로 API가 없어도 된다 |
| 6B `styles.css` | 없음 | `styles.css` | 클래스 이름이 이 계획서에 고정돼 있다. 테스트는 CSS를 보지 않는다 |
| 5 채팅방 조립·오류 문구 | 1, 2, 3, 4 | `pages/ChatRoomPage.*`, `api/client.*` | 작업 1과 같은 `ChatRoomPage.tsx`를 고친다 |
| 6C 사이드바·닉네임 | 4, 5 | `App.*`, `pages/RoomListPage.*`, `users/useNicknames.*`, `api/chat.ts`, `pages/ChatRoomPage.tsx` | `title` prop(5), `errorMessage` 문구(5, 401 테스트), `users/senderName.ts`의 `defaultSenderName`(4)을 쓴다 |
| 7 E2E·브라우저·기록 | 1~6C | `e2e/`, `docs/` | 백엔드를 띄워야 하므로 백엔드 작업과 겹치지 않게 한다 |
| 8 JPA 기반·기준선 | 6A, **계획 5b 공식 측정 완료** | `build.gradle.kts`, `ChatProperties`, `application.yml`, `Jdbc*Repository`(조건 어노테이션만), 저장소 계약 테스트 이동, `failure-lab.md`, `docs/reports/` 기준선 | J2. 6A가 고치는 `JdbcUserRepository`·`UserRepository`를 다시 고친다 |
| 9 `User` JPA | 8 | `user/infra/jpa/**` | |
| 10 `Room` JPA | 9 | `room/infra/jpa/*Room*` | |
| 11 `Membership` JPA | 10 | `room/infra/jpa/*Membership*` | |
| 12 `Message` JPA | 11 | `message/infra/jpa/**` | |
| 13 비교 측정·기록 | 7, 12 | `experiment/SqlCountExperiment`, API 테스트 jpa 하위 클래스, `infra/compose.bench.yml`, `docs/` | k6 측정은 다른 작업과 동시에 돌리면 CPU를 나눠 쓰므로 혼자 한다 |

**웨이브**

| 웨이브 | 동시에 하는 작업 | 끝난 뒤 통합 확인 |
|---|---|---|
| 1 | 1, 2, 3, 4, 6A, 6B | `cd frontend && npx vitest run && npx tsc -b && npm run lint`, `cd backend && ./gradlew test --tests 'jissuo.chat.user.*' --tests 'jissuo.chat.ArchitectureTest'` |
| 2 | 5, 8 | 작업 5 Step 5, 작업 8 마지막 확인 |
| 3 | 6C, 9 | 작업 6C 마지막 Step, 작업 9 마지막 확인 |
| 4 | 7 | 작업 7 Step 5 |
| 5 | 10 | 작업 10 마지막 확인 |
| 6 | 11 | 작업 11 마지막 확인 |
| 7 | 12 | 작업 12 마지막 확인 |
| 8 | 13 | 작업 13 마지막 확인 |

계획 5b가 늦게 끝나면 작업 8부터 백엔드 트랙만 뒤로 밀린다. 작업 7도 E2E와 문서(`failure-lab.md`, ADR, `README.md`, `CLAUDE.md`)를 다루므로 5b가 끝난 뒤 한다. 프론트 웨이브(5, 6C, 7)는 기다리지 않고 진행한다. 이때 웨이브 4 이후의 순서는 "8 → 9 → 10 → 11 → 12 → 13"이 된다.

**동시에 진행할 때의 규칙**
- 같은 작업 트리에서 진행한다. 커밋하지 않으므로 worktree를 나누면 합칠 방법이 없고, 작업마다 고치는 파일이 겹치지 않는다(위 표). **표에 없는 파일은 고치지 않는다.** 고쳐야 하면 멈추고 보고한다.
- 작업 중 확인은 **자기 파일만** 한다: `npx vitest run <자기 테스트 파일>`, `npx eslint <자기 파일>`. 다른 작업의 테스트는 TDD로 일부러 실패 중일 수 있으므로 전체 실행(`npx vitest run`, `npm run lint`, `npx tsc -b`)은 웨이브 끝의 통합 확인에서 한 번만 한다.
- 각 작업은 끝나면 결과를 보고하고 멈춘다. 웨이브의 모든 작업이 끝나면 통합 확인을 하고 웨이브 결과를 한 번에 보고한 뒤 다음 웨이브 승인을 기다린다.
- **백엔드 작업은 한 번에 하나만 한다.** vitest는 파일 단위로 돌지만 Gradle은 `src/main`·`src/test` 전체를 컴파일한다. 다른 백엔드 작업이 고치는 중인 파일 하나가 컴파일되지 않으면 모든 백엔드 테스트가 실패하고, 같은 `build/` 디렉터리를 두 Gradle 실행이 함께 쓴다. 그래서 백엔드 트랙(8~13)은 순서대로 하고, 프론트 작업과만 동시에 진행한다.
- **계획 5b와 함께 진행할 때 (2026-10-07, 5b 세션과 합의)**
  - 5b 벤치 앱은 SHA-256이 같은 JAR 복사본으로 고정돼 있다. 그래서 이 계획에서 백엔드 소스를 고쳐도 5b의 DB 비교 조건은 바뀌지 않는다.
  - **5b 측정 중에는 CPU를 많이 쓰는 실행을 미룬다**: Gradle 실행(`bootJar`, `build`, `test`, Testcontainers 테스트, `bootRun`), E2E(`npm run e2e`), `vite build`. 측정 중인지는 `docker ps`에 `chat-bench-` 컨테이너가 떠 있는지로 판단하고, 애매하면 사용자에게 묻는다.
  - 측정 중에도 해도 되는 것: 코드 작성, 짧은 프론트 확인(`npx vitest run <파일>`, `npx eslint <파일>`, `npx tsc -b`).
  - 작업 6A는 측정 중이면 **코드 작성까지만** 하고, Step 2(실패 확인)와 Step 4(통과 확인)는 벤치 컨테이너가 없을 때 실행한다. 이 경우 6A는 "확인 대기"로 보고하고, 웨이브 1 통합 확인의 백엔드 부분도 그때 함께 한다. 작업 6B의 `vite build` 확인도 같다.
  - `load/`, `infra/compose.bench.yml`, 5b 보고서, 5b가 쓰는 ADR·`failure-lab.md`·일지·`README.md`·`CLAUDE.md`는 5b가 끝날 때까지 **5b 세션만 고친다.** 이 계획에서 이 파일들을 고치는 작업(7, 8, 13)은 5b가 끝난 뒤 한다.
  - 작업 8~13(JPA·성능 비교)은 5b 공식 측정이 끝난 뒤 시작한다(J2).
- 6B를 먼저 적용하면 웨이브 1~2 동안 기존 화면의 모양이 어긋날 수 있다. 화면 확인은 작업 7에서만 하므로 문제 삼지 않는다.

---

### 작업 1: 메시지 상태를 `useRoomMessages` 훅으로 옮기기 (화면 변화 없음)

> 웨이브 1 · 선행 없음 · 2, 3, 4, 6A, 6B와 동시 진행

**Files:**
- Create: `frontend/src/messages/useRoomMessages.ts`, `frontend/src/messages/useRoomMessages.test.ts`
- Modify: `frontend/src/pages/ChatRoomPage.tsx`

**Interfaces:**
- Produces:
```ts
export type RoomStatus = 'loading' | 'notMember' | 'ready' | 'error'
export type PollingControls = {
  stats: PollStats; cursor: number; intervalMs: number; paused: boolean
  setIntervalMs: (ms: number) => void; togglePause: () => void
}
export type RoomMessages = {
  status: RoomStatus
  messages: Message[]
  hasOlder: boolean
  error: string | null
  join: () => Promise<void>
  send: (content: string) => Promise<boolean>   // 성공하면 true (입력창을 비울지 판단)
  loadOlder: () => Promise<void>
  leave: () => Promise<boolean>                 // 성공하면 true (목록으로 갈지 판단)
  polling: PollingControls
}
export function useRoomMessages(userId: number, roomId: number): RoomMessages
```

- [ ] **Step 1: 실패하는 훅 테스트 작성** — `useRoomMessages.test.ts`

```ts
import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as chat from '../api/chat'
import { ApiError } from '../api/client'
import type { Message } from '../api/types'
import { useRoomMessages } from './useRoomMessages'

vi.mock('../api/chat')

const info = { status: 200, requestId: 'r', durationMs: 1 }
const msg = (id: number, senderId = 2): Message => ({ id, roomId: 1, senderId, content: `m${id}`, createdAt: '2026-10-07T00:00:00Z' })
const page = (messages: Message[], hasMore = false) => ({ data: { messages, hasMore }, info })

describe('useRoomMessages', () => {
  beforeEach(() => vi.resetAllMocks())

  it('최신 메시지를 읽고 커서를 마지막 id로 둔다', async () => {
    vi.mocked(chat.readMessages).mockResolvedValue(page([msg(3), msg(4)], true))
    const { result } = renderHook(() => useRoomMessages(1, 1))
    await waitFor(() => expect(result.current.status).toBe('ready'))
    expect(result.current.messages.map((m) => m.id)).toEqual([3, 4])
    expect(result.current.hasOlder).toBe(true)
    expect(result.current.polling.cursor).toBe(4)
  })

  it('403 NOT_A_MEMBER면 notMember', async () => {
    vi.mocked(chat.readMessages).mockRejectedValue(new ApiError(403, 'NOT_A_MEMBER', '멤버가 아닙니다.', { ...info, status: 403 }))
    const { result } = renderHook(() => useRoomMessages(1, 1))
    await waitFor(() => expect(result.current.status).toBe('notMember'))
  })

  it('send는 성공하면 메시지를 합치고 true, 실패하면 오류를 남기고 false', async () => {
    vi.mocked(chat.readMessages).mockResolvedValue(page([msg(1)]))
    vi.mocked(chat.sendMessage)
      .mockResolvedValueOnce({ data: msg(2, 1), info: { ...info, status: 201 } })
      .mockRejectedValueOnce(new ApiError(400, 'INVALID_REQUEST', '요청 값이 올바르지 않습니다.', { ...info, status: 400 }))
    const { result } = renderHook(() => useRoomMessages(1, 1))
    await waitFor(() => expect(result.current.status).toBe('ready'))

    let ok = false
    await act(async () => { ok = await result.current.send('안녕') })
    expect(ok).toBe(true)
    expect(result.current.messages.map((m) => m.id)).toEqual([1, 2])
    // ADR-083: 내 메시지 id로 커서를 옮기지 않는다
    expect(result.current.polling.cursor).toBe(1)

    await act(async () => { ok = await result.current.send('') })
    expect(ok).toBe(false)
    expect(result.current.error).not.toBeNull()
  })

  it('leave는 성공하면 true', async () => {
    vi.mocked(chat.readMessages).mockResolvedValue(page([]))
    vi.mocked(chat.leaveRoom).mockResolvedValue({ data: null, info })
    const { result } = renderHook(() => useRoomMessages(1, 1))
    await waitFor(() => expect(result.current.status).toBe('ready'))
    let ok = false
    await act(async () => { ok = await result.current.leave() })
    expect(ok).toBe(true)
    expect(chat.leaveRoom).toHaveBeenCalledWith(1, 1)
  })
})
```

- [ ] **Step 2: 실패 확인** — `cd frontend && npx vitest run src/messages/useRoomMessages.test.ts` → 모듈이 없어 FAIL

- [ ] **Step 3: 훅 구현** — `ChatRoomPage.tsx`의 상태와 함수를 그대로 옮긴다(주석 포함). 차이는 `send`/`leave`가 boolean을 돌려주고 `draft`를 모른다는 것뿐이다.

```ts
import { useCallback, useEffect, useRef, useState } from 'react'
import { joinRoom, leaveRoom, readMessages, sendMessage } from '../api/chat'
import { ApiError, errorMessage } from '../api/client'
import type { Message } from '../api/types'
import { lastId, mergeMessages } from './merge'
import { DEFAULT_POLL_INTERVAL_MS, usePolling } from './usePolling'
import type { PollStats } from './usePolling'

export type RoomStatus = 'loading' | 'notMember' | 'ready' | 'error'
export type PollingControls = {
  stats: PollStats; cursor: number; intervalMs: number; paused: boolean
  setIntervalMs: (ms: number) => void; togglePause: () => void
}
export type RoomMessages = {
  status: RoomStatus
  messages: Message[]
  hasOlder: boolean
  error: string | null
  join: () => Promise<void>
  send: (content: string) => Promise<boolean>
  loadOlder: () => Promise<void>
  leave: () => Promise<boolean>
  polling: PollingControls
}

// 계획 6 세부 #2: P7에서 수신 방식(폴링 → WebSocket)만 바꾸도록 화면과 분리한다
export function useRoomMessages(userId: number, roomId: number): RoomMessages {
  const [status, setStatus] = useState<RoomStatus>('loading')
  const [messages, setMessages] = useState<Message[]>([])
  const [hasOlder, setHasOlder] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // ADR-083: 커서는 조회 응답으로만 전진한다. 내 메시지 id로 옮기면
  // 이미 커밋됐지만 아직 받지 못한 더 작은 id의 남의 메시지를 영원히 건너뛴다
  const cursorRef = useRef(0)
  const [cursorView, setCursorView] = useState(0)
  const [intervalMs, setIntervalMs] = useState(DEFAULT_POLL_INTERVAL_MS)
  const [paused, setPaused] = useState(false)
  const latestRequestRef = useRef(0)

  const loadLatest = useCallback(() => {
    const requestId = ++latestRequestRef.current
    return readMessages(userId, roomId)
      .then(({ data }) => {
        // 앞선 최초 조회가 늦게 끝나도 최신 메시지와 커서를 되돌리지 않는다
        if (requestId !== latestRequestRef.current) return
        cursorRef.current = lastId(data.messages)
        setCursorView(cursorRef.current)
        setMessages(data.messages)
        setHasOlder(data.hasMore)
        setStatus('ready')
        setError(null)
      })
      .catch((e: unknown) => {
        if (requestId !== latestRequestRef.current) return
        // ADR-084: 멤버 여부 API가 없으므로 서버의 인가 결과로 판단한다
        if (e instanceof ApiError && e.code === 'NOT_A_MEMBER') {
          setStatus('notMember')
          return
        }
        setStatus('error')
        setError(errorMessage(e))
      })
  }, [userId, roomId])

  useEffect(() => {
    const requestRef = latestRequestRef
    void loadLatest()
    return () => { requestRef.current++ }
  }, [loadLatest])

  const poll = useCallback(async (isCurrent: () => boolean) => {
    const { data, info } = await readMessages(userId, roomId, { after: cursorRef.current })
    if (!isCurrent()) return { hasMore: false, info, count: 0 }
    if (data.messages.length > 0) {
      cursorRef.current = lastId(data.messages)
      setCursorView(cursorRef.current)
      setMessages((current) => mergeMessages(current, data.messages))
    }
    return { hasMore: data.hasMore, info, count: data.messages.length }
  }, [userId, roomId])

  const stats = usePolling({ enabled: status === 'ready' && !paused, intervalMs, poll })

  async function join() {
    try {
      await joinRoom(userId, roomId)
    } catch (e) {
      if (!(e instanceof ApiError && e.code === 'ALREADY_MEMBER')) {
        setError(errorMessage(e))
        return
      }
    }
    await loadLatest()
  }

  async function send(content: string) {
    try {
      const { data } = await sendMessage(userId, roomId, content)
      setMessages((current) => mergeMessages(current, [data]))
      setError(null)
      return true
    } catch (e) {
      setError(errorMessage(e))
      return false
    }
  }

  async function loadOlder() {
    if (messages.length === 0) return
    try {
      const { data } = await readMessages(userId, roomId, { before: messages[0].id })
      setMessages((current) => mergeMessages(current, data.messages))
      setHasOlder(data.hasMore)
    } catch (e) {
      setError(errorMessage(e))
    }
  }

  async function leave() {
    try {
      await leaveRoom(userId, roomId)
      return true
    } catch (e) {
      setError(errorMessage(e))
      return false
    }
  }

  return {
    status, messages, hasOlder, error, join, send, loadOlder, leave,
    polling: {
      stats, cursor: cursorView, intervalMs, paused, setIntervalMs,
      togglePause: () => setPaused((current) => !current),
    },
  }
}
```

- [ ] **Step 4: `ChatRoomPage.tsx`가 훅을 쓰도록 바꾸기** — 상태·함수 선언(14~119행)을 지우고 아래처럼 바꾼다. JSX는 그대로 두되 이름만 바꾼다(`messages` → `room.messages`, `status` → `room.status` 등). 스크롤 effect(`newestId`)와 `draft`는 이 작업에서는 페이지에 남긴다.

```tsx
const room = useRoomMessages(userId, roomId)
const [draft, setDraft] = useState('')
const listRef = useRef<HTMLOListElement>(null)
const newestId = lastId(room.messages)
useEffect(() => {
  const list = listRef.current
  if (list) list.scrollTop = list.scrollHeight
}, [newestId])

async function send(event: FormEvent) {
  event.preventDefault()
  if (await room.send(draft)) setDraft('')
}
async function leave() {
  if (await room.leave()) onBack()
}
// JSX: <PollingPanel stats={room.polling.stats} cursor={room.polling.cursor} intervalMs={room.polling.intervalMs}
//        paused={room.polling.paused} onIntervalChange={room.polling.setIntervalMs} onTogglePause={room.polling.togglePause} />
```

- [ ] **Step 5: 자기 파일 확인** — `npx vitest run src/messages/useRoomMessages.test.ts src/pages/ChatRoomPage.test.tsx` → 새 훅 테스트와 **기존 `ChatRoomPage.test.tsx` 전부 PASS** (동작이 바뀌지 않았다는 확인). `npx eslint src/messages/useRoomMessages.ts src/messages/useRoomMessages.test.ts src/pages/ChatRoomPage.tsx` 오류 없음. 전체 `vitest`·`tsc -b`·`lint`는 웨이브 1 통합 확인에서 한다.

- [ ] **Step 6: 결과 보고 후 멈춤** (커밋하지 않음)

---

### 작업 2: 표시용 항목 만들기 (`chatItems.ts`)

> 웨이브 1 · 선행 없음

**Files:**
- Create: `frontend/src/messages/chatItems.ts`, `frontend/src/messages/chatItems.test.ts`

**Interfaces:**
- Produces:
```ts
export type ChatItem =
  | { kind: 'day'; key: string; label: string }
  | { kind: 'message'; key: string; message: Message; mine: boolean; showSender: boolean; time: string }
export function buildChatItems(messages: Message[], myId: number, timeZone?: string): ChatItem[]
```

- [ ] **Step 1: 실패하는 테스트**

```ts
import { describe, expect, it } from 'vitest'
import type { Message } from '../api/types'
import { buildChatItems } from './chatItems'

const msg = (id: number, senderId: number, createdAt: string): Message => ({ id, roomId: 1, senderId, content: `m${id}`, createdAt })
const SEOUL = 'Asia/Seoul'

describe('buildChatItems', () => {
  it('날짜가 바뀔 때마다 구분선을 넣는다 (시간대 기준)', () => {
    // UTC 14:59 = 서울 23:59, UTC 15:00 = 서울 다음 날 00:00
    const items = buildChatItems([
      msg(1, 2, '2026-10-06T14:59:00Z'),
      msg(2, 2, '2026-10-06T15:00:00Z'),
    ], 1, SEOUL)
    expect(items.map((i) => i.kind)).toEqual(['day', 'message', 'day', 'message'])
    expect(items[0]).toMatchObject({ kind: 'day', label: expect.stringContaining('10월 6일') })
    expect(items[2]).toMatchObject({ kind: 'day', label: expect.stringContaining('10월 7일') })
  })

  it('내 메시지를 표시하고, 남의 연속 메시지는 이름을 첫 번째에만 보인다', () => {
    const at = '2026-10-07T03:05:00Z'
    const items = buildChatItems([msg(1, 2, at), msg(2, 2, at), msg(3, 1, at), msg(4, 2, at)], 1, SEOUL)
      .filter((i) => i.kind === 'message')
    expect(items.map((i) => [i.message.id, i.mine, i.showSender])).toEqual([
      [1, false, true], [2, false, false], [3, true, false], [4, false, true],
    ])
  })

  it('시간은 시:분으로 보인다', () => {
    const [, item] = buildChatItems([msg(1, 2, '2026-10-07T06:05:00Z')], 1, SEOUL)
    // 서울 15:05. ICU 버전에 따라 "오후 3:05" 앞뒤 공백이 다를 수 있어 숫자만 확인한다
    expect(item).toMatchObject({ kind: 'message', time: expect.stringMatching(/3:05/) })
  })
})
```

- [ ] **Step 2: 실패 확인** — `npx vitest run src/messages/chatItems.test.ts` → FAIL (모듈 없음)

- [ ] **Step 3: 구현**

```ts
import type { Message } from '../api/types'

export type ChatItem =
  | { kind: 'day'; key: string; label: string }
  | { kind: 'message'; key: string; message: Message; mine: boolean; showSender: boolean; time: string }

// 계획 6 세부 #3, #4. timeZone은 테스트가 결과를 고정하려고 넘긴다. 화면은 브라우저 시간대를 쓴다
export function buildChatItems(messages: Message[], myId: number, timeZone?: string): ChatItem[] {
  const dayKey = new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' })
  const dayLabel = new Intl.DateTimeFormat('ko-KR', { timeZone, year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' })
  const timeLabel = new Intl.DateTimeFormat('ko-KR', { timeZone, hour: 'numeric', minute: '2-digit' })

  const items: ChatItem[] = []
  let previousDay: string | null = null
  let previousSender: number | null = null
  for (const message of messages) {
    const at = new Date(message.createdAt)
    const day = dayKey.format(at)
    if (day !== previousDay) {
      items.push({ kind: 'day', key: `day-${day}`, label: dayLabel.format(at) })
      previousDay = day
      previousSender = null
    }
    const mine = message.senderId === myId
    items.push({
      kind: 'message',
      key: `m-${message.id}`,
      message,
      mine,
      showSender: !mine && message.senderId !== previousSender,
      time: timeLabel.format(at),
    })
    previousSender = message.senderId
  }
  return items
}
```

- [ ] **Step 4: 통과 확인** — `npx vitest run src/messages/chatItems.test.ts` → PASS. `npx eslint src/messages/chatItems.ts src/messages/chatItems.test.ts` 오류 없음

- [ ] **Step 5: 결과 보고 후 멈춤**

---

### 작업 3: 스크롤 훅 (`useChatScroll`)

> 웨이브 1 · 선행 없음

**Files:**
- Create: `frontend/src/messages/useChatScroll.ts`, `frontend/src/messages/useChatScroll.test.tsx`

**Interfaces:**
- Produces:
```ts
export const BOTTOM_THRESHOLD_PX = 80
export function useChatScroll(
  listRef: RefObject<HTMLElement | null>, messages: Message[], myId: number,
): { unseen: number; onScroll: () => void; scrollToBottom: () => void }
```

설계: 화면 높이를 다루는 부분(`scrollTop` 대입)은 `useLayoutEffect`에서 DOM만 바꾸고, 상태(`atBottom`, `seenId`)는 스크롤 이벤트와 버튼 클릭에서만 바꾼다. 효과 안에서 setState를 하지 않아 렌더가 연쇄되지 않는다. `unseen`은 "맨 아래가 아닐 때, 마지막으로 본 id보다 큰 남의 메시지 수"로 렌더 중에 계산한다.

- [ ] **Step 1: 실패하는 테스트** — jsdom은 레이아웃을 계산하지 않으므로 높이를 직접 정의한다(항목 하나 = 20px, 보이는 높이 100px).

```tsx
import { fireEvent, render, screen } from '@testing-library/react'
import { useRef } from 'react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { Message } from '../api/types'
import { useChatScroll } from './useChatScroll'

const msg = (id: number, senderId = 2): Message => ({ id, roomId: 1, senderId, content: `m${id}`, createdAt: '2026-10-07T00:00:00Z' })
const ids = (from: number, to: number, senderId = 2) => Array.from({ length: to - from + 1 }, (_, i) => msg(from + i, senderId))

function Harness({ messages }: { messages: Message[] }) {
  const ref = useRef<HTMLOListElement>(null)
  const { unseen, onScroll, scrollToBottom } = useChatScroll(ref, messages, 1)
  return (
    <>
      <ol data-testid="list" ref={ref} onScroll={onScroll}>{messages.map((m) => <li key={m.id}>{m.content}</li>)}</ol>
      <output>{unseen}</output>
      <button onClick={scrollToBottom}>아래로</button>
    </>
  )
}

const scrollTops = new WeakMap<Element, number>()
const descriptors = {
  scrollHeight: Object.getOwnPropertyDescriptor(Element.prototype, 'scrollHeight'),
  clientHeight: Object.getOwnPropertyDescriptor(Element.prototype, 'clientHeight'),
  scrollTop: Object.getOwnPropertyDescriptor(Element.prototype, 'scrollTop'),
}

beforeEach(() => {
  Object.defineProperty(Element.prototype, 'scrollHeight', { configurable: true, get() { return this.childElementCount * 20 } })
  Object.defineProperty(Element.prototype, 'clientHeight', { configurable: true, get() { return 100 } })
  Object.defineProperty(Element.prototype, 'scrollTop', {
    configurable: true,
    get() { return scrollTops.get(this) ?? 0 },
    set(value: number) { scrollTops.set(this, Math.max(0, Math.min(value, this.scrollHeight - 100))) },
  })
})
afterEach(() => {
  for (const [key, descriptor] of Object.entries(descriptors)) {
    if (descriptor) Object.defineProperty(Element.prototype, key, descriptor)
  }
})

describe('useChatScroll', () => {
  it('처음 읽으면 맨 아래로 내린다', () => {
    render(<Harness messages={ids(1, 10)} />)
    expect(screen.getByTestId('list').scrollTop).toBe(100)
  })

  it('맨 아래에 있으면 새 메시지를 따라 내려간다', () => {
    const { rerender } = render(<Harness messages={ids(1, 10)} />)
    rerender(<Harness messages={ids(1, 12)} />)
    expect(screen.getByTestId('list').scrollTop).toBe(140)
    expect(screen.getByRole('status')).toHaveTextContent('0')
  })

  it('위로 올려 읽는 중이면 내려가지 않고 새 메시지 수를 센다. 버튼을 누르면 맨 아래로', () => {
    const { rerender } = render(<Harness messages={ids(1, 10)} />)
    const list = screen.getByTestId('list')
    list.scrollTop = 0
    fireEvent.scroll(list)
    rerender(<Harness messages={ids(1, 12)} />)
    expect(list.scrollTop).toBe(0)
    expect(screen.getByRole('status')).toHaveTextContent('2')

    fireEvent.click(screen.getByRole('button', { name: '아래로' }))
    expect(list.scrollTop).toBe(140)
    expect(screen.getByRole('status')).toHaveTextContent('0')
  })

  it('내가 보낸 메시지는 위에 있어도 맨 아래로 간다', () => {
    const { rerender } = render(<Harness messages={ids(1, 10)} />)
    const list = screen.getByTestId('list')
    list.scrollTop = 0
    fireEvent.scroll(list)
    rerender(<Harness messages={[...ids(1, 10), msg(11, 1)]} />)
    expect(list.scrollTop).toBe(120)
  })

  it('이전 메시지를 위에 붙이면 늘어난 높이만큼 내려서 보던 위치를 지킨다', () => {
    const { rerender } = render(<Harness messages={ids(11, 20)} />)
    const list = screen.getByTestId('list')
    list.scrollTop = 0
    fireEvent.scroll(list)
    rerender(<Harness messages={ids(6, 20)} />)
    expect(list.scrollTop).toBe(100) // 5개 × 20px
  })
})
```

참고: `<output>`의 암묵적 role은 `status`다.

- [ ] **Step 2: 실패 확인** — `npx vitest run src/messages/useChatScroll.test.tsx` → FAIL

- [ ] **Step 3: 구현**

```ts
import { useLayoutEffect, useRef, useState } from 'react'
import type { RefObject } from 'react'
import type { Message } from '../api/types'
import { lastId } from './merge'

export const BOTTOM_THRESHOLD_PX = 80

// 계획 6 세부 #5: 위로 올려 읽는 중에는 끌어내리지 않고, 위에 붙일 때는 보던 메시지를 그대로 둔다
export function useChatScroll(listRef: RefObject<HTMLElement | null>, messages: Message[], myId: number) {
  const [atBottom, setAtBottom] = useState(true)
  const [seenId, setSeenId] = useState(0)
  const previous = useRef({ oldest: 0, newest: 0, height: 0 })
  const newest = lastId(messages)

  useLayoutEffect(() => {
    const list = listRef.current
    if (!list) return
    const before = previous.current
    const oldest = messages.length === 0 ? 0 : messages[0].id
    const latest = lastId(messages)
    if (before.newest !== 0 && oldest < before.oldest && latest === before.newest) {
      list.scrollTop += list.scrollHeight - before.height
    } else if (latest > before.newest) {
      const mineArrived = messages.some((m) => m.id > before.newest && m.senderId === myId)
      if (before.newest === 0 || atBottom || mineArrived) list.scrollTop = list.scrollHeight
    }
    previous.current = { oldest, newest: latest, height: list.scrollHeight }
  }, [listRef, messages, myId, atBottom])

  function onScroll() {
    const list = listRef.current
    if (!list) return
    const nowAtBottom = list.scrollHeight - list.scrollTop - list.clientHeight <= BOTTOM_THRESHOLD_PX
    // 맨 아래에 있던 동안 보인 메시지는 읽은 것으로 본다 (맨 아래를 떠나는 순간 포함)
    if (nowAtBottom || atBottom) setSeenId(newest)
    setAtBottom(nowAtBottom)
  }

  function scrollToBottom() {
    const list = listRef.current
    if (list) list.scrollTop = list.scrollHeight
    setAtBottom(true)
    setSeenId(newest)
  }

  const unseen = atBottom ? 0 : messages.filter((m) => m.id > seenId && m.senderId !== myId).length
  return { unseen, onScroll, scrollToBottom }
}
```

- [ ] **Step 4: 통과 확인** — `npx vitest run src/messages/useChatScroll.test.tsx` → PASS. `npx eslint src/messages/useChatScroll.ts src/messages/useChatScroll.test.tsx` 경고·오류 없음 (react-hooks 규칙이 효과 안 setState를 지적하지 않는지 확인)

- [ ] **Step 5: 결과 보고 후 멈춤**

---

### 작업 4: 대화 목록과 입력창 컴포넌트

> 웨이브 1 · 작업 2와 동시 진행 (`ChatItem` 타입만 쓴다)

**Files:**
- Create: `frontend/src/components/MessageList.tsx`, `MessageList.test.tsx`, `Composer.tsx`, `Composer.test.tsx`, `frontend/src/users/senderName.ts`

**Interfaces:**
- Consumes: `ChatItem`(작업 2). `import type`으로만 쓰므로 `chatItems.ts`가 아직 없어도 vitest는 돈다(타입 import는 빌드에서 지워짐). 타입 검사는 웨이브 1 통합 확인에서 한다
- Produces:
```ts
type MessageListProps = {
  items: ChatItem[]
  senderName: (senderId: number) => string
  listRef: RefObject<HTMLOListElement | null>
  onScroll: () => void
  hasOlder: boolean
  onLoadOlder: () => void
  unseen: number
  onJumpToBottom: () => void
}
export function MessageList(props: MessageListProps): JSX.Element
export function Composer(props: { onSend: (content: string) => Promise<boolean> }): JSX.Element
```
`defaultSenderName`은 컴포넌트 파일이 아닌 `frontend/src/users/senderName.ts`에 둔다(웨이브 1에서 결정: 컴포넌트 파일이 함수를 함께 내보내면 `react-refresh/only-export-components` 오류).
```ts
export const defaultSenderName = (senderId: number) => `사용자 #${senderId}`
```

- [ ] **Step 1: 실패하는 테스트** — `MessageList.test.tsx`

```tsx
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createRef } from 'react'
import { describe, expect, it, vi } from 'vitest'
import type { ChatItem } from '../messages/chatItems'
import { defaultSenderName } from '../users/senderName'
import { MessageList } from './MessageList'

const message = (id: number, senderId: number, mine: boolean, showSender: boolean): ChatItem => ({
  kind: 'message', key: `m-${id}`, mine, showSender, time: '오후 3:05',
  message: { id, roomId: 1, senderId, content: `내용${id}`, createdAt: '2026-10-07T06:05:00Z' },
})
const props = {
  senderName: defaultSenderName, listRef: createRef<HTMLOListElement>(), onScroll: vi.fn(),
  hasOlder: false, onLoadOlder: vi.fn(), unseen: 0, onJumpToBottom: vi.fn(),
}

describe('MessageList', () => {
  it('날짜 구분선, 남의 메시지 이름, 내 메시지 표시를 그린다', () => {
    const items: ChatItem[] = [{ kind: 'day', key: 'd', label: '2026년 10월 7일 수요일' }, message(1, 2, false, true), message(2, 1, true, false)]
    render(<MessageList {...props} items={items} />)
    const list = within(screen.getByRole('list', { name: '대화' }))
    expect(list.getByText('2026년 10월 7일 수요일')).toBeInTheDocument()
    expect(list.getByText('사용자 #2')).toBeInTheDocument()
    expect(list.queryByText('사용자 #1')).not.toBeInTheDocument()
    expect(list.getByText('내용2').closest('li')).toHaveClass('mine')
  })

  it('이전 메시지 버튼과 새 메시지 버튼', async () => {
    const onLoadOlder = vi.fn()
    const onJumpToBottom = vi.fn()
    render(<MessageList {...props} items={[]} hasOlder onLoadOlder={onLoadOlder} unseen={3} onJumpToBottom={onJumpToBottom} />)
    await userEvent.click(screen.getByRole('button', { name: '이전 메시지 더 보기' }))
    await userEvent.click(screen.getByRole('button', { name: '새 메시지 3개' }))
    expect(onLoadOlder).toHaveBeenCalled()
    expect(onJumpToBottom).toHaveBeenCalled()
  })
})
```

`Composer.test.tsx`

```tsx
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { Composer } from './Composer'

describe('Composer', () => {
  it('Enter로 보내고 성공하면 비운다', async () => {
    const onSend = vi.fn().mockResolvedValue(true)
    render(<Composer onSend={onSend} />)
    await userEvent.type(screen.getByLabelText('메시지'), '안녕{Enter}')
    expect(onSend).toHaveBeenCalledWith('안녕')
    await waitFor(() => expect(screen.getByLabelText('메시지')).toHaveValue(''))
  })

  it('Shift+Enter는 줄바꿈, 실패하면 입력을 남긴다', async () => {
    const onSend = vi.fn().mockResolvedValue(false)
    render(<Composer onSend={onSend} />)
    await userEvent.type(screen.getByLabelText('메시지'), '첫 줄{Shift>}{Enter}{/Shift}둘째 줄')
    expect(onSend).not.toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: '보내기' }))
    expect(onSend).toHaveBeenCalledWith('첫 줄\n둘째 줄')
    expect(screen.getByLabelText('메시지')).toHaveValue('첫 줄\n둘째 줄')
  })

  it('한글 조합 중 Enter는 보내지 않는다', () => {
    const onSend = vi.fn().mockResolvedValue(true)
    render(<Composer onSend={onSend} />)
    const box = screen.getByLabelText('메시지')
    fireEvent.change(box, { target: { value: '안녕' } })
    fireEvent.keyDown(box, { key: 'Enter', isComposing: true })
    expect(onSend).not.toHaveBeenCalled()
  })

  it('빈 입력이면 보내기 버튼을 끈다. 전송 중에는 끄지 않는다 (ADR-034, F33)', async () => {
    let finish!: (ok: boolean) => void
    const onSend = vi.fn().mockImplementation(() => new Promise<boolean>((resolve) => { finish = resolve }))
    render(<Composer onSend={onSend} />)
    expect(screen.getByRole('button', { name: '보내기' })).toBeDisabled()
    await userEvent.type(screen.getByLabelText('메시지'), '안녕')
    await userEvent.click(screen.getByRole('button', { name: '보내기' }))
    expect(screen.getByRole('button', { name: '보내기' })).toBeEnabled()
    await act(async () => finish(true))
  })
})
```

- [ ] **Step 2: 실패 확인** — `npx vitest run src/components/MessageList.test.tsx src/components/Composer.test.tsx` → FAIL

- [ ] **Step 3: 구현** — `MessageList.tsx`

```tsx
import type { RefObject } from 'react'
import type { ChatItem } from '../messages/chatItems'

type Props = {
  items: ChatItem[]
  senderName: (senderId: number) => string
  listRef: RefObject<HTMLOListElement | null>
  onScroll: () => void
  hasOlder: boolean
  onLoadOlder: () => void
  unseen: number
  onJumpToBottom: () => void
}

export function MessageList({ items, senderName, listRef, onScroll, hasOlder, onLoadOlder, unseen, onJumpToBottom }: Props) {
  return (
    <div className="conversation">
      <ol aria-label="대화" className="messages" ref={listRef} onScroll={onScroll}>
        {/* 계획 6 세부 #6: 버튼을 목록 안 맨 위에 두어 위로 스크롤한 끝에서 바로 누른다 */}
        {hasOlder && (
          <li className="older"><button onClick={onLoadOlder}>이전 메시지 더 보기</button></li>
        )}
        {items.map((item) =>
          item.kind === 'day' ? (
            <li key={item.key} className="day"><span>{item.label}</span></li>
          ) : (
            <li key={item.key} className={item.mine ? 'msg mine' : 'msg'}>
              {item.showSender && <span className="sender">{senderName(item.message.senderId)}</span>}
              <div className="line">
                <p className="bubble">{item.message.content}</p>
                <time dateTime={item.message.createdAt}>{item.time}</time>
              </div>
            </li>
          ),
        )}
      </ol>
      {unseen > 0 && <button className="jump" onClick={onJumpToBottom}>새 메시지 {unseen}개</button>}
    </div>
  )
}
```

`Composer.tsx`

```tsx
import { useState } from 'react'
import type { FormEvent, KeyboardEvent } from 'react'

type Props = { onSend: (content: string) => Promise<boolean> }

export function Composer({ onSend }: Props) {
  const [draft, setDraft] = useState('')

  async function submit() {
    if (await onSend(draft)) setDraft('')
  }

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    void submit()
  }

  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    // 계획 6 세부 #7: 한글 조합 중 Enter를 전송으로 처리하면 마지막 글자가 다시 전송된다
    if (event.key !== 'Enter' || event.shiftKey || event.nativeEvent.isComposing) return
    event.preventDefault()
    if (draft.length > 0) void submit()
  }

  // ADR-085: 길이·문자 검사는 서버 한 곳에서 한다
  // ADR-034, F33: 전송 중에도 버튼을 끄지 않는다 (연속 제출 중복을 화면에서 미리 막지 않음)
  return (
    <form className="composer" onSubmit={onSubmit}>
      <textarea aria-label="메시지" rows={1} placeholder="메시지 입력 (Shift+Enter 줄바꿈)"
        value={draft} onChange={(e) => setDraft(e.target.value)} onKeyDown={onKeyDown} />
      <button type="submit" disabled={draft.length === 0}>보내기</button>
    </form>
  )
}
```

- [ ] **Step 4: 통과 확인** — `npx vitest run src/components` → PASS. `npx eslint src/components` 오류 없음

- [ ] **Step 5: 결과 보고 후 멈춤**

---

### 작업 5: 채팅방 화면 조립, 폴링 패널 접기, 오류 문구

> 웨이브 2 · 선행: 1, 2, 3, 4

**Files:**
- Modify: `frontend/src/pages/ChatRoomPage.tsx`, `ChatRoomPage.test.tsx`, `frontend/src/api/client.ts`, `client.test.ts`

**Interfaces:**
- Consumes: `useRoomMessages`(작업 1), `buildChatItems`(작업 2), `useChatScroll`(작업 3), `MessageList`·`Composer`, `users/senderName.ts`의 `defaultSenderName`(작업 4)
- Produces: `ChatRoomPage` props가 `{ userId: number; roomId: number; title: string; onBack: () => void; senderName?: (id: number) => string }`로 바뀐다(작업 6C에서 App이 넘김). `errorMessage(e)`는 코드 없이 문구만 돌려준다.

- [ ] **Step 1: 테스트 수정(실패 상태로 만들기)**
  - `client.test.ts`의 `errorMessage` 테스트를 바꾼다:
```ts
it('ApiError는 서버 문구만, 연결 실패는 안내 문구 (계획 6 세부 #12)', () => {
  const info = { status: 409, requestId: null, durationMs: 1 }
  expect(errorMessage(new ApiError(409, 'ALREADY_MEMBER', '이미 멤버입니다.', info))).toBe('이미 멤버입니다.')
  expect(errorMessage(new ApiError(502, 'UNKNOWN', 'HTTP 502', { ...info, status: 502 }))).toBe(CONNECTION_ERROR)
  expect(errorMessage(new TypeError('Failed to fetch'))).toBe(CONNECTION_ERROR)
})
```
  (`import { ApiError, CONNECTION_ERROR, apiFetch, errorMessage } from './client'`)
  - `ChatRoomPage.test.tsx`
    - 모든 `render(<ChatRoomPage userId={1} roomId={1} onBack={...} />)`에 `title="잡담"`을 추가한다.
    - "전송이 거절되면" 테스트의 기대를 `toHaveTextContent('멤버가 아닙니다.')`로 바꾼다.
    - ADR-083 테스트의 마지막 `items.map(li => li.textContent)` 비교를 아래로 바꾼다(구분선·시간이 생겼으므로 메시지 항목만 순서 확인):
```ts
const bubbles = within(screen.getByRole('list', { name: '대화' })).getAllByText(/^(m10|남의 메시지|내 메시지)$/)
expect(bubbles.map((el) => el.textContent)).toEqual(['m10', '남의 메시지', '내 메시지'])
```
    - 폴링 패널을 쓰는 두 테스트는 `findByRole('complementary', ...)` 전에 접힌 패널을 연다: `await user.click(await screen.findByText('폴링 상태'))`
    - 새 테스트 추가:
```ts
it('제목을 보이고, 내 메시지는 mine으로 표시한다', async () => {
  vi.mocked(chat.readMessages).mockResolvedValue(page([msg(1, 2, '남'), msg(2, 1, '나')]))
  render(<ChatRoomPage userId={1} roomId={1} title="잡담" onBack={vi.fn()} />)
  expect(await screen.findByRole('heading', { name: '잡담' })).toBeInTheDocument()
  expect(screen.getByText('나').closest('li')).toHaveClass('mine')
  expect(screen.getByText('남').closest('li')).not.toHaveClass('mine')
})
```

- [ ] **Step 2: 실패 확인** — `npx vitest run src/api src/pages/ChatRoomPage.test.tsx` → 수정한 테스트 FAIL

- [ ] **Step 3: `client.ts`의 `errorMessage` 수정**

```ts
export const CONNECTION_ERROR = '서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.'

// 계획 6 세부 #12: 사용자에게는 서버 문구만 보인다. 코드는 개발자 도구의 응답 본문에서 본다
export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) return e.code === 'UNKNOWN' ? CONNECTION_ERROR : e.message
  if (e instanceof TypeError) return CONNECTION_ERROR
  return e instanceof Error ? e.message : String(e)
}
```

- [ ] **Step 4: `ChatRoomPage.tsx` 다시 쓰기**

```tsx
import { useRef } from 'react'
import { PollingPanel } from '../components/PollingPanel'
import { Composer } from '../components/Composer'
import { MessageList } from '../components/MessageList'
import { defaultSenderName } from '../users/senderName'
import { buildChatItems } from '../messages/chatItems'
import { useChatScroll } from '../messages/useChatScroll'
import { useRoomMessages } from '../messages/useRoomMessages'

type Props = { userId: number; roomId: number; title: string; onBack: () => void; senderName?: (senderId: number) => string }

export function ChatRoomPage({ userId, roomId, title, onBack, senderName = defaultSenderName }: Props) {
  const room = useRoomMessages(userId, roomId)
  const listRef = useRef<HTMLOListElement>(null)
  const scroll = useChatScroll(listRef, room.messages, userId)

  async function leave() {
    if (await room.leave()) onBack()
  }

  return (
    <section className="chat">
      <header className="chat-header">
        <button className="back" onClick={onBack}>방 목록으로</button>
        <h2>{title}</h2>
        {room.status === 'ready' && (
          <>
            {/* 계획 6 세부 #8: 관측용 패널(ADR-081)은 남기되 평소에는 접어 둔다 */}
            <details className="debug">
              <summary>폴링 상태</summary>
              <PollingPanel stats={room.polling.stats} cursor={room.polling.cursor} intervalMs={room.polling.intervalMs}
                paused={room.polling.paused} onIntervalChange={room.polling.setIntervalMs} onTogglePause={room.polling.togglePause} />
            </details>
            <button onClick={() => void leave()}>나가기</button>
          </>
        )}
      </header>
      {room.error && <p role="alert" className="error">{room.error}</p>}
      {room.status === 'loading' && <p className="placeholder">불러오는 중…</p>}
      {room.status === 'notMember' && (
        <div className="join">
          <p>이 방의 멤버가 아닙니다.</p>
          <button onClick={() => void room.join()}>입장</button>
        </div>
      )}
      {room.status === 'ready' && (
        <>
          <MessageList items={buildChatItems(room.messages, userId)} senderName={senderName} listRef={listRef}
            onScroll={scroll.onScroll} hasOlder={room.hasOlder} onLoadOlder={() => void room.loadOlder()}
            unseen={scroll.unseen} onJumpToBottom={scroll.scrollToBottom} />
          <Composer onSend={room.send} />
        </>
      )}
    </section>
  )
}
```

- [ ] **Step 5: 통과 확인** — `npx vitest run` → 전부 PASS. `RoomListPage.test.tsx`의 401 테스트(`UNAUTHENTICATED` 기대)는 작업 6C에서 고치므로 여기서 실패하면 기대를 `'인증 정보가 없거나 올바르지 않습니다.'`로 바꿔 둔다. `LoginPage.test.tsx`의 400 테스트도 기대를 `'요청 값이 올바르지 않습니다.'`로 바꾼다. `App.tsx`는 6C 전까지 `title={`방 #${route.roomId}`}`를 임시로 넘겨 타입 검사를 통과시킨다(웨이브 2에서 결정). `npx tsc -b && npm run lint` 오류 없음.

- [ ] **Step 6: 결과 보고 후 멈춤**

---

### 작업 6A: 사용자 조회 API (백엔드)

> 웨이브 1 · 선행 없음 · 프론트 작업과 동시 진행

**Files:**
- Create: `backend/src/main/java/jissuo/chat/user/api/UserController.java`, `backend/src/test/java/jissuo/chat/user/api/UserApiContract.java`, `MySqlUserApiTest.java`, `PostgresUserApiTest.java`
- Modify: `UserRepository`, `JdbcUserRepository`, `UserService`

**Interfaces:**
- Produces: `GET /api/users?ids=1,2,3` (최대 100개, 인증 필요) → `ApiResponse<List<UserResponse>>` (`{ id, nickname }`). 없는 id는 빼고 돌려준다. id가 없거나 100개를 넘으면 400 `INVALID_REQUEST`. 작업 6C의 `listUsers`가 이 계약을 쓴다.

- [ ] **Step 1: 실패하는 테스트** — `UserApiContract.java` (`RoomApiContract`와 같은 구조, MySQL/Postgres 하위 클래스 두 개)
```java
@Test
void 여러_id의_닉네임을_조회한다() throws Exception {
    long a = addUser("철수");
    long b = addUser("영희");
    mvc.perform(get("/api/users").param("ids", a + "," + b + ",999999").header("X-User-Id", a))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[?(@.id == " + b + ")].nickname").value("영희"));
}

@Test
void id가_없거나_100개를_넘으면_400() throws Exception {
    long a = addUser("철수");
    mvc.perform(get("/api/users").header("X-User-Id", a)).andExpect(status().isBadRequest());
    String many = java.util.stream.LongStream.rangeClosed(1, 101).mapToObj(Long::toString).collect(java.util.stream.Collectors.joining(","));
    mvc.perform(get("/api/users").param("ids", many).header("X-User-Id", a))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
}
```

- [ ] **Step 2: 실패 확인** — `cd backend && ./gradlew test --tests 'jissuo.chat.user.api.*'` → FAIL

- [ ] **Step 3: 구현**
```java
// UserRepository
List<User> findAllById(Collection<Long> ids);

// JdbcUserRepository
@Override
public List<User> findAllById(Collection<Long> ids) {
    return jdbc.sql("SELECT id, nickname FROM users WHERE id IN (:ids)")
            .param("ids", ids)
            .query((rs, n) -> new User(rs.getLong("id"), new Nickname(rs.getString("nickname"))))
            .list();
}

// UserService
public List<User> findAll(Collection<Long> ids) {
    return users.findAllById(ids);
}

// UserController (새 파일, user.api)
@RestController
public class UserController {
    // 계획 6 세부 #13: 화면이 한 번에 보이는 사람 수보다 넉넉하고, IN 목록이 무한히 커지지 않게 한다
    static final int MAX_IDS = 100;
    private final UserService users;
    public UserController(UserService users) { this.users = users; }

    @GetMapping("/api/users")
    ApiResponse<List<UserResponse>> list(@CurrentUser AuthUser user, @RequestParam(required = false) List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_IDS) throw new ChatException(ErrorCode.INVALID_REQUEST);
        return ApiResponse.ok(users.findAll(Set.copyOf(ids)).stream().map(UserResponse::from).toList());
    }
}
```

- [ ] **Step 4: 통과 확인** — `cd backend && ./gradlew test --tests 'jissuo.chat.user.*' --tests 'jissuo.chat.ArchitectureTest'` → PASS (`@CurrentUser AuthUser` 규칙, user가 room·message를 모르는 규칙 포함)

- [ ] **Step 5: 결과 보고 후 멈춤**

---

### 작업 6B: 스타일 (`styles.css`)

> 웨이브 1 · 선행 없음 · 클래스 이름은 작업 4·5·6C의 코드 블록에 고정돼 있다

**Files:**
- Modify: `frontend/src/styles.css`

- [ ] **Step 1: `styles.css` 다시 쓰기**

```css
:root {
  font-family: system-ui, sans-serif;
  color-scheme: light dark;
  --bg: #ffffff; --panel: #f4f5f7; --text: #1f2328; --muted: #6b7280; --line: #d9dce1;
  --accent: #3b82f6; --accent-text: #ffffff; --bubble: #eef0f3; --danger: #c62828;
}
@media (prefers-color-scheme: dark) {
  :root:not([data-theme='light']) {
    --bg: #15171a; --panel: #1d2024; --text: #e6e8eb; --muted: #9aa1ab; --line: #30343a;
    --accent: #4f8ff7; --accent-text: #ffffff; --bubble: #262a30; --danger: #ef6b6b;
  }
}
* { box-sizing: border-box; }
html, body, #root { height: 100%; }
body { margin: 0; background: var(--bg); color: var(--text); }
button { font: inherit; cursor: pointer; }
button:disabled { cursor: default; opacity: .5; }
input, textarea { font: inherit; color: inherit; background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 8px 10px; }

.login { max-width: 360px; margin: 10vh auto; padding: 16px; display: grid; gap: 12px; }
.login form { display: flex; gap: 8px; }
.login input { flex: 1; }

.app { height: 100%; display: flex; flex-direction: column; }
.top { display: flex; align-items: center; gap: 12px; padding: 8px 16px; border-bottom: 1px solid var(--line); }
.top .me { margin-left: auto; color: var(--muted); }
.shell { flex: 1; min-height: 0; display: grid; grid-template-columns: 280px 1fr; }

.sidebar { display: flex; flex-direction: column; gap: 8px; padding: 12px; border-right: 1px solid var(--line); background: var(--panel); min-height: 0; }
.new-room { display: flex; gap: 6px; }
.new-room input { flex: 1; min-width: 0; }
.room-list { list-style: none; margin: 0; padding: 0; overflow-y: auto; flex: 1; }
.room-list button { width: 100%; text-align: left; padding: 10px 12px; border: 0; border-radius: 8px; background: none; color: inherit; }
.room-list button:hover { background: var(--bubble); }
.room-list button[aria-current='page'] { background: var(--accent); color: var(--accent-text); }

.main { min-width: 0; min-height: 0; display: flex; }
.placeholder { margin: auto; color: var(--muted); }
.chat { flex: 1; min-height: 0; display: flex; flex-direction: column; }
.chat-header { display: flex; align-items: center; gap: 12px; padding: 8px 16px; border-bottom: 1px solid var(--line); position: relative; }
.chat-header h2 { margin: 0; font-size: 1.05rem; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chat-header .back { display: none; }
.debug summary { cursor: pointer; color: var(--muted); font-size: 13px; }
.debug[open] .panel { position: absolute; right: 16px; top: 100%; z-index: 1; width: 260px; background: var(--bg); }
.panel { border: 1px solid var(--line); border-radius: 8px; padding: 8px; font-size: 13px; }
.panel dl { display: grid; grid-template-columns: auto 1fr; gap: 4px 8px; margin: 8px 0 0; }
.panel dd { margin: 0; word-break: break-all; }

.conversation { flex: 1; min-height: 0; position: relative; display: flex; }
.messages { flex: 1; overflow-y: auto; list-style: none; margin: 0; padding: 12px 16px; display: flex; flex-direction: column; gap: 4px; }
.messages .older { align-self: center; }
.messages .day { align-self: center; margin: 12px 0 4px; font-size: 12px; color: var(--muted); }
.msg { display: flex; flex-direction: column; align-items: flex-start; max-width: 75%; }
.msg.mine { align-self: flex-end; align-items: flex-end; }
.msg .sender { font-size: 12px; color: var(--muted); margin: 6px 0 2px 4px; }
.msg .line { display: flex; align-items: flex-end; gap: 6px; }
.msg.mine .line { flex-direction: row-reverse; }
.bubble { margin: 0; padding: 8px 12px; border-radius: 14px; background: var(--bubble); white-space: pre-wrap; overflow-wrap: anywhere; }
.msg.mine .bubble { background: var(--accent); color: var(--accent-text); }
.msg time { font-size: 11px; color: var(--muted); white-space: nowrap; }
.jump { position: absolute; bottom: 12px; left: 50%; transform: translateX(-50%); border: 0; border-radius: 16px; padding: 6px 14px; background: var(--accent); color: var(--accent-text); }

.composer { display: flex; gap: 8px; padding: 12px 16px; border-top: 1px solid var(--line); }
.composer textarea { flex: 1; resize: none; max-height: 30vh; field-sizing: content; }
.join { margin: auto; text-align: center; }
.error, [role='alert'] { color: var(--danger); margin: 8px 16px; }

@media (max-width: 640px) {
  .shell { grid-template-columns: 1fr; }
  .app[data-view='room'] .sidebar { display: none; }
  .app[data-view='rooms'] .main { display: none; }
  .chat-header .back { display: inline-block; }
  .msg { max-width: 85%; }
}
```

`LoginPage.tsx`는 구조를 바꾸지 않는다(이미 `className="login"`).

- [ ] **Step 2: 확인** — `cd frontend && npx vite build`로 CSS가 깨지지 않는지 확인한다(`tsc -b`는 다른 작업이 진행 중일 수 있어 웨이브 1 통합 확인에서 한다). 화면 확인은 작업 7에서 한다.

- [ ] **Step 3: 결과 보고 후 멈춤**

---

### 작업 6C: 사이드바 레이아웃, 작성자 닉네임

> 웨이브 3 · 선행: 4(`defaultSenderName`), 5(`ChatRoomPage`의 `title` prop, `errorMessage` 문구). 6A의 API 계약을 쓰지만 단위 테스트는 목으로 한다

**Files:**
- Modify: `frontend/src/App.tsx`, `App.test.tsx`, `frontend/src/pages/RoomListPage.tsx`, `RoomListPage.test.tsx`, `frontend/src/pages/ChatRoomPage.tsx`, `frontend/src/api/chat.ts`
- Create: `frontend/src/users/useNicknames.ts`, `useNicknames.test.ts`

**Interfaces:**
- `RoomListPage` props: `{ userId: number; activeRoomId: number | null; onOpen: (roomId: number) => void; onRoomsChange: (rooms: Room[]) => void }`
- `listUsers(userId: number, ids: number[]): Promise<ApiResult<User[]>>`, `useNicknames(userId: number, senderIds: number[]): (senderId: number) => string`

- [ ] **Step 1: 테스트 수정(실패 상태로)**
  - `RoomListPage.test.tsx`
    - 모든 `render(<RoomListPage userId={3} onOpen={...} />)`에 `activeRoomId={null} onRoomsChange={vi.fn()}`를 추가한다.
    - 첫 테스트의 `#2 · 마지막 메시지 #5`, `#1 · 메시지 없음` 기대 두 줄을 지운다.
    - 401 테스트 기대를 `'인증 정보가 없거나 올바르지 않습니다.'`로.
    - "방을 만들면" 테스트에 추가: `expect(screen.getByLabelText('방 이름')).toHaveValue('')`, `expect(chat.listRooms).toHaveBeenLastCalledWith(3, null)`, 그리고 `expect(chat.listRooms).toHaveBeenCalledTimes(2)`(처음 + 만든 뒤).
    - 새 테스트:
```ts
it('열린 방을 표시하고, 읽은 목록을 위로 알린다', async () => {
  vi.mocked(chat.listRooms).mockResolvedValue({ data: { rooms: [room(2, '둘째'), room(1, '첫째')], hasMore: false, nextCursor: null }, info })
  const onRoomsChange = vi.fn()
  render(<RoomListPage userId={3} activeRoomId={2} onOpen={vi.fn()} onRoomsChange={onRoomsChange} />)
  expect(await screen.findByRole('button', { name: '둘째' })).toHaveAttribute('aria-current', 'page')
  expect(screen.getByRole('button', { name: '첫째' })).not.toHaveAttribute('aria-current')
  expect(onRoomsChange).toHaveBeenLastCalledWith([room(2, '둘째'), room(1, '첫째')])
})
```
  - `App.test.tsx`
    - 목 바꾸기: `vi.mock('./pages/RoomListPage', () => ({ RoomListPage: () => <p>방 목록 화면</p> }))`는 그대로, `ChatRoomPage` 목은 `({ roomId, title }: { roomId: number; title: string }) => <p>채팅방 {roomId} {title}</p>`
    - "hash에 따라" 테스트: `#/rooms/7`이면 `방 목록 화면`과 `채팅방 7 방 #7`이 **둘 다** 보인다(사이드바가 항상 있음).
    - 새 테스트: 방이 없으면 `방을 고르거나 새로 만드세요.` 안내가 보인다.

- [ ] **Step 2: 실패 확인** — `npx vitest run src/App.test.tsx src/pages/RoomListPage.test.tsx` → FAIL

- [ ] **Step 3: `RoomListPage.tsx` 수정** (기존 로딩·세대 처리 로직은 그대로)

```tsx
// props 타입
type Props = { userId: number; activeRoomId: number | null; onOpen: (roomId: number) => void; onRoomsChange: (rooms: Room[]) => void }

// 상태 선언 아래에 추가 — 계획 6 세부 #11: 채팅방 제목을 사이드바가 읽은 목록에서 찾는다
useEffect(() => { onRoomsChange(rooms) }, [rooms, onRoomsChange])

// create 수정 — 계획 6 세부 #10: 사이드바에 만든 방이 보이도록 첫 페이지부터 다시 읽는다
async function create(event: FormEvent) {
  event.preventDefault()
  try {
    const { data } = await createRoom(userId, name)
    setName('')
    refresh()
    onOpen(data.id)
  } catch (e) {
    setError(errorMessage(e))
  }
}

// JSX
return (
  <aside className="sidebar" aria-label="방">
    <form className="new-room" onSubmit={create}>
      <input aria-label="방 이름" placeholder="새 방 이름" value={name} onChange={(e) => setName(e.target.value)} />
      <button type="submit">방 만들기</button>
    </form>
    <div className="sidebar-actions">
      <button onClick={refresh} disabled={isLoadingFirst}>새로고침</button>
    </div>
    {error && <p role="alert" className="error">{error}</p>}
    <ul aria-label="방 목록" className="room-list">
      {rooms.map((room) => (
        <li key={room.id}>
          <button aria-current={room.id === activeRoomId ? 'page' : undefined} onClick={() => onOpen(room.id)}>{room.name}</button>
        </li>
      ))}
    </ul>
    {hasMore && <button className="more" onClick={loadMore} disabled={isLoadingFirst || isLoadingMore}>더 보기</button>}
  </aside>
)
```

- [ ] **Step 4: `App.tsx` 수정**

```tsx
import { useState } from 'react'
import type { Room } from './api/types'
// (기존 import 유지)

export default function App() {
  const [userId, setUserId] = useState<number | null>(loadUserId)
  const [rooms, setRooms] = useState<Room[]>([])
  const route = useHashRoute()
  // (로그인 분기와 switchUser는 그대로)

  const roomId = route.page === 'room' ? route.roomId : null
  const title = rooms.find((room) => room.id === roomId)?.name ?? `방 #${roomId}`

  return (
    // 계획 6 세부 #1: 좁은 화면에서는 data-view로 목록과 대화 중 하나만 보인다
    <div className="app" data-view={roomId === null ? 'rooms' : 'room'}>
      <header className="top">
        <strong>chat</strong>
        <span className="me">사용자 #{userId}</span>
        <button onClick={switchUser}>사용자 바꾸기</button>
      </header>
      <div className="shell">
        <RoomListPage userId={userId} activeRoomId={roomId} onRoomsChange={setRooms}
          onOpen={(id) => (window.location.hash = roomHash(id))} />
        <main className="main">
          {roomId === null ? (
            <p className="placeholder">방을 고르거나 새로 만드세요.</p>
          ) : (
            // ADR-082: 방을 옮기면 커서·메시지 상태를 새로 시작하도록 다시 만든다
            <ChatRoomPage key={roomId} userId={userId} roomId={roomId} title={title}
              onBack={() => (window.location.hash = ROOMS_HASH)} />
          )}
        </main>
      </div>
    </div>
  )
}
```

- [ ] **Step 5: 프론트 `useNicknames`**
  - `api/chat.ts`에 추가:
```ts
export function listUsers(userId: number, ids: number[]) {
  return apiFetch<User[]>(`/api/users?ids=${ids.join(',')}`, { userId })
}
```
  - 실패하는 테스트 `users/useNicknames.test.ts`:
```ts
import { renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as chat from '../api/chat'
import { useNicknames } from './useNicknames'

vi.mock('../api/chat')
const info = { status: 200, requestId: 'r', durationMs: 1 }

describe('useNicknames', () => {
  beforeEach(() => vi.resetAllMocks())

  it('모르는 id만 조회하고, 받기 전에는 사용자 #id', async () => {
    vi.mocked(chat.listUsers).mockResolvedValue({ data: [{ id: 2, nickname: '영희' }], info })
    const { result, rerender } = renderHook(({ ids }) => useNicknames(1, ids), { initialProps: { ids: [2] } })
    expect(result.current(2)).toBe('사용자 #2')
    await waitFor(() => expect(result.current(2)).toBe('영희'))
    rerender({ ids: [2] })
    expect(chat.listUsers).toHaveBeenCalledTimes(1)
  })
})
```
  - 구현 `users/useNicknames.ts`:
```ts
import { useEffect, useRef, useState } from 'react'
import { listUsers } from '../api/chat'
import { defaultSenderName } from './senderName'

// 계획 6 세부 #13: 처음 보는 id만 모아 조회하고 탭 안에서 기억한다. 실패하면 사용자 #id로 둔다
export function useNicknames(userId: number, senderIds: number[]) {
  const [names, setNames] = useState<Map<number, string>>(new Map())
  const requested = useRef(new Set<number>())
  const missingKey = [...new Set(senderIds)].filter((id) => !requested.current.has(id)).sort((a, b) => a - b).join(',')

  useEffect(() => {
    if (missingKey === '') return
    const ids = missingKey.split(',').map(Number)
    ids.forEach((id) => requested.current.add(id))
    listUsers(userId, ids)
      .then(({ data }) => setNames((current) => {
        const next = new Map(current)
        for (const user of data) next.set(user.id, user.nickname)
        return next
      }))
      .catch(() => ids.forEach((id) => requested.current.delete(id)))
  }, [userId, missingKey])

  return (senderId: number) => names.get(senderId) ?? defaultSenderName(senderId)
}
```
  - `ChatRoomPage`에서 `const senderName = useNicknames(userId, room.messages.map((m) => m.senderId))`로 만들어 `MessageList`에 넘기고, `senderName` prop은 지운다.
  - 주의: 렌더 중 `requested.current`를 읽어 lint(`react-hooks/refs`)가 지적하면, `missingKey` 계산을 `names`(state)로 바꾸고 요청 중 표시는 effect 안에서만 쓴다.

- [ ] **Step 6: 전체 확인** — `cd frontend && npx vitest run && npx tsc -b && npm run lint` → 전부 PASS. 백엔드 전체 테스트는 같은 웨이브의 작업 9가 Gradle을 쓰므로 작업 9의 확인과 작업 7 Step 5에서 한다

- [ ] **Step 7: 결과 보고 후 멈춤** (화면 확인은 작업 7에서)

---

### 작업 7: E2E 갱신, 브라우저 확인, 기록

**Files:**
- Modify: `frontend/e2e/chat.spec.ts`(필요한 곳만), `docs/adr/2026-10-07.md`, `docs/journal/2026-10-07.md`, `docs/failure-lab.md`, `docs/README.md`(현재 위치), `CLAUDE.md`(현재 위치 한 줄), 이 계획서의 체크박스
- 코드 주석의 `계획 6 세부 #n`을 ADR 번호로 바꾼다.

- [ ] **Step 1: E2E 실행** — DB를 띄운 뒤(`docker compose -f infra/compose.db.yml up -d --wait`) `cd frontend && npm run e2e`
  - 예상: 이름(label·버튼·`대화`)을 유지했으므로 그대로 통과한다.
  - 사이드바가 생겨 `방 만들기` 버튼이 채팅방 화면에서도 보이므로, "나가기 후 목록 복귀" 확인(`b.getByRole('button', { name: '방 만들기' })`)이 나가기 전에도 참이 된다. 이 확인을 `await expect(b).toHaveURL(/#\/rooms$/)`로 바꾼다.
  - 실패하면 원인을 보고하고 멈춘다(테스트 기대를 임의로 느슨하게 하지 않는다).

- [ ] **Step 2: 브라우저로 확인** — 백엔드 `./gradlew bootRun --args='--spring.profiles.active=local,mysql'`, 프론트 `npm run dev`. 두 탭(다른 사용자)으로 확인하고 결과를 일지에 "측정"으로 적는다.
  1. 넓은 화면: 사이드바 + 대화, 열린 방 강조
  2. 내/남 말풍선 정렬, 연속 메시지 이름 한 번, 시간, 날짜 구분선
  3. 50개 넘게 보낸 뒤 위로 올린 상태에서 상대가 보내면 → 안 내려가고 "새 메시지 N개"
  4. "이전 메시지 더 보기" → 보던 메시지가 그대로 보임
  5. 한글 입력 후 Enter 한 번 → 한 번만 전송, Shift+Enter 줄바꿈
  6. 개발자 도구로 폭 375px → 목록/대화 한 화면씩, "방 목록으로" 버튼
  7. 다크 모드 전환 시 글자·말풍선 대비
  8. 폴링 상태 패널 펼치기 → 기존 값(커서, X-Request-Id) 그대로

- [ ] **Step 3: 장애 가설 기록** — 개편 중 발견한 위험을 `docs/failure-lab.md`에 가설로만 추가한다(고치지 않음). 후보:
  - 사이드바 목록이 자동 갱신되지 않아 다른 사람이 만든 방·순서 변화가 보이지 않음(ADR-084의 화면상 결과)
  - 방 만들기 직후 새로고침과 다른 사용자의 방 생성이 겹칠 때 목록 순서
  - `GET /api/users?ids=1,,2`처럼 빈 칸이 끼면 빈 값이 `null`로 바뀌어 `Set.copyOf`에서 500이 날 수 있음(웨이브 1에서 발견, 예상)

- [ ] **Step 4: ADR 기록** — `docs/adr/2026-10-07.md`에 "계획 6: 채팅 UI 개편" 절을 추가하고 세부 #1~#13을 다음 빈 ADR 번호부터 기록한다(결정 / 이유 / 포기한 것). `docs/journal/2026-10-07.md`에 작업 결과와 브라우저 확인 결과를 적는다. `docs/README.md`와 `CLAUDE.md`의 현재 위치를 "계획 6(UI 개편) 완료, 다음은 P7 WebSocket"으로 바꾼다.

- [ ] **Step 5: 최종 확인** — `cd frontend && npm test && npm run lint && npm run build`, `cd backend && ./gradlew test`. 출력과 함께 보고하고 멈춘다. 커밋은 사용자가 요청할 때만.

---

### 작업 8: JPA 기반과 JDBC 기준선

> 웨이브 2 · 선행: 6A, **계획 5b 공식 측정 완료**(J2) · 작업 5와 동시 진행

**Files:**
- Modify: `backend/build.gradle.kts`, `common/ChatProperties.java`, `ChatPropertiesTest.java`, `src/main/resources/application.yml`, `JdbcUserRepository`·`JdbcRoomRepository`·`JdbcMembershipRepository`(조건 어노테이션만), `docs/failure-lab.md`
- Move: `{user,room}/infra/jdbc/Jdbc*RepositoryContract.java`, `message/infra/jdbc/JdbcMessageRepositoryContract.java` → 한 단계 위 `infra/` 패키지의 `*RepositoryContract.java` (DB별 하위 클래스는 `extends`와 import만 바꾼다)
- Create: `docs/reports/YYYY-MM-DD-jdbc-vs-jpa.md`(실행한 날짜, "기준선" 절만)

- [ ] **Step 1: JDBC 기준선 재기 (아무것도 바꾸기 전)** — 결과를 보고서 "기준선" 절에 "측정"으로 적는다.
```bash
cd backend
wc -l src/main/java/jissuo/chat/*/infra/jdbc/*.java                       # 저장소 코드 줄 수
grep -o 'jdbc.sql(' -r src/main/java/jissuo/chat/*/infra/jdbc | wc -l      # 직접 쓴 SQL 수
grep -E 'private static .* to[A-Z][A-Za-z]*\(ResultSet' -r src/main/java | wc -l   # 행 → 객체 변환 코드 수
# 기동 시간과 트랜잭션 관리자: 로그의 "Started ChatApplication in N seconds"와
# 조건 보고서에서 DataSourceTransactionManagerAutoConfiguration 일치 여부를 적는다
./gradlew bootRun --args='--spring.profiles.active=local,mysql --debug' 2>&1 | tee build/boot-jdbc-before.log
```
  (`docker compose -f infra/compose.db.yml up -d --wait`로 DB를 먼저 띄운다. `Started ChatApplication`이 찍히면 서버를 종료한다.)

- [ ] **Step 2: 가설 기록** — 위 "예상되는 문제" H1~H6을 `docs/failure-lab.md`에 다음 빈 F 번호로 적는다(표 한 줄 + 본문에 재현 방법·확인할 테스트). 상태는 `가설`. 코드는 고치지 않는다.

- [ ] **Step 3: 계약 테스트를 인터페이스 기준으로 옮기기 (동작 변화 없음)**
  - `JdbcUserRepositoryContract` → `jissuo.chat.user.infra.UserRepositoryContract` (`public abstract class`). 필드 `JdbcUserRepository repository` → `UserRepository repository`. 같은 방식으로 `RoomRepositoryContract`, `MembershipRepositoryContract`(필드 `JdbcRoomRepository rooms` → `RoomRepository rooms`), `MessageRepositoryContract`.
  - 클래스 설명 주석에 이유를 덧붙인다: `// ADR-025: JDBC와 JPA 구현체가 같은 계약을 지키는지 보려고 인터페이스로 주입한다`
  - 확인: `./gradlew test --tests 'jissuo.chat.*.infra.*'` → 옮기기 전과 같은 수의 테스트가 PASS

- [ ] **Step 4: 실패하는 테스트** — `ChatPropertiesTest`에 추가
```java
@Test
void jpa를_고를_수_있다() {
    runner.withPropertyValues("chat.repository=jpa")
            .run(context -> assertThat(context.getBean(ChatProperties.class).repository())
                    .isEqualTo(ChatProperties.Repository.JPA));
}
```
  `./gradlew test --tests 'jissuo.chat.common.ChatPropertiesTest'` → FAIL

- [ ] **Step 5: 의존성과 설정**
  - `build.gradle.kts`: `implementation("org.springframework.boot:spring-boot-starter-data-jpa")`, `testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")`
  - `ChatProperties`: `public enum Repository { JDBC, JPA }`
  - `application.yml`의 `spring:` 아래:
```yaml
  jpa:
    # J7: 요청이 끝날 때까지 연결을 잡지 않게 해서 JDBC와 같은 연결 사용 조건으로 비교한다
    open-in-view: false
    hibernate:
      # J6: 스키마 원본은 Flyway다. 엔티티가 테이블과 어긋나면 기동할 때 실패시킨다
      ddl-auto: validate
```
  - `JdbcUserRepository`, `JdbcRoomRepository`, `JdbcMembershipRepository`에 `@ConditionalOnProperty(prefix = "chat", name = "repository", havingValue = "jdbc", matchIfMissing = true)`를 붙인다(`MessageRepositoryConfig`와 같은 조건).

- [ ] **Step 6: 확인과 측정**
  - `./gradlew test` → 전체 PASS (기본값 jdbc)
  - Step 1의 `bootRun --debug`를 다시 실행해 `build/boot-jdbc-after.log`로 남기고, 기동 시간과 트랜잭션 관리자 자동 설정이 바뀌었는지 보고서에 "측정"으로 적는다(J2의 예상 확인).

- [ ] **Step 7: 결과 보고 후 멈춤**

---

### 작업 9: `User` JPA 구현체

> 웨이브 3 · 선행: 8 · 작업 6C와 동시 진행

**Files:**
- Create: `user/infra/jpa/UserEntity.java`, `SpringDataUserRepository.java`, `JpaUserRepository.java`
- Create (test): `user/infra/jpa/MySqlJpaUserRepositoryTest.java`, `PostgresJpaUserRepositoryTest.java`

- [ ] **Step 1: 실패하는 테스트** — 기존 DB별 하위 클래스와 같은 모양이고 설정만 다르다.
```java
@SpringBootTest(properties = "chat.repository=jpa")
@ActiveProfiles("mysql")
class MySqlJpaUserRepositoryTest extends UserRepositoryContract {

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) {
        MySqlContainerSupport.register(registry);
    }
}
```
- [ ] **Step 2: 실패 확인** — `./gradlew test --tests 'jissuo.chat.user.infra.jpa.*'` → FAIL (`UserRepository` 빈 없음)

- [ ] **Step 3: 구현**
```java
@Entity
@Table(name = "users")
class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String nickname;
    private LocalDateTime createdAt;

    protected UserEntity() {
    }

    UserEntity(String nickname, LocalDateTime createdAt) {
        this.nickname = nickname;
        this.createdAt = createdAt;
    }

    Long getId() { return id; }
    String getNickname() { return nickname; }
}

interface SpringDataUserRepository extends JpaRepository<UserEntity, Long> {
}

@Repository
@ConditionalOnProperty(prefix = "chat", name = "repository", havingValue = "jpa")
public class JpaUserRepository implements UserRepository {

    private final SpringDataUserRepository users;

    public JpaUserRepository(SpringDataUserRepository users) {
        this.users = users;
    }

    @Override
    public long save(Nickname nickname, Instant createdAt) {
        // 계획 1 세부 2: JDBC 구현과 같게 UTC 기준 LocalDateTime으로 저장한다 (J3: 도메인 record는 엔티티가 될 수 없어 변환한다)
        return users.save(new UserEntity(nickname.value(), LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC))).getId();
    }

    @Override
    public List<User> findAllById(Collection<Long> ids) {
        return users.findAllById(ids).stream()
                .map(e -> new User(e.getId(), new Nickname(e.getNickname())))
                .toList();
    }
}
```
- [ ] **Step 4: 확인** — `./gradlew test --tests 'jissuo.chat.user.*' --tests 'jissuo.chat.ArchitectureTest'` → PASS. 실패하면 원인을 보고하고 멈춘다(가설과 관련 있으면 해당 F 번호에 재현 결과를 적는다).

- [ ] **Step 5: 결과 보고 후 멈춤**

---

### 작업 10: `Room` JPA 구현체

> 웨이브 5 · 선행: 9

**Files:**
- Create: `room/infra/jpa/RoomEntity.java`, `SpringDataRoomRepository.java`, `JpaRoomRepository.java`, (test) `room/infra/jpa/{MySql,Postgres}JpaRoomRepositoryTest.java`

- [ ] **Step 1~2: 실패하는 테스트와 확인** — 작업 9 Step 1과 같은 모양으로 `RoomRepositoryContract`를 물려받는다. `./gradlew test --tests 'jissuo.chat.room.infra.jpa.*'` → FAIL

- [ ] **Step 3: 구현** — `RoomEntity`는 `rooms`의 컬럼(`id` IDENTITY, `name`, `createdBy`, `lastMessageId`(Long, null 가능), `createdAt`)을 `UserEntity`와 같은 형식으로 둔다.
```java
interface SpringDataRoomRepository extends JpaRepository<RoomEntity, Long> {

    // 계획 1 세부 6: DESC에서 NULL 위치가 DB마다 달라 직접 정한다 (JDBC 구현과 같은 정렬)
    String ORDER = " ORDER BY CASE WHEN r.lastMessageId IS NULL THEN 1 ELSE 0 END, r.lastMessageId DESC, r.id DESC";

    @Query("SELECT r FROM RoomEntity r" + ORDER)
    List<RoomEntity> findFirstPage(Pageable page);

    @Query("SELECT r FROM RoomEntity r WHERE r.lastMessageId IS NULL AND r.id < :id" + ORDER)
    List<RoomEntity> findEmptyRoomsAfter(long id, Pageable page);

    @Query("SELECT r FROM RoomEntity r WHERE r.lastMessageId < :lastMessageId"
            + " OR (r.lastMessageId = :lastMessageId AND r.id < :id) OR r.lastMessageId IS NULL" + ORDER)
    List<RoomEntity> findPageAfter(long lastMessageId, long id, Pageable page);

    // ADR-016: 엔티티를 읽고 바꾸면(변경 감지) 늦게 커밋된 작은 번호가 덮어쓸 수 있어 조건부 UPDATE 그대로 둔다 (H5)
    @Transactional
    @Modifying
    @Query("UPDATE RoomEntity r SET r.lastMessageId = :messageId"
            + " WHERE r.id = :roomId AND (r.lastMessageId IS NULL OR r.lastMessageId < :messageId)")
    int advanceLastMessageId(long roomId, long messageId);
}
```
  어댑터 `JpaRoomRepository`(`@Repository`, `@ConditionalOnProperty(... havingValue = "jpa")`):
```java
@Override
public List<Room> findPage(RoomListCursor cursor, int limit) {
    Pageable page = PageRequest.ofSize(limit);
    List<RoomEntity> found;
    if (cursor == null) {
        found = rooms.findFirstPage(page);
    } else if (cursor.lastMessageId() == null) {
        // 메시지 없는 방 구간에 들어왔으면 그 뒤에는 메시지 없는 방만 남는다
        found = rooms.findEmptyRoomsAfter(cursor.id(), page);
    } else {
        found = rooms.findPageAfter(cursor.lastMessageId(), cursor.id(), page);
    }
    return found.stream().map(JpaRoomRepository::toRoom).toList();
}
```
  `save`는 `rooms.save(new RoomEntity(...)).getId()`, `findById`는 `rooms.findById(id).map(JpaRoomRepository::toRoom)`, `advanceLastMessageId`는 위 쿼리를 부른다. `toRoom`은 `createdAt.toInstant(ZoneOffset.UTC)`로 바꾼다.

- [ ] **Step 4: 확인** — `./gradlew test --tests 'jissuo.chat.room.infra.*' --tests 'jissuo.chat.ArchitectureTest'` → PASS. 실패하면 보고하고 멈춘다.

- [ ] **Step 5: 결과 보고 후 멈춤**

---

### 작업 11: `Membership` JPA 구현체

> 웨이브 6 · 선행: 10 · **가설 H1·H2·H6이 드러날 것으로 예상하는 작업**

**Files:**
- Create: `room/infra/jpa/MembershipId.java`, `MembershipEntity.java`, `SpringDataMembershipRepository.java`, `JpaMembershipRepository.java`, (test) `room/infra/jpa/{MySql,Postgres}JpaMembershipRepositoryTest.java`

- [ ] **Step 1~2: 실패하는 테스트와 확인** — `MembershipRepositoryContract`를 물려받는다. `./gradlew test --tests 'jissuo.chat.room.infra.jpa.*Membership*'` → FAIL

- [ ] **Step 3: 구현** — ADR-034대로 가장 흔한 방식(`save()`)으로 구현하고, JDBC 구현의 예외 처리를 그대로 옮긴다.
```java
@Embeddable
class MembershipId implements Serializable {
    private Long roomId;
    private Long userId;
    protected MembershipId() {
    }
    MembershipId(long roomId, long userId) { this.roomId = roomId; this.userId = userId; }
    // equals/hashCode는 roomId, userId로 (JPA 복합 키 요구)
}

@Entity
@Table(name = "room_members")
class MembershipEntity {
    @EmbeddedId
    private MembershipId id;
    private Long joinedMessageId;
    private LocalDateTime joinedAt;
    // protected 기본 생성자, 패키지 생성자, getter
}

interface SpringDataMembershipRepository extends JpaRepository<MembershipEntity, MembershipId> {

    // ADR-010: 지운 행 수로 멤버였는지 판단하려고 벌크 DELETE를 쓴다 (deleteById는 먼저 조회한다)
    @Transactional
    @Modifying
    @Query("DELETE FROM MembershipEntity m WHERE m.id.roomId = :roomId AND m.id.userId = :userId")
    int deleteByKey(long roomId, long userId);
}
```
  어댑터 `save`:
```java
@Override
public void save(Membership membership) {
    try {
        memberships.save(toEntity(membership));
    } catch (DuplicateKeyException e) {
        // R2, ADR-019 (JDBC 구현과 같은 처리)
        throw new ChatException(ErrorCode.ALREADY_MEMBER);
    } catch (DataIntegrityViolationException e) {
        // ADR-031 (JDBC 구현과 같은 처리)
        throw new ChatException(ErrorCode.UNAUTHENTICATED);
    }
}
```
  `find`는 `findById(new MembershipId(roomId, userId))`, `delete`는 `deleteByKey(...) > 0`.

- [ ] **Step 4: 확인** — `./gradlew test --tests 'jissuo.chat.room.infra.*'`
  - 예상: 중복 입장 테스트가 FAIL(H1, 또는 H6). **고치지 않고 멈춘다.** 실패 메시지, 실행된 SQL(`logging.level.org.hibernate.SQL=DEBUG`), 해당 F 번호를 보고하고, 원인 분석과 해결 방법(예: `persist` 사용, `Persistable` 구현, 엔티티 버전 필드)은 사용자와 함께 정한다.
  - 예상과 달리 통과하면 그 사실과 실행된 SQL을 그대로 보고한다.

- [ ] **Step 5: 결과 보고 후 멈춤**

---

### 작업 12: `Message` JPA 구현체

> 웨이브 7 · 선행: 11 · 계획 5b가 스키마 B를 골랐으면 시작하지 말고 보고한다(J5, H4 재현 방법을 함께 정한다)

**Files:**
- Create: `message/infra/jpa/MessageEntity.java`, `SpringDataMessageRepository.java`, `JpaMessageRepository.java`, `JpaMessageRepositoryConfig.java`, (test) `message/infra/jpa/`의 DB별 하위 클래스(JDBC 하위 클래스와 같은 조합 중 스키마 A만)

- [ ] **Step 1~2: 실패하는 테스트와 확인** — `MessageRepositoryContract`를 물려받고 `tableName()`은 `"messages"`. 추가로 "jpa + 스키마 B면 기동에 실패한다" 테스트(`ApplicationContextRunner` 또는 `@SpringBootTest` 실패 확인)를 둔다. `./gradlew test --tests 'jissuo.chat.message.infra.jpa.*'` → FAIL

- [ ] **Step 3: 구현** — `MessageEntity`는 `messages`의 컬럼(`id` IDENTITY, `roomId`, `senderId`, `content`, `createdAt`).
```java
interface SpringDataMessageRepository extends JpaRepository<MessageEntity, Long> {
    // 입장 경계 id
    List<MessageEntity> findByRoomIdAndIdGreaterThanOrderByIdDesc(long roomId, long boundaryId, Pageable page);
    List<MessageEntity> findByRoomIdAndIdGreaterThanAndIdLessThanOrderByIdDesc(long roomId, long boundaryId, long beforeId, Pageable page);
    List<MessageEntity> findByRoomIdAndIdGreaterThanOrderByIdAsc(long roomId, long afterId, Pageable page);
    // 입장 경계 time
    List<MessageEntity> findByRoomIdAndCreatedAtGreaterThanOrderByIdDesc(long roomId, LocalDateTime joinedAt, Pageable page);
    List<MessageEntity> findByRoomIdAndCreatedAtGreaterThanAndIdLessThanOrderByIdDesc(long roomId, LocalDateTime joinedAt, long beforeId, Pageable page);
    List<MessageEntity> findByRoomIdAndCreatedAtGreaterThanAndIdGreaterThanOrderByIdAsc(long roomId, LocalDateTime joinedAt, long afterId, Pageable page);
}
```
  - 어댑터 `find`는 `boundary`(id/time) × 커서 방향(LATEST/AFTER/BEFORE)으로 위 메서드를 고른다. id 경계의 AFTER는 `Math.max(boundary.messageId(), cursor.id())`로 한 조건에 합친다(결과는 JDBC의 두 조건과 같다). LATEST·BEFORE 결과는 JDBC 구현처럼 뒤집어 오래된 순으로 돌려준다(ADR-017).
  - `save`는 `createdAt.truncatedTo(ChronoUnit.MICROS)`로 JDBC 구현과 같은 정밀도로 저장하고 그 값을 돌려준다.
  - `JpaMessageRepositoryConfig`:
```java
@Configuration(proxyBeanMethods = false)
public class JpaMessageRepositoryConfig {

    @Bean
    @ConditionalOnProperty(prefix = "chat", name = "repository", havingValue = "jpa")
    MessageRepository messageRepository(SpringDataMessageRepository messages, ChatProperties properties) {
        // J5: 5b에서 고른 스키마만 JPA로 만든다. 다른 스키마로 켜면 조건이 틀린 채 측정되지 않게 기동을 멈춘다
        if (properties.messageSchema() != ChatProperties.MessageSchema.A) {
            throw new IllegalStateException("JPA 구현은 메시지 스키마 A만 지원한다");
        }
        return new JpaMessageRepository(messages, properties.joinBoundary());
    }
}
```
- [ ] **Step 4: 확인** — `./gradlew test --tests 'jissuo.chat.message.*' --tests 'jissuo.chat.ArchitectureTest'` → PASS(`message`는 `room.domain`만 의존). 실패하면 보고하고 멈춘다.

- [ ] **Step 5: 결과 보고 후 멈춤**

---

### 작업 13: JDBC와 JPA 비교 측정, 기록

> 웨이브 8 · 선행: 7, 12 · k6 측정은 다른 작업 없이 혼자 실행한다

**Files:**
- Create: `room/api/{MySql,Postgres}JpaRoomApiTest.java`, `message/api/{MySql,Postgres}JpaMessageApiTest.java`, `user/api/{MySql,Postgres}JpaUserApiTest.java`, `experiment/SqlCountExperiment.java`(+ DB·구현별 하위 클래스)
- Modify: `infra/compose.bench.yml`(환경 변수 한 줄), `docs/reports/YYYY-MM-DD-jdbc-vs-jpa.md`, `docs/failure-lab.md`, `docs/adr/`(실행한 날짜), `docs/journal/`(실행한 날짜), `docs/design/architecture.md`(SQL 로그 패키지), `docs/README.md`, `CLAUDE.md`("쓰지 않는 것: JPA" 줄과 현재 위치)

- [ ] **Step 1: API 테스트를 JPA로** — 기존 `*ApiContract`의 DB별 하위 클래스와 같은 모양에 `properties = "chat.repository=jpa"`만 다르게 만든다. `./gradlew test --tests 'jissuo.chat.*.api.*Jpa*'`
  - 예상: 저장소 계약 테스트는 통과했어도 여기서 H2(커밋 시점 제약 위반 → 500)가 드러날 수 있다. 실패하면 멈추고 보고한다.

- [ ] **Step 2: 요청당 실행 SQL 수** — `@Tag("experiment")` 테스트. 두 구현에 같은 방법을 쓰려고 라이브러리 없이 `DataSource`를 감싸 센다.
```java
// 실험용: JDBC와 JPA가 요청 하나에 SQL을 몇 번 실행하는지 같은 방법으로 센다 (F21 보조 지표)
@TestConfiguration
static class Counting {
    static final AtomicInteger STATEMENTS = new AtomicInteger();

    @Bean
    static BeanPostProcessor countStatements() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (!(bean instanceof DataSource ds)) return bean;
                return Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                        (p, m, args) -> {
                            Object result = m.invoke(ds, args);
                            if (result instanceof Connection c) return countingConnection(c);
                            return result;
                        });
            }
        };
    }
    // countingConnection: prepareStatement·createStatement 호출 때 STATEMENTS를 1 올리는 Connection 프록시
}
```
  시나리오(MockMvc): 방 만들기, 입장, 메시지 전송, 최신 조회, 폴링 조회(`after`), 방 목록. 각 요청 전에 0으로 맞추고 횟수를 출력한다. MySQL·PostgreSQL × jdbc·jpa. `./gradlew experimentTest --tests '*SqlCountExperiment*'`

- [ ] **Step 3: 코드량·기동** — 작업 8 Step 1의 명령을 `infra/jpa`에 같은 방법으로 실행한다(JPA의 "직접 쓴 쿼리 수"는 `@Query` 수 + 파생 쿼리 메서드 수). `bootRun --args='--spring.profiles.active=local,mysql --chat.repository=jpa'`로 기동 시간과 `jvm_memory_used_bytes`를 jdbc와 비교한다.

- [ ] **Step 4: 성능 (F21)** — `infra/compose.bench.yml`의 app 환경 변수에 `CHAT_REPOSITORY: "${BENCH_REPOSITORY:-jdbc}"`를 추가하고 `./gradlew bootJar`. 계획 5b에서 고른 DB·스키마·50만 건 데이터로, 5b와 같은 워밍업·반복(ADR-103) 조건에서 W1~W5 기준선을 `BENCH_REPOSITORY=jdbc`와 `jpa`로 각각 실행한다(실행 방법은 `load/README.md`). 원시 결과는 `load/results/`에 남긴다.

- [ ] **Step 5: 보고서** — `docs/reports/YYYY-MM-DD-jdbc-vs-jpa.md`에 "확인 방법" 표의 관점마다 JDBC / JPA / 차이 / 예상과 맞았는지를 적는다. **나아진 점과 나빠진 점을 나눠 요약한다.** 측정하지 못한 항목은 이유와 함께 "측정하지 않음"으로 남긴다.

- [ ] **Step 6: 기록** — `failure-lab.md`의 H1~H6 상태(재현됨/재현 안 됨/해결)를 갱신한다. ADR에 J1~J7을 다음 빈 번호로 적는다. "기본값을 jpa로 바꿀지, JDBC 구현을 지울지"는 보고서를 보고 사용자와 정하므로 **결정할 사항으로만 보고한다.** `architecture.md`의 SQL 로그 패키지에 `org.hibernate.SQL`을 추가하고, `CLAUDE.md`의 "쓰지 않는 것: JPA(Step 1 실험 이후)"를 현재 상태(설정으로 고름)로 바꾼다.

- [ ] **Step 7: 최종 확인** — `cd backend && ./gradlew test`. 출력과 함께 보고하고 멈춘다. 커밋은 사용자가 요청할 때만.

---

## 검증 요약
| 무엇을 | 어떻게 |
|---|---|
| 기존 동작 유지 | 작업 1에서 기존 `ChatRoomPage.test.tsx`를 고치지 않고 통과 |
| 표시 규칙 | `chatItems.test.ts`(구분선·이름·시간), `MessageList.test.tsx` |
| 스크롤 | `useChatScroll.test.tsx` + 브라우저 확인 3·4 |
| 입력 | `Composer.test.tsx`(Enter, Shift+Enter, 조합 중, 전송 중 비활성화 안 함) + 브라우저 확인 5 |
| 화면 흐름 | Playwright E2E 2개 |
| 의존 규칙 | `ArchitectureTest` (사용자 조회 API 추가 후, JPA 구현체 추가 후) |
| JPA 구현의 정확성 | 같은 저장소 계약 테스트·API 테스트를 `chat.repository=jpa`로 두 DB에서 실행 |
| JPA로 나아진 점·나빠진 점 | 작업 13 보고서: 코드량, 스키마 정합, 실행 SQL 수, W1~W5 성능(F21), 기동 시간·메모리. 예상과 측정을 나눠 적음 |

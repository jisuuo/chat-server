# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트 성격

HTTP 폴링 → WebSocket → 서버 2대 → Redis로 확장하는 채팅 서버 학습 프로젝트다. 기능 완성보다 **결정 → 구현 → 장애 재현 → 원인 분석 → 해결 → 기록** 과정이 목적이다. 모든 문서와 대화는 한국어로 쓴다.

- 기술: Java 21, Spring Boot 4.1.1, Gradle Kotlin DSL(단일 모듈, 패키지 `jissuo.chat`), `JdbcClient`, Flyway, MySQL 8.4.11 / PostgreSQL 18.6 (DB는 측정 후 선택, ADR-003)
- 쓰지 않는 것: JPA(Step 1 실험 이후), Spring Security, H2(잠금·커밋 동작이 실제 DB와 달라서)
- 현재 위치: Step 1(단일 서버 + REST + 폴링). 구현 계획 `docs/superpowers/plans/2026-10-06-plan1-backend-core.md`를 작업(task) 단위로 진행 중

## 명령어

```bash
# DB (두 DB를 같은 CPU/메모리 제한, 같은 내구성 설정으로 띄운다. MySQL 13306, PostgreSQL 15432, 계정 chat/chat)
docker compose -f infra/compose.db.yml up -d --wait

# 백엔드 (backend/ 에서)
./gradlew build -x test
./gradlew test                                   # 단위 + 통합(Testcontainers, Docker 필요). @Tag("experiment") 제외
./gradlew test --tests 'jissuo.chat.room.*'      # 일부만 실행
./gradlew experimentTest                         # @Tag("experiment") 테스트만 실행
./gradlew bootRun --args='--spring.profiles.active=local,mysql'   # profile = 환경(local|bench|prod) × DB(mysql|postgres)
```

## 아키텍처 (전체 그림)

근거 문서: `docs/superpowers/specs/2026-10-06-chat-server-step1-design.md`(무엇을 만드는가), `docs/design/domain.md`(용어, 규칙 R1~R7, 의존 방향), `docs/design/architecture.md`(인증, API, 응답, 관측), `docs/adr/YYYY-MM-DD.md`(결정 이유, 날짜별).

- **패키지**: 기능별(`room`, `message`, `user`) × 4계층(`domain`, `application`, `infra/jdbc`, `api`) + 기술 관심사(`auth`, `audit`, `common`).
- **의존 방향** (ArchUnit으로 검사): `api → application → domain ← infra`. `domain`은 Spring도 모른다. 기능 사이에는 `message → room.domain`만 허용한다(`RoomService` 호출 금지). `room`·`message`는 `user`를 모르고 `userId` 값과 DB FK로만 연결한다.
- **애그리거트**: `Room`, `Membership`(둘 다 `room` 패키지), `Message`. 서로 id로만 참조한다.
- **인증**: `AuthFilter`가 `X-User-Id`를 `Authenticator`에 넘기고, 형식만 검사한다(DB 조회 없음). 컨트롤러는 `@CurrentUser AuthUser`로 받는다(ThreadLocal 쓰지 않음). `/api/dev/**`는 인증에서 제외한다. 멤버인지 판단(인가)은 서비스가 `room_members` 행으로 한다.
- **응답**: 모두 `ApiResponse`이고 `ok()`/`fail()`로만 만든다. HTTP 상태 코드는 실제 결과대로 준다. 예외 변환은 `@RestControllerAdvice` 한 곳에서 한다.
- **설정으로 구현 선택**: `chat.repository=jdbc`, `chat.message-schema=A|B`(messages PK 비교: `id` 단독 / `(room_id, id)`), `chat.join-boundary=id|time`(재입장 경계: `joined_message_id` / `joined_at`). 통합 테스트는 두 DB × 스키마 A/B에서 같은 결과를 보장해야 한다.
- **Flyway**: DB별 스크립트가 따로 있다. `backend/src/main/resources/db/migration/{mysql,postgresql}`
- **메시지 조회**: 커서 방식(`after`=폴링, `before`=위로 스크롤, 둘 다 없으면 최신). 응답은 항상 오래된 것 → 최신 순이고 `hasMore`를 포함한다. 방 목록은 `rooms.last_message_id`(비정규화 컬럼)로 정렬하고, 전송 트랜잭션 안에서 조건부 UPDATE로 갱신한다.
- **감사**: 서비스가 이벤트를 발행하고, `audit`이 커밋 후에 수신한다.

## 작업 규칙 (사용자가 정함, `docs/collab-rules.md`)

- **task는 하나씩 승인받고 시작한다.** 계획서를 승인받았다고 해서 실행까지 승인받은 것은 아니다. task가 끝나면 결과를 보여 주고 다음 task를 시작해도 되는지 묻는다.
- **장애 선행 (ADR-034)**: 예상되는 문제는 `docs/failure-lab.md`에 가설로 기록만 하고, 코드로 미리 고치지 않는다(중복 방지 키, 격리 수준 변경, 잠금 순서 변경 등 금지). 격리 수준은 각 DB 기본값을 쓴다. F22(커밋 순서 역전)와 F23(나가기와 전송의 경쟁)은 의도적으로 열어 둔 문제다. 이미 ADR로 결정된 사항은 그대로 구현한다.
- **결정은 사용자와 함께 한다.** 문서에 없는 값이나 세부는 혼자 정하지 말고 선택지 2~3개와 추천안을 제시한다. 기술 제안은 7단계 형식(한 줄 요약 → 문제 → 사용 화면의 변화 → 추천과 이유 → 비용과 위험 → 기술 근거 → 결정할 사항)으로 하고, 예상과 측정 결과를 구분한다. F·ADR 번호는 뜻을 먼저 풀어쓰고 괄호에 적는다.
- **기록**: 원본은 로컬 `docs/`다. 누적 문서는 `README.md`, `failure-lab.md`, `collab-rules.md`, `design/`이고, 날짜별 문서는 결정 기록 `adr/YYYY-MM-DD.md`와 일지 `journal/YYYY-MM-DD.md`, 요청 시 CS 지식 목록 `cs/YYYY-MM-DD.md`다. 노션 동기화는 사용자가 요청할 때만 한다.
- **코드 주석은 "왜 이렇게 했는지"를 남긴다.** 코드만 봐도 알 수 있는 "무엇을 하는지"는 쓰지 않는다. 결정이나 실험과 관련된 이유면 ADR·F 번호를 함께 적는다 (예: `// ADR-019: DB를 고르기 전까지 중복 키 위반은 모두 "이미 멤버"로 본다`).
- **자동 커밋 금지.** 사용자가 요청할 때만 커밋한다. 커밋 메시지는 한국어로 쓰고 `chore:`/`docs:` 같은 접두사를 붙인다.

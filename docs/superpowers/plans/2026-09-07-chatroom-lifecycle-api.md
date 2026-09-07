# 채팅방 생성/입장/퇴장/목록 API (Plan A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `back/` Spring Boot 애플리케이션에 채팅방 생성/입장/퇴장/목록 REST API를 동시성 안전하게 구현하고, 이후 플랜(메시지 송수신, WebSocket, Redis)이 올라탈 기반(엔티티, 인증, 에러 처리)을 마련한다.

**Architecture:** `ChatRoom`/`ChatRoomMember` JPA 엔티티 + Spring Data JPA repository, `SELECT ... FOR UPDATE` 비관적 락으로 방 lifecycle(입장/퇴장/닫힘)을 직렬화하는 서비스 계층, 표준 `Principal` 기반 인증(임시 Mock 필터)을 사용하는 REST 컨트롤러. DB가 최종 정합성(partial unique index)을 보장하고 애플리케이션 계층은 친화적 응답(idempotent 성공)을 담당한다.

**Tech Stack:** Java 21, Spring Boot 3.4.1 (Web, Data JPA, Security, Validation), Gradle Kotlin DSL, PostgreSQL 16 (docker-compose), JUnit 5 + MockMvc + real DB 통합 테스트.

## Global Constraints

- `ChatRoom.name`: required, trim 후 blank 금지, 최대 100자. (`docs/api-design.md`)
- pagination `limit`: room list 기본 20 / 최대 100. 초과 시 400. (`docs/api-design.md`)
- `createdBy`는 Request Body로 받지 않는다. 인증된 Principal의 `userId`가 기준이다. (`docs/api-design.md`, `docs/policy.md`)
- 시간 컬럼은 전부 `TIMESTAMPTZ`로 통일한다. (`docs/domain-model.md`)
- Chat Server 내부 소유 엔티티의 PK/FK는 전부 `BIGINT`. 외부 `userId`는 `VARCHAR(255)`. (`docs/domain-model.md`)
- 활성 membership은 `(chatRoomId, userId)` 기준 최대 1개, DB partial unique index(`uq_chat_room_member_active`)로 최종 강제. (`docs/domain-model.md`)
- `ChatRoom`은 물리 DELETE하지 않는다 (닫힘 상태로만 전환). (`docs/domain-model.md`)
- Join/Leave는 같은 `ChatRoom` row에 비관적 락(`SELECT ... FOR UPDATE`)으로 직렬화한다. (`docs/domain-model.md`, `docs/policy.md`)
- 중복 Join/Leave 요청은 성공 처리한다 (멱등). (`docs/policy.md`)
- 오류 body 공통 형태: `{ code, message, details? }`. (`docs/api-design.md`)
- 기존 스택: Java 21, Spring Boot 3.4.1, `back/src/main/resources/application.yml`에 `spring.jpa.hibernate.ddl-auto: none` 고정됨 — 스키마는 SQL로 직접 관리.
- 코드 인덴트는 기존 파일(`back/src/main/java/com/chatserver/common/ApplicationException.java`)과 동일하게 tab을 사용한다.

**이번 플랜에서 확정한 가정 (세션 중 사용자 확인):**

- 인증은 실제 로그인 시스템이 아직 없으므로 **임시 Mock 인증**을 쓴다: `X-User-Id` 헤더를 신뢰하는 필터가 Spring Security `Authentication`을 채운다. 이후 실제 인증(JWT 등)으로 교체할 때는 이 필터만 교체하면 되도록 컨트롤러는 표준 `java.security.Principal`만 사용한다.
- 채팅방 목록 API의 `lastMessage` 필드는 이 플랜에서 항상 `null`이다 (Message 엔티티/테이블이 아직 없음, `docs/api-design.md`의 정렬 규칙만 이 플랜 범위). 다음 플랜(메시지 송수신)에서 실제 내용으로 채운다.
- 테스트는 `docker-compose`의 실제 PostgreSQL을 사용하는 통합 테스트다 (H2 미사용 — partial unique index, 비관적 락은 실제 Postgres 의미가 필요). **모든 테스트 실행 전에 `docker compose up -d`가 되어 있어야 한다.**

---

## File Structure

```
back/src/main/java/com/chatserver/
  security/
    MockUserIdAuthenticationFilter.java   (신규) — X-User-Id 헤더 → Authentication
    RestAuthenticationEntryPoint.java     (신규) — 미인증 요청 401 JSON 응답
    SecurityConfig.java                   (신규) — SecurityFilterChain 설정
  common/
    ApplicationException.java             (수정) — errorCode 필드 추가
    ErrorResponse.java                    (기존, 변경 없음)
    GlobalExceptionHandler.java           (신규, Task 3에서 생성 후 Task 4/6에서 확장)
  chatroom/
    ChatRoom.java                         (신규)
    ChatRoomMember.java                   (신규)
    ChatRoomRepository.java               (신규, Task 2 생성 → Task 4 확장)
    ChatRoomMemberRepository.java         (신규, Task 2 생성 → Task 4/5 확장)
    ChatRoomService.java                  (신규, Task 3 생성 → Task 4/5/6 확장)
    ChatRoomController.java               (신규, Task 3 생성 → Task 4/5/6 확장)
    ChatRoomSummaryProjection.java        (신규, Task 6)
    ChatRoomListCursor.java               (신규, Task 6)
    dto/
      CreateChatRoomRequest.java          (신규, Task 3)
      ChatRoomResponse.java               (신규, Task 3)
      ChatRoomSummaryResponse.java        (신규, Task 6)
      ChatRoomListResponse.java           (신규, Task 6)
    exception/
      ChatRoomNotFoundException.java      (신규, Task 4)
      ChatRoomClosedException.java        (신규, Task 4)
      InvalidCursorException.java         (신규, Task 6)

back/src/main/resources/
  schema.sql                              (신규) — chat_room, chat_room_member DDL + 인덱스
  application.yml                         (수정) — spring.sql.init.mode: always 추가

back/src/test/java/com/chatserver/
  security/MockUserIdAuthenticationFilterTest.java   (신규, Task 1)
  chatroom/
    ChatRoomRepositoryTest.java                       (신규, Task 2)
    ChatRoomControllerCreateTest.java                 (신규, Task 3)
    ChatRoomServiceCreateRollbackTest.java             (신규, Task 3)
    ChatRoomControllerJoinTest.java                   (신규, Task 4)
    ChatRoomControllerLeaveTest.java                  (신규, Task 5)
    ChatRoomControllerListTest.java                   (신규, Task 6)
```

---

### Task 1: Mock Authentication Infrastructure

**Files:**
- Create: `back/src/main/java/com/chatserver/security/MockUserIdAuthenticationFilter.java`
- Create: `back/src/main/java/com/chatserver/security/RestAuthenticationEntryPoint.java`
- Create: `back/src/main/java/com/chatserver/security/SecurityConfig.java`
- Test: `back/src/test/java/com/chatserver/security/MockUserIdAuthenticationFilterTest.java`

**Interfaces:**
- Consumes: 없음 (최하위 인프라 계층).
- Produces: `/api/**` 요청은 `X-User-Id` 헤더가 있어야 `Principal.getName()`으로 그 값을 얻을 수 있다. 헤더 없으면 401 `{ "code": "UNAUTHORIZED", "message": "...", "details": null }`.

- [ ] **Step 1: Write the failing test**

`back/src/test/java/com/chatserver/security/MockUserIdAuthenticationFilterTest.java`:

```java
package com.chatserver.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

class MockUserIdAuthenticationFilterTest {

	private final MockUserIdAuthenticationFilter filter = new MockUserIdAuthenticationFilter();

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void setsAuthenticationWhenHeaderPresent() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("X-User-Id", "alice");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilterInternal(request, response, chain);

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		assertThat(authentication).isNotNull();
		assertThat(authentication.getName()).isEqualTo("alice");
		verify(chain).doFilter(request, response);
	}

	@Test
	void leavesContextEmptyWhenHeaderMissing() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilterInternal(request, response, chain);

		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
		verify(chain).doFilter(request, response);
	}

	@Test
	void leavesContextEmptyWhenHeaderBlank() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("X-User-Id", "   ");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilterInternal(request, response, chain);

		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd back && ./gradlew test --tests "com.chatserver.security.MockUserIdAuthenticationFilterTest"`
Expected: FAIL — `MockUserIdAuthenticationFilter` 클래스가 없어 컴파일 에러.

- [ ] **Step 3: Write MockUserIdAuthenticationFilter**

`back/src/main/java/com/chatserver/security/MockUserIdAuthenticationFilter.java`:

```java
package com.chatserver.security;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Temporary authentication: trusts the X-User-Id header as the caller's
 * identity. Replace with real credential verification once the auth
 * system exists; downstream code only depends on java.security.Principal.
 */
public class MockUserIdAuthenticationFilter extends OncePerRequestFilter {

	public static final String USER_ID_HEADER = "X-User-Id";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		String userId = request.getHeader(USER_ID_HEADER);
		if (userId != null && !userId.isBlank()) {
			Authentication authentication = new UsernamePasswordAuthenticationToken(userId, null, List.of());
			SecurityContextHolder.getContext().setAuthentication(authentication);
		}
		filterChain.doFilter(request, response);
	}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd back && ./gradlew test --tests "com.chatserver.security.MockUserIdAuthenticationFilterTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Write RestAuthenticationEntryPoint and SecurityConfig**

`back/src/main/java/com/chatserver/security/RestAuthenticationEntryPoint.java`:

```java
package com.chatserver.security;

import java.io.IOException;

import com.chatserver.common.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final ObjectMapper objectMapper;

	public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		ErrorResponse body = new ErrorResponse("UNAUTHORIZED", "Authentication required", null);
		objectMapper.writeValue(response.getWriter(), body);
	}
}
```

`back/src/main/java/com/chatserver/security/SecurityConfig.java`:

```java
package com.chatserver.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configsurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	private final RestAuthenticationEntryPoint entryPoint;

	public SecurityConfig(RestAuthenticationEntryPoint entryPoint) {
		this.entryPoint = entryPoint;
	}

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		http
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/api/**").authenticated()
						.anyRequest().permitAll())
				.exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint))
				.addFilterBefore(new MockUserIdAuthenticationFilter(),
						org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}
}
```

Note: `UsernamePasswordAuthenticationToken` import unused directly in `SecurityConfig` — remove it if your IDE flags it (only needed in the filter).

- [ ] **Step 6: Run full test suite**

Run: `cd back && ./gradlew test`
Expected: PASS. (No other tests reference security yet.)

- [ ] **Step 7: Commit**

```bash
cd back
git add src/main/java/com/chatserver/security src/test/java/com/chatserver/security
git commit -m "feat: add mock X-User-Id authentication filter and security config"
```

---

### Task 2: ChatRoom & ChatRoomMember Persistence

**Files:**
- Create: `back/src/main/java/com/chatserver/chatroom/ChatRoom.java`
- Create: `back/src/main/java/com/chatserver/chatroom/ChatRoomMember.java`
- Create: `back/src/main/java/com/chatserver/chatroom/ChatRoomRepository.java`
- Create: `back/src/main/java/com/chatserver/chatroom/ChatRoomMemberRepository.java`
- Create: `back/src/main/resources/schema.sql`
- Modify: `back/src/main/resources/application.yml`
- Test: `back/src/test/java/com/chatserver/chatroom/ChatRoomRepositoryTest.java`

**Interfaces:**
- Consumes: 없음.
- Produces:
  - `ChatRoom(String name, String createdBy, OffsetDateTime createdAt)`, `room.getId()`, `room.getName()`, `room.getCreatedAt()`, `room.isClosed()`, `room.close(OffsetDateTime closedAt)`.
  - `ChatRoomMember(Long chatRoomId, String userId, OffsetDateTime joinedAt)`, `member.getUserId()`, `member.isActive()`, `member.leave(OffsetDateTime leftAt)`.
  - `ChatRoomRepository extends JpaRepository<ChatRoom, Long>`.
  - `ChatRoomMemberRepository extends JpaRepository<ChatRoomMember, Long>`.

**전제:** `docker compose up -d` 실행 중이어야 한다 (`back/src/main/resources/application.yml`이 `localhost:5432/chatserver`를 가리킴).

- [ ] **Step 1: Write the failing test**

`back/src/test/java/com/chatserver/chatroom/ChatRoomRepositoryTest.java`:

```java
package com.chatserver.chatroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

@SpringBootTest
class ChatRoomRepositoryTest {

	@Autowired
	private ChatRoomRepository chatRoomRepository;

	@Autowired
	private ChatRoomMemberRepository chatRoomMemberRepository;

	@AfterEach
	void cleanUp() {
		chatRoomMemberRepository.deleteAll();
		chatRoomRepository.deleteAll();
	}

	@Test
	void savesAndLoadsChatRoom() {
		ChatRoom room = chatRoomRepository.save(new ChatRoom("study", "alice", OffsetDateTime.now()));

		ChatRoom found = chatRoomRepository.findById(room.getId()).orElseThrow();
		assertThat(found.getName()).isEqualTo("study");
		assertThat(found.isClosed()).isFalse();
	}

	@Test
	void activeMembershipUniquePerRoomAndUser() {
		ChatRoom room = chatRoomRepository.save(new ChatRoom("study", "alice", OffsetDateTime.now()));
		chatRoomMemberRepository.save(new ChatRoomMember(room.getId(), "alice", OffsetDateTime.now()));
		chatRoomMemberRepository.flush();

		ChatRoomMember duplicate = new ChatRoomMember(room.getId(), "alice", OffsetDateTime.now());

		assertThatThrownBy(() -> {
			chatRoomMemberRepository.save(duplicate);
			chatRoomMemberRepository.flush();
		}).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void allowsNewActiveMembershipAfterPreviousOneLeft() {
		ChatRoom room = chatRoomRepository.save(new ChatRoom("study", "alice", OffsetDateTime.now()));
		ChatRoomMember first = chatRoomMemberRepository.save(
				new ChatRoomMember(room.getId(), "alice", OffsetDateTime.now()));
		first.leave(OffsetDateTime.now());
		chatRoomMemberRepository.save(first);
		chatRoomMemberRepository.flush();

		ChatRoomMember rejoin = chatRoomMemberRepository.save(
				new ChatRoomMember(room.getId(), "alice", OffsetDateTime.now()));
		chatRoomMemberRepository.flush();

		assertThat(rejoin.getId()).isNotEqualTo(first.getId());
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd back && ./gradlew test --tests "com.chatserver.chatroom.ChatRoomRepositoryTest"`
Expected: FAIL — 컴파일 에러 (엔티티/repository 없음).

- [ ] **Step 3: Write ChatRoom and ChatRoomMember entities**

`back/src/main/java/com/chatserver/chatroom/ChatRoom.java`:

```java
package com.chatserver.chatroom;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "chat_room")
public class ChatRoom {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(name = "created_by", nullable = false)
	private String createdBy;

	@Column(name = "created_at", nullable = false)
	private OffsetDateTime createdAt;

	@Column(name = "closed_at")
	private OffsetDateTime closedAt;

	@Column(name = "last_message_at")
	private OffsetDateTime lastMessageAt;

	protected ChatRoom() {
	}

	public ChatRoom(String name, String createdBy, OffsetDateTime createdAt) {
		this.name = name;
		this.createdBy = createdBy;
		this.createdAt = createdAt;
	}

	public boolean isClosed() {
		return closedAt != null;
	}

	public void close(OffsetDateTime closedAt) {
		this.closedAt = closedAt;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getCreatedBy() {
		return createdBy;
	}

	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}

	public OffsetDateTime getClosedAt() {
		return closedAt;
	}

	public OffsetDateTime getLastMessageAt() {
		return lastMessageAt;
	}
}
```

`back/src/main/java/com/chatserver/chatroom/ChatRoomMember.java`:

```java
package com.chatserver.chatroom;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "chat_room_member")
public class ChatRoomMember {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "chat_room_id", nullable = false)
	private Long chatRoomId;

	@Column(name = "user_id", nullable = false)
	private String userId;

	@Column(name = "joined_at", nullable = false)
	private OffsetDateTime joinedAt;

	@Column(name = "left_at")
	private OffsetDateTime leftAt;

	protected ChatRoomMember() {
	}

	public ChatRoomMember(Long chatRoomId, String userId, OffsetDateTime joinedAt) {
		this.chatRoomId = chatRoomId;
		this.userId = userId;
		this.joinedAt = joinedAt;
	}

	public boolean isActive() {
		return leftAt == null;
	}

	public void leave(OffsetDateTime leftAt) {
		this.leftAt = leftAt;
	}

	public Long getId() {
		return id;
	}

	public Long getChatRoomId() {
		return chatRoomId;
	}

	public String getUserId() {
		return userId;
	}

	public OffsetDateTime getJoinedAt() {
		return joinedAt;
	}

	public OffsetDateTime getLeftAt() {
		return leftAt;
	}
}
```

- [ ] **Step 4: Write repositories**

`back/src/main/java/com/chatserver/chatroom/ChatRoomRepository.java`:

```java
package com.chatserver.chatroom;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {
}
```

`back/src/main/java/com/chatserver/chatroom/ChatRoomMemberRepository.java`:

```java
package com.chatserver.chatroom;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRoomMemberRepository extends JpaRepository<ChatRoomMember, Long> {
}
```

- [ ] **Step 5: Write schema.sql and enable SQL init**

`back/src/main/resources/schema.sql`:

```sql
CREATE TABLE IF NOT EXISTS chat_room (
    id              BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    created_by      VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    closed_at       TIMESTAMPTZ NULL,
    last_message_at TIMESTAMPTZ NULL
);

CREATE TABLE IF NOT EXISTS chat_room_member (
    id           BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    chat_room_id BIGINT NOT NULL,
    user_id      VARCHAR(255) NOT NULL,
    joined_at    TIMESTAMPTZ NOT NULL,
    left_at      TIMESTAMPTZ NULL,
    CONSTRAINT fk_chat_room_member_room
        FOREIGN KEY (chat_room_id) REFERENCES chat_room(id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_room_member_active
ON chat_room_member (chat_room_id, user_id)
WHERE left_at IS NULL;

CREATE INDEX IF NOT EXISTS ix_chat_room_member_active_by_user
ON chat_room_member (user_id)
WHERE left_at IS NULL;
```

Modify `back/src/main/resources/application.yml` — add `spring.sql.init.mode: always` so Boot runs `schema.sql` against the configured (non-embedded) datasource on every startup:

```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/chatserver
    username: chatserver
    password: chatserver
  sql:
    init:
      mode: always
  jpa:
    hibernate:
      ddl-auto: none
  data:
    redis:
      host: localhost
      port: 6379
```

- [ ] **Step 6: Run test to verify it passes**

Precondition: `docker compose up -d` (repo root).

Run: `cd back && ./gradlew test --tests "com.chatserver.chatroom.ChatRoomRepositoryTest"`
Expected: PASS (3 tests). The unique-index test confirms `uq_chat_room_member_active` rejects a second active membership row.

- [ ] **Step 7: Commit**

```bash
cd back
git add src/main/java/com/chatserver/chatroom src/main/resources/schema.sql src/main/resources/application.yml src/test/java/com/chatserver/chatroom/ChatRoomRepositoryTest.java
git commit -m "feat: add ChatRoom/ChatRoomMember entities and schema"
```

---

### Task 3: Chat Room Creation API

**Files:**
- Create: `back/src/main/java/com/chatserver/chatroom/dto/CreateChatRoomRequest.java`
- Create: `back/src/main/java/com/chatserver/chatroom/dto/ChatRoomResponse.java`
- Create: `back/src/main/java/com/chatserver/chatroom/ChatRoomService.java`
- Create: `back/src/main/java/com/chatserver/chatroom/ChatRoomController.java`
- Create: `back/src/main/java/com/chatserver/common/GlobalExceptionHandler.java`
- Modify: `back/src/main/java/com/chatserver/common/ApplicationException.java`
- Test: `back/src/test/java/com/chatserver/chatroom/ChatRoomControllerCreateTest.java`
- Test: `back/src/test/java/com/chatserver/chatroom/ChatRoomServiceCreateRollbackTest.java`

**Interfaces:**
- Consumes: `ChatRoomRepository`, `ChatRoomMemberRepository` (Task 2). `Principal` from Task 1's auth filter.
- Produces:
  - `ChatRoomService.createChatRoom(String name, String creatorUserId): ChatRoom` — `@Transactional`.
  - `POST /api/chat-rooms` — body `{ "name": "..." }`, header `X-User-Id` required → `201 { roomId, name, createdAt }`.
  - `ApplicationException(String errorCode, String message)` — base type for domain exceptions used by later tasks.
  - `GlobalExceptionHandler` (`@RestControllerAdvice`) — later tasks add `@ExceptionHandler` methods here.

- [ ] **Step 1: Write the failing tests**

`back/src/test/java/com/chatserver/chatroom/ChatRoomControllerCreateTest.java`:

```java
package com.chatserver.chatroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.chatserver.chatroom.dto.CreateChatRoomRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ChatRoomControllerCreateTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private ChatRoomRepository chatRoomRepository;

	@Autowired
	private ChatRoomMemberRepository chatRoomMemberRepository;

	@AfterEach
	void cleanUp() {
		chatRoomMemberRepository.deleteAll();
		chatRoomRepository.deleteAll();
	}

	@Test
	void createsRoomAndAutoJoinsCreator() throws Exception {
		mockMvc.perform(post("/api/chat-rooms")
						.header("X-User-Id", "alice")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateChatRoomRequest("study"))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.roomId").exists())
				.andExpect(jsonPath("$.name").value("study"));

		List<ChatRoom> rooms = chatRoomRepository.findAll();
		assertThat(rooms).hasSize(1);
		List<ChatRoomMember> members = chatRoomMemberRepository.findAll();
		assertThat(members).hasSize(1);
		assertThat(members.get(0).getUserId()).isEqualTo("alice");
		assertThat(members.get(0).isActive()).isTrue();
	}

	@Test
	void rejectsBlankName() throws Exception {
		mockMvc.perform(post("/api/chat-rooms")
						.header("X-User-Id", "alice")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateChatRoomRequest("   "))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void rejectsWithoutAuthentication() throws Exception {
		mockMvc.perform(post("/api/chat-rooms")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateChatRoomRequest("study"))))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}
}
```

`back/src/test/java/com/chatserver/chatroom/ChatRoomServiceCreateRollbackTest.java`:

```java
package com.chatserver.chatroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest
class ChatRoomServiceCreateRollbackTest {

	@Autowired
	private ChatRoomService chatRoomService;

	@Autowired
	private ChatRoomRepository chatRoomRepository;

	@MockBean
	private ChatRoomMemberRepository chatRoomMemberRepository;

	@AfterEach
	void cleanUp() {
		chatRoomRepository.deleteAll();
	}

	@Test
	void rollsBackRoomCreationWhenMemberSaveFails() {
		when(chatRoomMemberRepository.save(any())).thenThrow(new RuntimeException("boom"));

		assertThatThrownBy(() -> chatRoomService.createChatRoom("study", "alice"))
				.isInstanceOf(RuntimeException.class);

		assertThat(chatRoomRepository.findAll()).isEmpty();
	}
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd back && ./gradlew test --tests "com.chatserver.chatroom.ChatRoomControllerCreateTest" --tests "com.chatserver.chatroom.ChatRoomServiceCreateRollbackTest"`
Expected: FAIL — 컴파일 에러 (DTO/서비스/컨트롤러 없음).

- [ ] **Step 3: Update ApplicationException with an error code**

Modify `back/src/main/java/com/chatserver/common/ApplicationException.java`:

```java
package com.chatserver.common;

/**
 * Base type for application-specific exceptions. Subclasses supply a
 * stable errorCode used both in the HTTP error body and for client
 * branching (docs/api-design.md common error shape).
 */
public class ApplicationException extends RuntimeException {

	private final String errorCode;

	protected ApplicationException(String errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	protected ApplicationException(String errorCode, String message, Throwable cause) {
		super(message, cause);
		this.errorCode = errorCode;
	}

	public String getErrorCode() {
		return errorCode;
	}

}
```

- [ ] **Step 4: Write DTOs, service, controller, and exception handler**

`back/src/main/java/com/chatserver/chatroom/dto/CreateChatRoomRequest.java`:

```java
package com.chatserver.chatroom.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateChatRoomRequest(
		@NotBlank @Size(max = 100) String name
) {
}
```

`back/src/main/java/com/chatserver/chatroom/dto/ChatRoomResponse.java`:

```java
package com.chatserver.chatroom.dto;

import java.time.OffsetDateTime;

public record ChatRoomResponse(
		Long roomId,
		String name,
		OffsetDateTime createdAt
) {
}
```

`back/src/main/java/com/chatserver/chatroom/ChatRoomService.java`:

```java
package com.chatserver.chatroom;

import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatRoomService {

	private final ChatRoomRepository chatRoomRepository;
	private final ChatRoomMemberRepository chatRoomMemberRepository;

	public ChatRoomService(ChatRoomRepository chatRoomRepository,
			ChatRoomMemberRepository chatRoomMemberRepository) {
		this.chatRoomRepository = chatRoomRepository;
		this.chatRoomMemberRepository = chatRoomMemberRepository;
	}

	@Transactional
	public ChatRoom createChatRoom(String name, String creatorUserId) {
		OffsetDateTime now = OffsetDateTime.now();
		ChatRoom room = chatRoomRepository.save(new ChatRoom(name, creatorUserId, now));
		chatRoomMemberRepository.save(new ChatRoomMember(room.getId(), creatorUserId, now));
		return room;
	}
}
```

`back/src/main/java/com/chatserver/chatroom/ChatRoomController.java`:

```java
package com.chatserver.chatroom;

import java.security.Principal;

import com.chatserver.chatroom.dto.ChatRoomResponse;
import com.chatserver.chatroom.dto.CreateChatRoomRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat-rooms")
public class ChatRoomController {

	private final ChatRoomService chatRoomService;

	public ChatRoomController(ChatRoomService chatRoomService) {
		this.chatRoomService = chatRoomService;
	}

	@PostMapping
	public ResponseEntity<ChatRoomResponse> create(@Valid @RequestBody CreateChatRoomRequest request,
			Principal principal) {
		ChatRoom room = chatRoomService.createChatRoom(request.name(), principal.getName());
		ChatRoomResponse body = new ChatRoomResponse(room.getId(), room.getName(), room.getCreatedAt());
		return ResponseEntity.status(HttpStatus.CREATED).body(body);
	}
}
```

`back/src/main/java/com/chatserver/common/GlobalExceptionHandler.java`:

```java
package com.chatserver.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
		String details = ex.getBindingResult().getFieldErrors().stream()
				.map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
				.reduce((a, b) -> a + "; " + b)
				.orElse("Invalid request");
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new ErrorResponse("VALIDATION_FAILED", details, null));
	}
}
```

- [ ] **Step 5: Run tests to verify they pass**

Precondition: `docker compose up -d`.

Run: `cd back && ./gradlew test --tests "com.chatserver.chatroom.ChatRoomControllerCreateTest" --tests "com.chatserver.chatroom.ChatRoomServiceCreateRollbackTest"`
Expected: PASS (4 tests total).

- [ ] **Step 6: Commit**

```bash
cd back
git add src/main/java/com/chatserver/chatroom src/main/java/com/chatserver/common src/test/java/com/chatserver/chatroom
git commit -m "feat: add chat room creation API with atomic creator membership"
```

---

### Task 4: Chat Room Join API

**Files:**
- Create: `back/src/main/java/com/chatserver/chatroom/exception/ChatRoomNotFoundException.java`
- Create: `back/src/main/java/com/chatserver/chatroom/exception/ChatRoomClosedException.java`
- Modify: `back/src/main/java/com/chatserver/chatroom/ChatRoomRepository.java`
- Modify: `back/src/main/java/com/chatserver/chatroom/ChatRoomMemberRepository.java`
- Modify: `back/src/main/java/com/chatserver/chatroom/ChatRoomService.java`
- Modify: `back/src/main/java/com/chatserver/chatroom/ChatRoomController.java`
- Modify: `back/src/main/java/com/chatserver/common/GlobalExceptionHandler.java`
- Test: `back/src/test/java/com/chatserver/chatroom/ChatRoomControllerJoinTest.java`

**Interfaces:**
- Consumes: `ChatRoomService`, `ChatRoomRepository`, `ChatRoomMemberRepository` (Tasks 2-3).
- Produces:
  - `ChatRoomService.join(Long roomId, String userId): void` — `@Transactional`, idempotent, throws `ChatRoomNotFoundException` (404) / `ChatRoomClosedException` (409).
  - `POST /api/chat-rooms/{roomId}/join` → `200` on success (including duplicate join).
  - `ChatRoomRepository.findByIdForUpdate(Long id): Optional<ChatRoom>` — `PESSIMISTIC_WRITE` lock, used by Task 5 too.
  - `ChatRoomMemberRepository.findByChatRoomIdAndUserIdAndLeftAtIsNull(Long, String): Optional<ChatRoomMember>` — used by Task 5 too.

- [ ] **Step 1: Write the failing tests**

`back/src/test/java/com/chatserver/chatroom/ChatRoomControllerJoinTest.java`:

```java
package com.chatserver.chatroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ChatRoomControllerJoinTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ChatRoomRepository chatRoomRepository;

	@Autowired
	private ChatRoomMemberRepository chatRoomMemberRepository;

	@Autowired
	private ChatRoomService chatRoomService;

	@AfterEach
	void cleanUp() {
		chatRoomMemberRepository.deleteAll();
		chatRoomRepository.deleteAll();
	}

	@Test
	void joiningNonExistentRoomReturns404() throws Exception {
		mockMvc.perform(post("/api/chat-rooms/999999/join").header("X-User-Id", "bob"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CHAT_ROOM_NOT_FOUND"));
	}

	@Test
	void joiningClosedRoomReturns409() throws Exception {
		ChatRoom room = chatRoomRepository.save(new ChatRoom("study", "alice", OffsetDateTime.now()));
		room.close(OffsetDateTime.now());
		chatRoomRepository.save(room);

		mockMvc.perform(post("/api/chat-rooms/" + room.getId() + "/join").header("X-User-Id", "bob"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CHAT_ROOM_CLOSED"));
	}

	@Test
	void duplicateJoinSucceedsIdempotently() throws Exception {
		ChatRoom room = chatRoomRepository.save(new ChatRoom("study", "alice", OffsetDateTime.now()));

		mockMvc.perform(post("/api/chat-rooms/" + room.getId() + "/join").header("X-User-Id", "bob"))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/chat-rooms/" + room.getId() + "/join").header("X-User-Id", "bob"))
				.andExpect(status().isOk());

		long activeCount = chatRoomMemberRepository.findAll().stream()
				.filter(m -> m.getUserId().equals("bob") && m.isActive())
				.count();
		assertThat(activeCount).isEqualTo(1);
	}

	@Test
	void concurrentJoinsLeaveExactlyOneActiveMembership() throws Exception {
		ChatRoom room = chatRoomRepository.save(new ChatRoom("study", "alice", OffsetDateTime.now()));
		int threads = 10;
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch start = new CountDownLatch(1);

		List<Future<?>> futures = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			futures.add(executor.submit(() -> {
				ready.countDown();
				try {
					start.await();
					chatRoomService.join(room.getId(), "bob");
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}));
		}
		ready.await();
		start.countDown();
		for (Future<?> future : futures) {
			future.get();
		}
		executor.shutdown();

		long activeCount = chatRoomMemberRepository.findAll().stream()
				.filter(m -> m.getUserId().equals("bob") && m.isActive())
				.count();
		assertThat(activeCount).isEqualTo(1);
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd back && ./gradlew test --tests "com.chatserver.chatroom.ChatRoomControllerJoinTest"`
Expected: FAIL — 컴파일 에러 (`join` 없음).

- [ ] **Step 3: Write exceptions**

`back/src/main/java/com/chatserver/chatroom/exception/ChatRoomNotFoundException.java`:

```java
package com.chatserver.chatroom.exception;

import com.chatserver.common.ApplicationException;

public class ChatRoomNotFoundException extends ApplicationException {

	public ChatRoomNotFoundException(Long roomId) {
		super("CHAT_ROOM_NOT_FOUND", "Chat room not found: " + roomId);
	}
}
```

`back/src/main/java/com/chatserver/chatroom/exception/ChatRoomClosedException.java`:

```java
package com.chatserver.chatroom.exception;

import com.chatserver.common.ApplicationException;

public class ChatRoomClosedException extends ApplicationException {

	public ChatRoomClosedException(Long roomId) {
		super("CHAT_ROOM_CLOSED", "Chat room is closed: " + roomId);
	}
}
```

- [ ] **Step 4: Extend repositories**

`back/src/main/java/com/chatserver/chatroom/ChatRoomRepository.java` (full replacement):

```java
package com.chatserver.chatroom;

import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from ChatRoom r where r.id = :id")
	Optional<ChatRoom> findByIdForUpdate(@Param("id") Long id);
}
```

`back/src/main/java/com/chatserver/chatroom/ChatRoomMemberRepository.java` (full replacement):

```java
package com.chatserver.chatroom;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRoomMemberRepository extends JpaRepository<ChatRoomMember, Long> {

	Optional<ChatRoomMember> findByChatRoomIdAndUserIdAndLeftAtIsNull(Long chatRoomId, String userId);
}
```

- [ ] **Step 5: Add join() to ChatRoomService**

Modify `back/src/main/java/com/chatserver/chatroom/ChatRoomService.java` — add this method (keep `createChatRoom` unchanged):

```java
	@Transactional
	public void join(Long roomId, String userId) {
		ChatRoom room = chatRoomRepository.findByIdForUpdate(roomId)
				.orElseThrow(() -> new ChatRoomNotFoundException(roomId));
		if (room.isClosed()) {
			throw new ChatRoomClosedException(roomId);
		}
		boolean alreadyActive = chatRoomMemberRepository
				.findByChatRoomIdAndUserIdAndLeftAtIsNull(roomId, userId)
				.isPresent();
		if (alreadyActive) {
			return;
		}
		chatRoomMemberRepository.save(new ChatRoomMember(roomId, userId, OffsetDateTime.now()));
	}
```

Add the two new imports at the top of the file:

```java
import com.chatserver.chatroom.exception.ChatRoomClosedException;
import com.chatserver.chatroom.exception.ChatRoomNotFoundException;
```

- [ ] **Step 6: Add join endpoint to ChatRoomController**

Modify `back/src/main/java/com/chatserver/chatroom/ChatRoomController.java` — add this method (keep `create` unchanged), plus imports `PathVariable`, `PostMapping` already present:

```java
	@PostMapping("/{roomId}/join")
	public ResponseEntity<Void> join(@PathVariable Long roomId, Principal principal) {
		chatRoomService.join(roomId, principal.getName());
		return ResponseEntity.ok().build();
	}
```

Add import: `import org.springframework.web.bind.annotation.PathVariable;`

- [ ] **Step 7: Extend GlobalExceptionHandler**

Modify `back/src/main/java/com/chatserver/common/GlobalExceptionHandler.java` (full replacement):

```java
package com.chatserver.common;

import com.chatserver.chatroom.exception.ChatRoomClosedException;
import com.chatserver.chatroom.exception.ChatRoomNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
		String details = ex.getBindingResult().getFieldErrors().stream()
				.map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
				.reduce((a, b) -> a + "; " + b)
				.orElse("Invalid request");
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new ErrorResponse("VALIDATION_FAILED", details, null));
	}

	@ExceptionHandler(ChatRoomNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleNotFound(ChatRoomNotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new ErrorResponse(ex.getErrorCode(), ex.getMessage(), null));
	}

	@ExceptionHandler(ChatRoomClosedException.class)
	public ResponseEntity<ErrorResponse> handleClosed(ChatRoomClosedException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(new ErrorResponse(ex.getErrorCode(), ex.getMessage(), null));
	}
}
```

- [ ] **Step 8: Run tests to verify they pass**

Precondition: `docker compose up -d`.

Run: `cd back && ./gradlew test --tests "com.chatserver.chatroom.ChatRoomControllerJoinTest"`
Expected: PASS (4 tests). Then run the full suite to confirm no regressions: `./gradlew test`.

- [ ] **Step 9: Commit**

```bash
cd back
git add src/main/java/com/chatserver/chatroom src/main/java/com/chatserver/common src/test/java/com/chatserver/chatroom/ChatRoomControllerJoinTest.java
git commit -m "feat: add chat room join API with row-lock concurrency control"
```

---

### Task 5: Chat Room Leave API

**Files:**
- Modify: `back/src/main/java/com/chatserver/chatroom/ChatRoomMemberRepository.java`
- Modify: `back/src/main/java/com/chatserver/chatroom/ChatRoomService.java`
- Modify: `back/src/main/java/com/chatserver/chatroom/ChatRoomController.java`
- Test: `back/src/test/java/com/chatserver/chatroom/ChatRoomControllerLeaveTest.java`

**Interfaces:**
- Consumes: `ChatRoomRepository.findByIdForUpdate`, `ChatRoomMemberRepository.findByChatRoomIdAndUserIdAndLeftAtIsNull` (Task 4).
- Produces:
  - `ChatRoomService.leave(Long roomId, String userId): void` — `@Transactional`, idempotent, closes room when last active member leaves.
  - `POST /api/chat-rooms/{roomId}/leave` → `200` on success (including duplicate leave, including non-member).
  - `ChatRoomMemberRepository.countByChatRoomIdAndLeftAtIsNull(Long roomId): long`.

- [ ] **Step 1: Write the failing tests**

`back/src/test/java/com/chatserver/chatroom/ChatRoomControllerLeaveTest.java`:

```java
package com.chatserver.chatroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ChatRoomControllerLeaveTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ChatRoomRepository chatRoomRepository;

	@Autowired
	private ChatRoomMemberRepository chatRoomMemberRepository;

	@Autowired
	private ChatRoomService chatRoomService;

	@AfterEach
	void cleanUp() {
		chatRoomMemberRepository.deleteAll();
		chatRoomRepository.deleteAll();
	}

	@Test
	void leavingNonExistentRoomReturns404() throws Exception {
		mockMvc.perform(post("/api/chat-rooms/999999/leave").header("X-User-Id", "alice"))
				.andExpect(status().isNotFound());
	}

	@Test
	void leavingWithoutActiveMembershipSucceedsIdempotently() throws Exception {
		ChatRoom room = chatRoomRepository.save(new ChatRoom("study", "alice", OffsetDateTime.now()));
		chatRoomMemberRepository.save(new ChatRoomMember(room.getId(), "alice", OffsetDateTime.now()));

		mockMvc.perform(post("/api/chat-rooms/" + room.getId() + "/leave").header("X-User-Id", "bob"))
				.andExpect(status().isOk());
	}

	@Test
	void duplicateLeaveSucceedsIdempotently() throws Exception {
		ChatRoom room = chatRoomRepository.save(new ChatRoom("study", "alice", OffsetDateTime.now()));
		chatRoomMemberRepository.save(new ChatRoomMember(room.getId(), "alice", OffsetDateTime.now()));

		mockMvc.perform(post("/api/chat-rooms/" + room.getId() + "/leave").header("X-User-Id", "alice"))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/chat-rooms/" + room.getId() + "/leave").header("X-User-Id", "alice"))
				.andExpect(status().isOk());
	}

	@Test
	void lastMemberLeavingClosesRoom() throws Exception {
		ChatRoom room = chatRoomRepository.save(new ChatRoom("study", "alice", OffsetDateTime.now()));
		chatRoomMemberRepository.save(new ChatRoomMember(room.getId(), "alice", OffsetDateTime.now()));

		mockMvc.perform(post("/api/chat-rooms/" + room.getId() + "/leave").header("X-User-Id", "alice"))
				.andExpect(status().isOk());

		ChatRoom updated = chatRoomRepository.findById(room.getId()).orElseThrow();
		assertThat(updated.isClosed()).isTrue();
	}

	@Test
	void leavingWithOtherActiveMembersDoesNotCloseRoom() throws Exception {
		ChatRoom room = chatRoomRepository.save(new ChatRoom("study", "alice", OffsetDateTime.now()));
		chatRoomMemberRepository.save(new ChatRoomMember(room.getId(), "alice", OffsetDateTime.now()));
		chatRoomMemberRepository.save(new ChatRoomMember(room.getId(), "bob", OffsetDateTime.now()));

		mockMvc.perform(post("/api/chat-rooms/" + room.getId() + "/leave").header("X-User-Id", "alice"))
				.andExpect(status().isOk());

		ChatRoom updated = chatRoomRepository.findById(room.getId()).orElseThrow();
		assertThat(updated.isClosed()).isFalse();
	}

	@Test
	void concurrentLastTwoLeavesCloseRoomExactlyOnce() throws Exception {
		ChatRoom room = chatRoomRepository.save(new ChatRoom("study", "alice", OffsetDateTime.now()));
		chatRoomMemberRepository.save(new ChatRoomMember(room.getId(), "alice", OffsetDateTime.now()));
		chatRoomMemberRepository.save(new ChatRoomMember(room.getId(), "bob", OffsetDateTime.now()));

		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);

		Callable<Void> leaveAlice = () -> {
			ready.countDown();
			start.await();
			chatRoomService.leave(room.getId(), "alice");
			return null;
		};
		Callable<Void> leaveBob = () -> {
			ready.countDown();
			start.await();
			chatRoomService.leave(room.getId(), "bob");
			return null;
		};

		Future<Void> f1 = executor.submit(leaveAlice);
		Future<Void> f2 = executor.submit(leaveBob);
		ready.await();
		start.countDown();
		f1.get();
		f2.get();
		executor.shutdown();

		ChatRoom updated = chatRoomRepository.findById(room.getId()).orElseThrow();
		assertThat(updated.isClosed()).isTrue();
		assertThat(chatRoomMemberRepository.countByChatRoomIdAndLeftAtIsNull(room.getId())).isZero();
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd back && ./gradlew test --tests "com.chatserver.chatroom.ChatRoomControllerLeaveTest"`
Expected: FAIL — 컴파일 에러 (`leave` 없음).

- [ ] **Step 3: Extend ChatRoomMemberRepository**

Modify `back/src/main/java/com/chatserver/chatroom/ChatRoomMemberRepository.java` (full replacement):

```java
package com.chatserver.chatroom;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRoomMemberRepository extends JpaRepository<ChatRoomMember, Long> {

	Optional<ChatRoomMember> findByChatRoomIdAndUserIdAndLeftAtIsNull(Long chatRoomId, String userId);

	long countByChatRoomIdAndLeftAtIsNull(Long chatRoomId);
}
```

- [ ] **Step 4: Add leave() to ChatRoomService**

Modify `back/src/main/java/com/chatserver/chatroom/ChatRoomService.java` — add this method:

```java
	@Transactional
	public void leave(Long roomId, String userId) {
		ChatRoom room = chatRoomRepository.findByIdForUpdate(roomId)
				.orElseThrow(() -> new ChatRoomNotFoundException(roomId));
		Optional<ChatRoomMember> active = chatRoomMemberRepository
				.findByChatRoomIdAndUserIdAndLeftAtIsNull(roomId, userId);
		if (active.isEmpty()) {
			return;
		}
		active.get().leave(OffsetDateTime.now());
		chatRoomMemberRepository.save(active.get());

		long remaining = chatRoomMemberRepository.countByChatRoomIdAndLeftAtIsNull(roomId);
		if (remaining == 0) {
			room.close(OffsetDateTime.now());
			chatRoomRepository.save(room);
		}
	}
```

Add import: `import java.util.Optional;`

- [ ] **Step 5: Add leave endpoint to ChatRoomController**

Modify `back/src/main/java/com/chatserver/chatroom/ChatRoomController.java` — add this method:

```java
	@PostMapping("/{roomId}/leave")
	public ResponseEntity<Void> leave(@PathVariable Long roomId, Principal principal) {
		chatRoomService.leave(roomId, principal.getName());
		return ResponseEntity.ok().build();
	}
```

- [ ] **Step 6: Run tests to verify they pass**

Precondition: `docker compose up -d`.

Run: `cd back && ./gradlew test --tests "com.chatserver.chatroom.ChatRoomControllerLeaveTest"`
Expected: PASS (6 tests). Then `./gradlew test` for the full suite.

- [ ] **Step 7: Commit**

```bash
cd back
git add src/main/java/com/chatserver/chatroom src/test/java/com/chatserver/chatroom/ChatRoomControllerLeaveTest.java
git commit -m "feat: add chat room leave API that closes room when last member leaves"
```

---

### Task 6: Chat Room List API

**Files:**
- Create: `back/src/main/java/com/chatserver/chatroom/ChatRoomSummaryProjection.java`
- Create: `back/src/main/java/com/chatserver/chatroom/ChatRoomListCursor.java`
- Create: `back/src/main/java/com/chatserver/chatroom/dto/ChatRoomSummaryResponse.java`
- Create: `back/src/main/java/com/chatserver/chatroom/dto/ChatRoomListResponse.java`
- Create: `back/src/main/java/com/chatserver/chatroom/exception/InvalidCursorException.java`
- Modify: `back/src/main/java/com/chatserver/chatroom/ChatRoomRepository.java`
- Modify: `back/src/main/java/com/chatserver/chatroom/ChatRoomService.java`
- Modify: `back/src/main/java/com/chatserver/chatroom/ChatRoomController.java`
- Modify: `back/src/main/java/com/chatserver/common/GlobalExceptionHandler.java`
- Test: `back/src/test/java/com/chatserver/chatroom/ChatRoomControllerListTest.java`

**Interfaces:**
- Consumes: `ChatRoomRepository`, `ChatRoomMemberRepository`, `ChatRoomService` (Tasks 2-5).
- Produces:
  - `GET /api/chat-rooms?cursor=&limit=` → `200 { items: [{roomId,name,lastMessage,lastMessageAt}], nextCursor }`.
  - `ChatRoomService.listMyRooms(String userId, String cursor, int limit): ChatRoomListResponse`.

- [ ] **Step 1: Write the failing tests**

`back/src/test/java/com/chatserver/chatroom/ChatRoomControllerListTest.java`:

```java
package com.chatserver.chatroom;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class ChatRoomControllerListTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ChatRoomRepository chatRoomRepository;

	@Autowired
	private ChatRoomMemberRepository chatRoomMemberRepository;

	@AfterEach
	void cleanUp() {
		chatRoomMemberRepository.deleteAll();
		chatRoomRepository.deleteAll();
	}

	@Test
	void emptyListReturns200WithEmptyArray() throws Exception {
		mockMvc.perform(get("/api/chat-rooms").header("X-User-Id", "alice"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items").isArray())
				.andExpect(jsonPath("$.items").isEmpty())
				.andExpect(jsonPath("$.nextCursor").doesNotExist());
	}

	@Test
	void listsOnlyRoomsUserActivelyBelongsTo() throws Exception {
		ChatRoom roomA = chatRoomRepository.save(new ChatRoom("A", "alice", OffsetDateTime.now()));
		chatRoomMemberRepository.save(new ChatRoomMember(roomA.getId(), "alice", OffsetDateTime.now()));

		ChatRoom roomB = chatRoomRepository.save(new ChatRoom("B", "bob", OffsetDateTime.now()));
		chatRoomMemberRepository.save(new ChatRoomMember(roomB.getId(), "bob", OffsetDateTime.now()));

		mockMvc.perform(get("/api/chat-rooms").header("X-User-Id", "alice"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].name").value("A"))
				.andExpect(jsonPath("$.items[0].lastMessage").doesNotExist());
	}

	@Test
	void sortsByCreatedAtDescWhenNoMessages() throws Exception {
		ChatRoom older = chatRoomRepository.save(
				new ChatRoom("older", "alice", OffsetDateTime.now().minusMinutes(10)));
		chatRoomMemberRepository.save(new ChatRoomMember(older.getId(), "alice", OffsetDateTime.now()));
		ChatRoom newer = chatRoomRepository.save(new ChatRoom("newer", "alice", OffsetDateTime.now()));
		chatRoomMemberRepository.save(new ChatRoomMember(newer.getId(), "alice", OffsetDateTime.now()));

		mockMvc.perform(get("/api/chat-rooms").header("X-User-Id", "alice"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].name").value("newer"))
				.andExpect(jsonPath("$.items[1].name").value("older"));
	}

	@Test
	void paginatesWithCursor() throws Exception {
		for (int i = 0; i < 3; i++) {
			ChatRoom room = chatRoomRepository.save(
					new ChatRoom("room-" + i, "alice", OffsetDateTime.now().minusMinutes(3 - i)));
			chatRoomMemberRepository.save(new ChatRoomMember(room.getId(), "alice", OffsetDateTime.now()));
		}

		MvcResult firstPage = mockMvc.perform(get("/api/chat-rooms?limit=2").header("X-User-Id", "alice"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.nextCursor").exists())
				.andReturn();

		String body = firstPage.getResponse().getContentAsString();
		String nextCursor = JsonPath.read(body, "$.nextCursor");

		mockMvc.perform(get("/api/chat-rooms?limit=2&cursor=" + nextCursor).header("X-User-Id", "alice"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.nextCursor").doesNotExist());
	}

	@Test
	void limitOverMaxReturns400() throws Exception {
		mockMvc.perform(get("/api/chat-rooms?limit=101").header("X-User-Id", "alice"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void invalidCursorReturns400() throws Exception {
		mockMvc.perform(get("/api/chat-rooms?cursor=not-a-valid-cursor").header("X-User-Id", "alice"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd back && ./gradlew test --tests "com.chatserver.chatroom.ChatRoomControllerListTest"`
Expected: FAIL — 컴파일 에러 (list 엔드포인트 없음).

- [ ] **Step 3: Write projection, cursor codec, DTOs, exception**

`back/src/main/java/com/chatserver/chatroom/ChatRoomSummaryProjection.java`:

```java
package com.chatserver.chatroom;

import java.time.OffsetDateTime;

public interface ChatRoomSummaryProjection {

	Long getId();

	String getName();

	OffsetDateTime getLastMessageAt();

	OffsetDateTime getSortAt();
}
```

`back/src/main/java/com/chatserver/chatroom/ChatRoomListCursor.java`:

```java
package com.chatserver.chatroom;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;

import com.chatserver.chatroom.exception.InvalidCursorException;

public final class ChatRoomListCursor {

	public record Cursor(OffsetDateTime sortAt, Long roomId) {
	}

	private ChatRoomListCursor() {
	}

	public static String encode(OffsetDateTime sortAt, Long roomId) {
		String raw = sortAt.toInstant().toEpochMilli() + ":" + roomId;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	public static Cursor decode(String cursor) {
		try {
			String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			String[] parts = raw.split(":", 2);
			OffsetDateTime sortAt = Instant.ofEpochMilli(Long.parseLong(parts[0])).atOffset(ZoneOffset.UTC);
			Long roomId = Long.parseLong(parts[1]);
			return new Cursor(sortAt, roomId);
		} catch (RuntimeException e) {
			throw new InvalidCursorException(cursor);
		}
	}
}
```

`back/src/main/java/com/chatserver/chatroom/exception/InvalidCursorException.java`:

```java
package com.chatserver.chatroom.exception;

import com.chatserver.common.ApplicationException;

public class InvalidCursorException extends ApplicationException {

	public InvalidCursorException(String cursor) {
		super("INVALID_CURSOR", "Invalid cursor: " + cursor);
	}
}
```

`back/src/main/java/com/chatserver/chatroom/dto/ChatRoomSummaryResponse.java`:

```java
package com.chatserver.chatroom.dto;

import java.time.OffsetDateTime;

public record ChatRoomSummaryResponse(
		Long roomId,
		String name,
		String lastMessage,
		OffsetDateTime lastMessageAt
) {
}
```

`back/src/main/java/com/chatserver/chatroom/dto/ChatRoomListResponse.java`:

```java
package com.chatserver.chatroom.dto;

import java.util.List;

public record ChatRoomListResponse(
		List<ChatRoomSummaryResponse> items,
		String nextCursor
) {
}
```

- [ ] **Step 4: Add keyset queries to ChatRoomRepository**

Modify `back/src/main/java/com/chatserver/chatroom/ChatRoomRepository.java` (full replacement):

```java
package com.chatserver.chatroom;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from ChatRoom r where r.id = :id")
	Optional<ChatRoom> findByIdForUpdate(@Param("id") Long id);

	@Query(value = """
			SELECT r.id AS id, r.name AS name, r.last_message_at AS lastMessageAt,
			       COALESCE(r.last_message_at, r.created_at) AS sortAt
			FROM chat_room_member m
			JOIN chat_room r ON r.id = m.chat_room_id
			WHERE m.user_id = :userId
			  AND m.left_at IS NULL
			ORDER BY COALESCE(r.last_message_at, r.created_at) DESC, r.id DESC
			LIMIT :limitPlusOne
			""", nativeQuery = true)
	List<ChatRoomSummaryProjection> findFirstPage(@Param("userId") String userId,
			@Param("limitPlusOne") int limitPlusOne);

	@Query(value = """
			SELECT r.id AS id, r.name AS name, r.last_message_at AS lastMessageAt,
			       COALESCE(r.last_message_at, r.created_at) AS sortAt
			FROM chat_room_member m
			JOIN chat_room r ON r.id = m.chat_room_id
			WHERE m.user_id = :userId
			  AND m.left_at IS NULL
			  AND (COALESCE(r.last_message_at, r.created_at), r.id) < (:cursorSortAt, :cursorRoomId)
			ORDER BY COALESCE(r.last_message_at, r.created_at) DESC, r.id DESC
			LIMIT :limitPlusOne
			""", nativeQuery = true)
	List<ChatRoomSummaryProjection> findNextPage(@Param("userId") String userId,
			@Param("cursorSortAt") OffsetDateTime cursorSortAt,
			@Param("cursorRoomId") Long cursorRoomId,
			@Param("limitPlusOne") int limitPlusOne);
}
```

- [ ] **Step 5: Add listMyRooms() to ChatRoomService**

Modify `back/src/main/java/com/chatserver/chatroom/ChatRoomService.java` — add this method:

```java
	@Transactional(readOnly = true)
	public ChatRoomListResponse listMyRooms(String userId, String cursor, int limit) {
		int limitPlusOne = limit + 1;
		List<ChatRoomSummaryProjection> rows = (cursor == null)
				? chatRoomRepository.findFirstPage(userId, limitPlusOne)
				: queryNextPage(userId, cursor, limitPlusOne);

		boolean hasMore = rows.size() > limit;
		List<ChatRoomSummaryProjection> page = hasMore ? rows.subList(0, limit) : rows;

		String nextCursor = hasMore
				? ChatRoomListCursor.encode(
						page.get(page.size() - 1).getSortAt(), page.get(page.size() - 1).getId())
				: null;

		List<ChatRoomSummaryResponse> items = page.stream()
				.map(r -> new ChatRoomSummaryResponse(r.getId(), r.getName(), null, r.getLastMessageAt()))
				.toList();

		return new ChatRoomListResponse(items, nextCursor);
	}

	private List<ChatRoomSummaryProjection> queryNextPage(String userId, String cursor, int limitPlusOne) {
		ChatRoomListCursor.Cursor decoded = ChatRoomListCursor.decode(cursor);
		return chatRoomRepository.findNextPage(userId, decoded.sortAt(), decoded.roomId(), limitPlusOne);
	}
```

Add imports at the top of `ChatRoomService.java`:

```java
import java.util.List;

import com.chatserver.chatroom.dto.ChatRoomListResponse;
import com.chatserver.chatroom.dto.ChatRoomSummaryResponse;
```

- [ ] **Step 6: Add list endpoint to ChatRoomController**

Modify `back/src/main/java/com/chatserver/chatroom/ChatRoomController.java` — add `@Validated` on the class and this method:

```java
@Validated
@RestController
@RequestMapping("/api/chat-rooms")
public class ChatRoomController {
```

```java
	@GetMapping
	public ResponseEntity<ChatRoomListResponse> list(
			@RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
			Principal principal) {
		return ResponseEntity.ok(chatRoomService.listMyRooms(principal.getName(), cursor, limit));
	}
```

Add imports:

```java
import com.chatserver.chatroom.dto.ChatRoomListResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
```

- [ ] **Step 7: Extend GlobalExceptionHandler**

Modify `back/src/main/java/com/chatserver/common/GlobalExceptionHandler.java` (full replacement):

```java
package com.chatserver.common;

import com.chatserver.chatroom.exception.ChatRoomClosedException;
import com.chatserver.chatroom.exception.ChatRoomNotFoundException;
import com.chatserver.chatroom.exception.InvalidCursorException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
		String details = ex.getBindingResult().getFieldErrors().stream()
				.map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
				.reduce((a, b) -> a + "; " + b)
				.orElse("Invalid request");
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new ErrorResponse("VALIDATION_FAILED", details, null));
	}

	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new ErrorResponse("VALIDATION_FAILED", ex.getMessage(), null));
	}

	@ExceptionHandler(InvalidCursorException.class)
	public ResponseEntity<ErrorResponse> handleInvalidCursor(InvalidCursorException ex) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new ErrorResponse(ex.getErrorCode(), ex.getMessage(), null));
	}

	@ExceptionHandler(ChatRoomNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleNotFound(ChatRoomNotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new ErrorResponse(ex.getErrorCode(), ex.getMessage(), null));
	}

	@ExceptionHandler(ChatRoomClosedException.class)
	public ResponseEntity<ErrorResponse> handleClosed(ChatRoomClosedException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(new ErrorResponse(ex.getErrorCode(), ex.getMessage(), null));
	}
}
```

- [ ] **Step 8: Run tests to verify they pass**

Precondition: `docker compose up -d`.

Run: `cd back && ./gradlew test --tests "com.chatserver.chatroom.ChatRoomControllerListTest"`
Expected: PASS (7 tests). Then run the full suite: `./gradlew test` — all tests across Tasks 1-6 should pass.

- [ ] **Step 9: Commit**

```bash
cd back
git add src/main/java/com/chatserver/chatroom src/main/java/com/chatserver/common src/test/java/com/chatserver/chatroom/ChatRoomControllerListTest.java
git commit -m "feat: add chat room list API with keyset pagination"
```

---

## Done Criteria for Plan A

- `./gradlew build` passes (compiles + all tests green) with `docker compose up -d` running.
- `docs/api-design.md`의 체크리스트 중 아래 항목 충족:
  - ChatRoom 생성 + creator Membership 원자성.
  - 동시 Join에서 active membership 1개.
  - 마지막 멤버 동시 Leave에서 closedAt 정확히 한 번.
  - closed room Join → 409.
- 다음 플랜(메시지 송수신, Plan B)이 바로 이어받을 수 있는 상태: `ChatRoomRepository`, `ChatRoomMemberRepository`, `ChatRoomService`, 인증 `Principal` 계약이 안정적으로 존재.

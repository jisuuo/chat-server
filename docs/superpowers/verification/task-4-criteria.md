# Task 4 완료조건 (Chat Room Join API)

- [x] 1. `ChatRoomRepository.findByIdForUpdate(Long id): Optional<ChatRoom>`가 존재하며 `@Lock(LockModeType.PESSIMISTIC_WRITE)` + `select r from ChatRoom r where r.id = :id` JPQL로 정의되어 있다 (`ChatRoomRepository.java:14-16`).
- [x] 2. `ChatRoomMemberRepository.findByChatRoomIdAndUserIdAndLeftAtIsNull(Long, String): Optional<ChatRoomMember>`가 존재한다 (`ChatRoomMemberRepository.java:9`).
- [x] 3. `ChatRoomNotFoundException`(`CHAT_ROOM_NOT_FOUND`)과 `ChatRoomClosedException`(`CHAT_ROOM_CLOSED`)이 `ApplicationException`을 상속하며 각각 존재한다.
- [x] 4. `ChatRoomService.join(Long roomId, String userId)`는 `@Transactional`이며, `findByIdForUpdate`로 room row 락을 먼저 획득한 뒤 존재 여부·closed 여부를 확인한다 (`ChatRoomService.java:36-50`).
- [x] 5. 존재하지 않는 room에 join 시 `ChatRoomNotFoundException` 발생 → 404.
- [x] 6. closed room에 join 시 `ChatRoomClosedException` 발생 → 409.
- [x] 7. 이미 active membership이 있는 사용자가 다시 join하면 새 row를 만들지 않고 조용히 성공 반환한다 (멱등).
- [x] 8. active membership이 없으면 새 `ChatRoomMember` row를 insert한다.
- [x] 9. `POST /api/chat-rooms/{roomId}/join`은 request body 없이 `Principal.getName()`으로만 userId를 받고, 성공 시 200을 반환한다 (`ChatRoomController.java:41-45`).
- [x] 10. `GlobalExceptionHandler`가 `ChatRoomNotFoundException→404`, `ChatRoomClosedException→409`로 매핑하고, 바디는 `{code, message, details:null}` 형태다.
- [x] 11. `ChatRoomControllerJoinTest` 4개 테스트 통과: `joiningNonExistentRoomReturns404`, `joiningClosedRoomReturns409`, `duplicateJoinSucceedsIdempotently`, `concurrentJoinsLeaveExactlyOneActiveMembership`(10-thread 동시 join → active membership 정확히 1개). 커밋 `32b3f60`, task reviewer 승인(Critical/Important 없음).

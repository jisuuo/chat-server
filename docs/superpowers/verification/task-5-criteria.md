# Task 5 완료조건 (Chat Room Leave API)

- [x] 1. `ChatRoomMemberRepository.countByChatRoomIdAndLeftAtIsNull(Long roomId): long`이 존재한다 (`ChatRoomMemberRepository.java:11`).
- [x] 2. `ChatRoomService.leave(Long roomId, String userId)`는 `@Transactional`이며, `findByIdForUpdate`로 join과 동일한 room row 락을 먼저 획득한다 (`ChatRoomService.java:52-69`) — join/leave가 같은 락으로 직렬화됨.
- [x] 3. 존재하지 않는 room에 leave 시 `ChatRoomNotFoundException` 발생 → 404.
- [x] 4. active membership이 없는 사용자(비멤버 또는 이미 퇴장)가 leave하면 아무 상태 변경 없이 조용히 성공 반환한다 (멱등, 200).
- [x] 5. active membership이 있으면 `leftAt`을 기록(`member.leave(now)`)하고 저장한다.
- [x] 6. `leave` 처리 후 `countByChatRoomIdAndLeftAtIsNull == 0`이면 `room.close(now)`로 `closedAt`을 기록하고 저장한다.
- [x] 7. 남은 active 멤버가 있으면 방은 닫히지 않는다.
- [x] 8. `POST /api/chat-rooms/{roomId}/leave`는 `Principal.getName()`으로 userId를 받고 성공 시 200을 반환한다 (`ChatRoomController.java:47-51`).
- [x] 9. 동시에 마지막 두 멤버가 leave해도 room row 락 덕분에 `closedAt`이 정확히 한 번만 기록된다 (두 트랜잭션이 락으로 직렬화되어 두 번째 트랜잭션의 count 조회가 첫 번째 트랜잭션의 커밋 이후에 일어남).
- [x] 10. `ChatRoomControllerLeaveTest` 6개 테스트 통과: `leavingNonExistentRoomReturns404`, `leavingWithoutActiveMembershipSucceedsIdempotently`, `duplicateLeaveSucceedsIdempotently`, `lastMemberLeavingClosesRoom`, `leavingWithOtherActiveMembersDoesNotCloseRoom`, `concurrentLastTwoLeavesCloseRoomExactlyOnce`(2-thread 동시 leave → closed 정확히 한 번 + active count 0). 커밋 `40dd1b1`, task reviewer 승인(Critical/Important 없음).

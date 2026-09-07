# Task 6 완료조건 (Chat Room List API)

- [x] 1. `ChatRoomSummaryProjection`(id/name/lastMessageAt/sortAt), `ChatRoomListCursor`(encode/decode), `ChatRoomSummaryResponse`, `ChatRoomListResponse`, `InvalidCursorException`(`INVALID_CURSOR`)이 브리프대로 존재한다.
- [x] 2. `ChatRoomRepository.findFirstPage`/`findNextPage`가 native query로 `chat_room_member ⋈ chat_room`을 `user_id`+`left_at IS NULL` 조건, `COALESCE(last_message_at, created_at) DESC, id DESC` 정렬로 조회하며, 파라미터가 전부 바인딩(`:userId`, `:cursorSortAt`, `:cursorRoomId`, `:limitPlusOne`)되어 SQL 인젝션 여지가 없다.
- [x] 3. keyset 비교 `(sortAt, id) < (cursorSortAt, cursorId)`가 `ORDER BY sortAt DESC, id DESC`와 방향이 일치한다 (다음 페이지 = 커서보다 작은 행).
- [x] 4. `ChatRoomService.listMyRooms(userId, cursor, limit)`은 `limit+1`개 조회 후 `hasMore` 판단, 다음 커서는 마지막 항목의 `sortAt`+`id`로 인코딩한다.
- [x] 5. `GET /api/chat-rooms?cursor=&limit=`은 `Principal.getName()`만으로 userId를 받고, `limit` 기본 20/최대 100(`@Min(1) @Max(100)`, 초과 시 400), `cursor` 없으면 첫 페이지를 반환한다.
- [x] 6. 잘못된 cursor 문자열은 500이 아니라 `InvalidCursorException`→400 `INVALID_CURSOR`로 처리된다 (`ChatRoomListCursor.decode`의 모든 파싱 실패가 `RuntimeException`으로 catch되어 변환됨).
- [x] 7. 목록 응답의 `lastMessage` 필드는 이 플랜에서 항상 `null`이다 (Message 엔티티 없음 — 확정된 가정).
- [x] 8. `ChatRoomControllerListTest` 6개 테스트 통과: `emptyListReturns200WithEmptyArray`, `listsOnlyRoomsUserActivelyBelongsTo`, `sortsByCreatedAtDescWhenNoMessages`, `paginatesWithCursor`, `limitOverMaxReturns400`, `invalidCursorReturns400`.
- [x] 9. **(fix round 1)** cursor 인코딩이 마이크로초/나노초 정밀도를 보존한다 — 최초 구현은 `toEpochMilli()`로 밀리초까지만 인코딩해 같은 밀리초에 여러 방이 생성되면 일부 방이 페이지네이션에서 영구 누락되는 silent data-loss 버그가 있었음. `epochSecond:nano:roomId` 형식으로 수정, encode→decode가 원래 `Instant`를 정확히 복원함을 재리뷰로 확인 (`ChatRoomListCursor.java:19-35`). 커밋 `3d4df58`.
- [x] 10. Task reviewer 최초 리뷰: Important 1건(위 9번) 외 승인. Fix round 1 재리뷰: 해당 finding ADDRESSED, 새 breakage 없음 → 최종 승인. 커밋 `18ee8f3`(구현)+`3d4df58`(fix).

## Deferred Minor (병합 전 필수 아님, ledger에 기록됨)

- 숫자가 아닌 `limit` 쿼리 파라미터(`?limit=abc`)는 `MethodArgumentTypeMismatchException`이 `GlobalExceptionHandler`에 핸들러 없어 Spring 기본 에러 바디로 나감 (`{code,message,details}` 형태 아님).
- `ConstraintViolationException` 핸들러 메시지 포맷이 `handleValidation`의 필드 추출 포맷과 코스메틱하게 다름.

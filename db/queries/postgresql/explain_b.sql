-- 앱(JdbcMessageRepository, JdbcRoomRepository)과 같은 SQL의 실행 계획. 앱 SQL이 바뀌면 함께 고친다.
-- 인기 방 1과 나머지 구간 방 5000을 본다. 조회 크기는 앱처럼 size+1(=51, 방 목록은 21)이다. 경계는 id 기준(기본값)이다
SELECT COALESCE(MAX(id), 0) - 10 AS hot_after,
       COALESCE(MIN(id) + (MAX(id) - MIN(id)) / 2, 0) AS hot_before
FROM messages_b WHERE room_id = 1 \gset

-- W2 최근 메시지 (인기 방)
EXPLAIN (ANALYZE, BUFFERS) SELECT id, room_id, sender_id, content, created_at FROM messages_b
WHERE room_id = 1 AND id > 0 ORDER BY id DESC LIMIT 51;
-- W2 최근 메시지 (나머지 구간 방)
EXPLAIN (ANALYZE, BUFFERS) SELECT id, room_id, sender_id, content, created_at FROM messages_b
WHERE room_id = 5000 AND id > 0 ORDER BY id DESC LIMIT 51;
-- W3 과거 스크롤 (before 커서, 인기 방의 가운데)
EXPLAIN (ANALYZE, BUFFERS) SELECT id, room_id, sender_id, content, created_at FROM messages_b
WHERE room_id = 1 AND id > 0 AND id < :hot_before ORDER BY id DESC LIMIT 51;
-- W4 폴링 (after 커서, 최근 10건 뒤)
EXPLAIN (ANALYZE, BUFFERS) SELECT id, room_id, sender_id, content, created_at FROM messages_b
WHERE room_id = 1 AND id > 0 AND id > :hot_after ORDER BY id ASC LIMIT 51;
-- 방 목록 첫 페이지 (rooms에는 정렬용 인덱스가 없다, F20)
EXPLAIN (ANALYZE, BUFFERS) SELECT id, name, created_by, last_message_id, created_at FROM rooms
ORDER BY (last_message_id IS NULL), last_message_id DESC, id DESC LIMIT 21;

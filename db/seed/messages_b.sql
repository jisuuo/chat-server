-- ADR-041: 한 DB에는 한 메시지 스키마만 채운다. base.sql 다음에 실행한다.
INSERT INTO messages_b (room_id, sender_id, content, created_at)
SELECT r.id, u.id, v.content, v.created_at
FROM (
    SELECT '잡담방' AS room, '철수' AS sender, '잡담 1' AS content, TIMESTAMP '2026-10-01 10:00:01' AS created_at
    UNION ALL SELECT '잡담방', '영희', '잡담 2', TIMESTAMP '2026-10-01 10:00:02'
    UNION ALL SELECT '잡담방', '민수', '잡담 3', TIMESTAMP '2026-10-01 10:00:03'
    UNION ALL SELECT '잡담방', '철수', '잡담 4', TIMESTAMP '2026-10-01 10:00:04'
    UNION ALL SELECT '잡담방', '영희', '잡담 5', TIMESTAMP '2026-10-01 10:00:05'
    UNION ALL SELECT '스터디', '영희', '스터디 1', TIMESTAMP '2026-10-01 11:00:01'
    UNION ALL SELECT '스터디', '철수', '스터디 2', TIMESTAMP '2026-10-01 11:00:02'
) v
JOIN rooms r ON r.name = v.room
JOIN users u ON u.nickname = v.sender
ORDER BY v.created_at;

-- ADR-008: 재입장 때 그 시점의 마지막 메시지를 경계로 삼는다.
-- I7: id 기준과 시각 기준이 같은 결과를 내도록 같은 메시지의 시각을 쓴다.
UPDATE room_members
SET joined_message_id = (SELECT id FROM messages_b WHERE content = '잡담 3'),
    joined_at = (SELECT created_at FROM messages_b WHERE content = '잡담 3')
WHERE room_id = (SELECT id FROM rooms WHERE name = '잡담방')
  AND user_id = (SELECT id FROM users WHERE nickname = '민수');

-- ADR-016: 방 목록의 최근 메시지 정렬값을 실제 메시지와 맞춘다 (I1).
UPDATE rooms SET last_message_id = (SELECT MAX(m.id) FROM messages_b m WHERE m.room_id = rooms.id);

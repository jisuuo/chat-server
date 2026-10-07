-- ADR-041: 스키마 전환 때 DB를 초기화하므로 메시지 테이블 한쪽만 채워진다.
WITH all_messages AS (
    SELECT id, room_id, sender_id, content, created_at FROM messages
    UNION ALL
    SELECT id, room_id, sender_id, content, created_at FROM messages_b
)
SELECT 'I1', COUNT(*) FROM rooms r
    LEFT JOIN (SELECT room_id, MAX(id) AS max_id FROM all_messages GROUP BY room_id) m ON m.room_id = r.id
    WHERE (r.last_message_id IS NULL AND m.max_id IS NOT NULL)
       OR (r.last_message_id IS NOT NULL AND (m.max_id IS NULL OR r.last_message_id <> m.max_id))
UNION ALL
SELECT 'I2', COUNT(*) FROM all_messages m
    LEFT JOIN room_members rm ON rm.room_id = m.room_id AND rm.user_id = m.sender_id
    WHERE rm.user_id IS NULL
UNION ALL
SELECT 'I3', COUNT(*) FROM room_members rm JOIN rooms r ON r.id = rm.room_id
    WHERE rm.joined_message_id > COALESCE(r.last_message_id, 0) OR rm.joined_at < r.created_at
UNION ALL
SELECT 'I4', COUNT(*) FROM (
        SELECT created_at, LAG(created_at) OVER (PARTITION BY room_id ORDER BY id) AS prev FROM all_messages
    ) t WHERE t.prev > t.created_at
UNION ALL
SELECT 'I5', (SELECT COUNT(*) = 0 FROM users) + (SELECT COUNT(*) = 0 FROM rooms) + (SELECT COUNT(*) = 0 FROM room_members)
UNION ALL
-- 다중 바이트 문자와 CHAR(0)를 문자열로 비교하면 MySQL에서 거짓 양성이 나므로 바이트로 비교한다.
SELECT 'I6', (SELECT COUNT(*) FROM users WHERE CHAR_LENGTH(nickname) NOT BETWEEN 1 AND 50 OR nickname REGEXP '[[:cntrl:]]')
           + (SELECT COUNT(*) FROM rooms WHERE CHAR_LENGTH(name) NOT BETWEEN 1 AND 50 OR name REGEXP '[[:cntrl:]]')
           + (SELECT COUNT(*) FROM all_messages WHERE CHAR_LENGTH(content) NOT BETWEEN 1 AND 1000 OR INSTR(CAST(content AS BINARY), X'00') > 0)
UNION ALL
-- 재입장 멤버만 검사하므로 경계가 모두 0인 bulk에서는 이 조인이 비어 있다.
SELECT 'I7', COUNT(*) FROM room_members rm JOIN all_messages m ON m.room_id = rm.room_id
    WHERE rm.joined_message_id > 0
      AND (m.id > rm.joined_message_id) <> (m.created_at > rm.joined_at);

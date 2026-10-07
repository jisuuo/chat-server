-- bulk의 구간별 메시지 비율(세부 #6: 인기 50%, 중간 30%, 나머지 20%)을 확인한다
WITH all_messages AS (
    SELECT room_id FROM messages
    UNION ALL
    SELECT room_id FROM messages_b
)
SELECT tier, COUNT(*) AS messages, ROUND(100.0 * COUNT(*) / (SELECT COUNT(*) FROM all_messages), 2) AS percent
FROM (SELECT CASE WHEN room_id <= 100 THEN 'hot' WHEN room_id <= 1000 THEN 'warm' ELSE 'cold' END AS tier
      FROM all_messages) t
GROUP BY tier
ORDER BY tier;

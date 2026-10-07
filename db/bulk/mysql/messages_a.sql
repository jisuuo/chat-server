-- 스키마 A(messages)에 메시지 @messages건을 넣는다. 실행: mysql --init-command="SET @messages=500000" … < messages_a.sql
-- base.sql 다음에 실행한다. 최대 9,999,999건 (7자리)
-- 메시지 k의 구간은 k mod 100으로 정해 비율이 정확히 50/30/20이 되게 하고, 구간 안에서는 방을 차례로 돌아가며 고른다 (세부 #6)
-- 자릿수마다 "d * 10^i < @messages" 조건을 걸어, 작은 @messages에서 1천만 조합을 만들지 않게 한다
INSERT INTO messages (room_id, sender_id, content, created_at)
WITH digits AS (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
                UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9),
     seq AS (SELECT d1.d + d2.d * 10 + d3.d * 100 + d4.d * 1000 + d5.d * 10000 + d6.d * 100000 + d7.d * 1000000 + 1 AS k
             FROM digits d1 CROSS JOIN digits d2 CROSS JOIN digits d3 CROSS JOIN digits d4
                  CROSS JOIN digits d5 CROSS JOIN digits d6 CROSS JOIN digits d7
             WHERE d2.d * 10 < @messages AND d3.d * 100 < @messages AND d4.d * 1000 < @messages
               AND d5.d * 10000 < @messages AND d6.d * 100000 < @messages AND d7.d * 1000000 < @messages),
     placed AS (SELECT k, MOD(k, 100) AS s, k DIV 100 AS q FROM seq WHERE k <= @messages),
     routed AS (SELECT k,
                       CASE WHEN s < 50 THEN 1 + MOD(q * 50 + s, 100)
                            WHEN s < 80 THEN 101 + MOD(q * 30 + s - 50, 900)
                            ELSE 1001 + MOD(q * 20 + s - 80, 9000) END AS room_id,
                       CASE WHEN s < 50 THEN 50 WHEN s < 80 THEN 20 ELSE 5 END AS members
                FROM placed)
SELECT room_id,
       MOD(room_id - 1 + MOD(k, members) * 97, 10000) + 1,
       RPAD(CONCAT('m', k, ' '), 20 + MOD(k, 181), 'x'),
       -- 30일(2,592,000,000,000마이크로초)을 메시지 수로 나눠 k 순서대로 늘린다 (I4)
       TIMESTAMPADD(MICROSECOND, k * (2592000000000 DIV @messages), TIMESTAMP '2026-09-08 00:00:00')
FROM routed
ORDER BY k;

UPDATE rooms SET last_message_id = (SELECT MAX(m.id) FROM messages m WHERE m.room_id = rooms.id);

-- 실행 계획(queries/explain_a.sql)이 방금 넣은 데이터 기준으로 나오게 통계를 갱신한다
ANALYZE TABLE rooms, room_members, messages;

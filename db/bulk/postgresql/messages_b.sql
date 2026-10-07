-- 스키마 B(messages_b)에 메시지 :messages건을 넣는다. 실행: psql -v ON_ERROR_STOP=1 -v messages=500000 -f messages_b.sql
-- base.sql 다음에 실행한다
-- 메시지 k의 구간은 k mod 100으로 정해 비율이 정확히 50/30/20이 되게 하고, 구간 안에서는 방을 차례로 돌아가며 고른다 (세부 #6)
INSERT INTO messages_b (room_id, sender_id, content, created_at)
SELECT room_id,
       MOD(room_id - 1 + MOD(k, members) * 97, 10000) + 1,
       RPAD(CONCAT('m', k, ' '), (20 + MOD(k, 181))::int, 'x'),
       -- 30일(2,592,000,000,000마이크로초)을 메시지 수로 나눠 k 순서대로 늘린다 (I4)
       TIMESTAMP '2026-09-08 00:00:00' + (k * (2592000000000 / :messages)) * INTERVAL '1 microsecond'
FROM (
    SELECT k,
           CASE WHEN s < 50 THEN 1 + MOD(q * 50 + s, 100)
                WHEN s < 80 THEN 101 + MOD(q * 30 + s - 50, 900)
                ELSE 1001 + MOD(q * 20 + s - 80, 9000) END AS room_id,
           CASE WHEN s < 50 THEN 50 WHEN s < 80 THEN 20 ELSE 5 END AS members
    FROM (SELECT k, MOD(k, 100) AS s, k / 100 AS q FROM generate_series(1::bigint, :messages) AS g(k)) placed
) routed
ORDER BY k;

UPDATE rooms SET last_message_id = (SELECT MAX(m.id) FROM messages_b m WHERE m.room_id = rooms.id);

-- 실행 계획(queries/explain_a.sql)이 방금 넣은 데이터 기준으로 나오게 통계를 갱신한다
ANALYZE rooms, room_members, messages_b;

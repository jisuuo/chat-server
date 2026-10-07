-- 부하 측정용 공통 데이터: 사용자 1만, 방 1만, 멤버 6만8천 (세부 #6). 빈 DB에서 실행한다 (ADR-041).
-- 방 구간은 id로 정한다: 1~100 인기(멤버 50), 101~1000 중간(멤버 20), 1001~10000 나머지(멤버 5).
-- 재귀 CTE는 cte_max_recursion_depth(기본 1000)에 걸리므로 숫자 0~9를 cross join해 만든다 (세부 #5)
INSERT INTO users (nickname, created_at)
WITH digits AS (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
                UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9),
     seq AS (SELECT d1.d + d2.d * 10 + d3.d * 100 + d4.d * 1000 + 1 AS n
             FROM digits d1 CROSS JOIN digits d2 CROSS JOIN digits d3 CROSS JOIN digits d4)
SELECT CONCAT('user', n), TIMESTAMP '2026-09-01 00:00:00' FROM seq ORDER BY n;

INSERT INTO rooms (name, created_by, created_at)
WITH digits AS (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
                UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9),
     seq AS (SELECT d1.d + d2.d * 10 + d3.d * 100 + d4.d * 1000 + 1 AS n
             FROM digits d1 CROSS JOIN digits d2 CROSS JOIN digits d3 CROSS JOIN digits d4)
SELECT CONCAT('room', n), n, TIMESTAMP '2026-09-01 00:00:00' FROM seq ORDER BY n;

-- 멤버 j번은 사용자 ((방id-1 + j*97) mod 10000) + 1. j=0이 생성자이고, j<50이면 97*j < 10000이라 겹치지 않는다
INSERT INTO room_members (room_id, user_id, joined_message_id, joined_at)
WITH digits AS (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
                UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9),
     slots AS (SELECT d1.d + d2.d * 10 AS j FROM digits d1 CROSS JOIN digits d2 WHERE d1.d + d2.d * 10 < 50)
SELECT r.id, MOD(r.id - 1 + s.j * 97, 10000) + 1, 0, r.created_at
FROM rooms r JOIN slots s
  ON s.j < CASE WHEN r.id <= 100 THEN 50 WHEN r.id <= 1000 THEN 20 ELSE 5 END;

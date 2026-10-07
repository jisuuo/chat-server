-- 부하 측정용 공통 데이터: 사용자 1만, 방 1만, 멤버 6만8천 (세부 #6). 빈 DB에서 실행한다 (ADR-041).
-- 방 구간은 id로 정한다: 1~100 인기(멤버 50), 101~1000 중간(멤버 20), 1001~10000 나머지(멤버 5)
INSERT INTO users (nickname, created_at)
SELECT CONCAT('user', n), TIMESTAMP '2026-09-01 00:00:00' FROM generate_series(1, 10000) AS g(n) ORDER BY n;

INSERT INTO rooms (name, created_by, created_at)
SELECT CONCAT('room', n), n, TIMESTAMP '2026-09-01 00:00:00' FROM generate_series(1, 10000) AS g(n) ORDER BY n;

-- 멤버 j번은 사용자 ((방id-1 + j*97) mod 10000) + 1. j=0이 생성자이고, j<50이면 97*j < 10000이라 겹치지 않는다
INSERT INTO room_members (room_id, user_id, joined_message_id, joined_at)
SELECT r.id, MOD(r.id - 1 + s.j * 97, 10000) + 1, 0, r.created_at
FROM rooms r JOIN generate_series(0, 49) AS s(j)
  ON s.j < CASE WHEN r.id <= 100 THEN 50 WHEN r.id <= 1000 THEN 20 ELSE 5 END;

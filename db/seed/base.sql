-- 두 DB가 같은 소량 샘플을 쓰도록 한 파일로 둔다 (세부 #8). 빈 DB에서 실행한다.
-- PostgreSQL GENERATED ALWAYS가 명시적 id를 거부하므로 이름으로 참조한다 (세부 #1).
-- PostgreSQL에서 UNION의 문자열은 text로 추론되므로 시각은 타입 지정 리터럴로 쓴다.
INSERT INTO users (nickname, created_at) VALUES
    ('철수', TIMESTAMP '2026-10-01 09:00:00'),
    ('영희', TIMESTAMP '2026-10-01 09:00:00'),
    ('민수', TIMESTAMP '2026-10-01 09:00:00');

INSERT INTO rooms (name, created_by, created_at)
SELECT '잡담방', id, TIMESTAMP '2026-10-01 09:10:00' FROM users WHERE nickname = '철수'
UNION ALL
SELECT '스터디', id, TIMESTAMP '2026-10-01 09:20:00' FROM users WHERE nickname = '영희'
UNION ALL
SELECT '빈 방', id, TIMESTAMP '2026-10-01 09:30:00' FROM users WHERE nickname = '민수';

-- 처음 입장한 멤버의 경계는 0, 입장 시각은 방 생성 시각이다 (R1).
INSERT INTO room_members (room_id, user_id, joined_message_id, joined_at)
SELECT r.id, u.id, 0, r.created_at
FROM rooms r JOIN users u
  ON (r.name = '잡담방' AND u.nickname IN ('철수', '영희', '민수'))
  OR (r.name = '스터디' AND u.nickname IN ('영희', '철수'))
  OR (r.name = '빈 방' AND u.nickname = '민수');

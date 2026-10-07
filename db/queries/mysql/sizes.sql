-- information_schema의 크기 값은 기본 86400초 동안 캐시되어, 방금 넣은 데이터가 0으로 보일 수 있다
SET SESSION information_schema_stats_expiry = 0;
ANALYZE TABLE users, rooms, room_members, messages, messages_b;

SELECT table_name, table_rows,
       ROUND(data_length / 1048576, 1) AS data_mb,
       ROUND(index_length / 1048576, 1) AS index_mb,
       ROUND((data_length + index_length) / 1048576, 1) AS total_mb
FROM information_schema.tables
WHERE table_schema = DATABASE()
ORDER BY data_length + index_length DESC;

-- 조건 C1(데이터가 메모리에 들어갈 때와 넘칠 때)의 기준인 버퍼 크기 (ADR-040)
SELECT ROUND(@@innodb_buffer_pool_size / 1048576) AS buffer_pool_mb;

-- 인덱스 목록. MySQL은 FK 컬럼(room_members.user_id)에 인덱스를 자동으로 만들고 PostgreSQL은 만들지 않는다 (2026-10-07 확인)
SELECT table_name, index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS columns
FROM information_schema.statistics
WHERE table_schema = DATABASE()
GROUP BY table_name, index_name
ORDER BY table_name, index_name;

SELECT relname AS table_name, n_live_tup AS live_rows,
       ROUND(pg_relation_size(relid) / 1048576.0, 1) AS data_mb,
       ROUND(pg_indexes_size(relid) / 1048576.0, 1) AS index_mb,
       ROUND(pg_total_relation_size(relid) / 1048576.0, 1) AS total_mb
FROM pg_stat_user_tables
ORDER BY pg_total_relation_size(relid) DESC;

-- 조건 C1(데이터가 메모리에 들어갈 때와 넘칠 때)의 기준인 버퍼 크기 (ADR-040)
SHOW shared_buffers;

-- 인덱스 목록. PostgreSQL은 FK 컬럼(room_members.user_id)에 인덱스를 자동으로 만들지 않는다 (2026-10-07 확인)
SELECT tablename, indexname, indexdef FROM pg_indexes WHERE schemaname = current_schema() ORDER BY tablename, indexname;

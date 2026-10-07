-- 통계 초기화(pg_stat_reset) 이후 누적값이다. 측정 구간의 적중률은 측정 전후 값의 차이로 계산한다
SELECT relname, heap_blks_read, heap_blks_hit, idx_blks_read, idx_blks_hit,
       ROUND(heap_blks_hit::numeric / NULLIF(heap_blks_hit + heap_blks_read, 0), 4) AS heap_hit_ratio,
       ROUND(idx_blks_hit::numeric / NULLIF(idx_blks_hit + idx_blks_read, 0), 4) AS idx_hit_ratio
FROM pg_statio_user_tables
ORDER BY relname;

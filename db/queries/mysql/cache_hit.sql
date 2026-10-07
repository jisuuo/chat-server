-- 서버 기동 이후 누적값이다. 측정 구간의 적중률은 측정 전후 값의 차이로 계산한다:
-- 적중률 = 1 - (reads 증가분 / read_requests 증가분). SHOW는 추가 권한 없이 실행된다
SHOW GLOBAL STATUS LIKE 'Innodb_buffer_pool_read%';

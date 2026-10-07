# 계획 5b 부하 실험

격리된 `chat-bench` Docker Compose 프로젝트에서만 데이터를 적재한다. `chat-db` 볼륨은 건드리지 않는다. 실행 환경·판정 규칙은 [계획 5b](../docs/superpowers/plans/2026-10-07-plan5b-load-db-comparison.md)와 ADR-101~104를 따른다.

```bash
# 저장소 루트에서, Java 21·Docker·k6 2.1.0·mysql·psql 필요
cd backend && ./gradlew bootJar && cd ..
mkdir -p load/artifacts
cp backend/build/libs/chat-0.0.1-SNAPSHOT.jar load/artifacts/chat-bench.jar
python3 load/run_baselines.py
python3 load/summarize.py official-mysql-a-500k-baseline official-mysql-b-500k-baseline official-postgres-a-500k-baseline official-mysql-a-5000k-baseline official-mysql-b-5000k-baseline official-postgres-a-5000k-baseline
```

`run_baselines.py --start N`은 N번째 구성부터 다시 시작한다. 각 구성은 `prepare.py --fresh`로 **벤치 프로젝트의** 볼륨만 새로 만들고, 15초 워밍업 뒤 W2·W3·W4·W5·W1을 각각 30초씩 3회 실행한다. 원시 k6 요약 JSON, 로그, DB·Hikari 전후 지표, Hikari 피크, 적재 manifest와 최종 메시지 수는 `load/results/<run-id>/`에 남는다. `load/results/`는 git에서 제외하므로 보고서에 필요한 원자료는 `docs/reports/`로 복사한다.

벤치 앱은 `load/artifacts/chat-bench.jar`의 고정 복사본을 읽는다. 측정 도중 다른 작업이 백엔드 코드를 편집하거나 `bootJar`를 실행해도 벤치 앱 바이트가 바뀌지 않게 하려는 것이다. 현재 공식 복사본의 SHA-256은 `ac826aeb7bb100383693fe224b3f19171f3b75c9cac38eb27989c14ed515f432`다.

개별 실험에는 `run_matrix.py --suite custom --case W4:200:0:0.5`처럼 워크로드·VU·인기 방 고정 여부·폴링 주기를 지정한다. 반복 조건의 값은 워밍업 전에 설정하고, 같은 ID의 결과 디렉터리를 재사용하지 않는다.

기준선 이후의 실행 스크립트는 `run_followups.py`(C2·C3·F1과 500만 건 F15), `run_sql_sizes.py`(50만 건 F15), `restart_probe.py`(C4), `run_index_probe.py`(F16), `run_hot_index.py`(F20), `delete_probe.py`(F19·테이블 크기)다. 재시작 실험만 `prepare.py --fresh`로 해당 구성을 준비한 뒤 실행하며, 다른 스크립트는 내부에서 새 벤치 볼륨을 준비한다. 전체 부하가 끝나면 `decide.py`로 사전 판정 규칙을 적용하고 `archive_results.py`로 공식 JSON·CSV를 보고서 옆에 복사한다. SQL 실험과 k6 부하는 같은 벤치 DB를 공유하므로 한 번에 하나씩 실행한다.

후속 실험이 끝난 다음 `python3 load/run_remaining.py`를 실행하면 50만 건 SQL, 대량 삭제, 인덱스, 재시작, 결과 아카이브를 순서대로 처리한다. 중간에 실패하면 원인을 수정한 뒤 출력된 단계 번호로 `--start N`을 지정해 재개한다. 각 단계는 해당 격리 볼륨을 새로 준비하므로 이전 공식 결과는 `load/results/`에 남는다.

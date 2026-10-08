# 서버 2대 클러스터

같은 MySQL을 쓰는 앱 2대와 라운드로빈 nginx를 별도 Docker Compose 프로젝트로 띄운다. 기본 `naive.conf`는 WebSocket 프록시 설정이 빠진 장애 재현 조건이다.

```bash
(cd backend && ./gradlew bootJar)
(cd frontend && npm run build)
docker compose -f infra/compose.cluster.yml up -d --wait
docker compose -f infra/compose.cluster.yml logs nginx
docker compose -f infra/compose.cluster.yml down
```

데이터까지 지울 때만 `docker compose -f infra/compose.cluster.yml down -v`를 사용한다.

| 서비스 | 호스트 포트 | 용도 |
|---|---:|---|
| nginx | 18090 | 화면, `/api`, `/ws` |
| app1 | 18081 | 직접 확인과 지표 |
| app2 | 18082 | 직접 확인과 지표 |
| MySQL | 33306 | 분리된 클러스터 데이터 |

`NGINX_CONF=naive`가 기본이며, 다른 설정 파일을 `infra/cluster/nginx/<이름>.conf`에 둔 뒤 `NGINX_CONF=<이름> docker compose -f infra/compose.cluster.yml up -d --force-recreate --wait nginx`로 교체한다. `CLUSTER_PROFILE=local`이 기본이고 재연결 부하 측정에는 `CLUSTER_PROFILE=bench`를 지정한다. 두 변수는 설정을 바꿀 때의 `up` 명령에도 다시 지정해야 한다.

앱 로그는 각각 `backend/logs/cluster/app1`, `backend/logs/cluster/app2`에 쌓인다. nginx 로그는 위 `logs nginx` 명령으로 본다.

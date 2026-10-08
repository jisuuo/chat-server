# 서버 2대 클러스터

같은 MySQL을 쓰는 앱 2대와 라운드로빈 nginx를 별도 Docker Compose 프로젝트로 띄운다. 기본 `chat.conf`는 WebSocket을 프록시한다. `naive.conf`는 장애 재현 조건이다.

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

`NGINX_CONF=chat`이 기본이며, 다른 설정 파일을 `infra/cluster/nginx/<이름>.conf`에 둔 뒤 `NGINX_CONF=<이름> docker compose -f infra/compose.cluster.yml up -d --force-recreate --wait nginx`로 교체한다. `CLUSTER_PROFILE=local`이 기본이고 재연결 부하 측정에는 `CLUSTER_PROFILE=bench`를 지정한다. 두 변수는 설정을 바꿀 때의 `up` 명령에도 다시 지정해야 한다.

`f8-upgrade`는 WebSocket 업그레이드 헤더만, `f8-host`는 여기에 포트 없는 Host를 더한 비교 설정이다. `f8-timeout5`는 정상 설정의 읽기 유휴 시간만 5초로 줄인 대조다. `chat`의 `X-Forwarded-Port: 18090`은 위 nginx 호스트 포트와 맞춰 두었으므로 포트를 바꾸면 둘 다 수정해야 한다.

`chat`의 nginx 경유 요청은 요청자가 보낸 `X-Forwarded-For` 대신 nginx가 관측한 주소를 앱에 전달한다. 실험용 앱 직접 포트 18081·18082는 이 처리를 거치지 않는다. nginx 앞에 다른 프록시를 배치하거나 앱 포트를 외부에 공개할 때는 신뢰할 프록시와 원격 IP 전달 방식을 다시 정해야 한다.

앱 로그는 각각 `backend/logs/cluster/app1`, `backend/logs/cluster/app2`에 쌓인다. nginx 로그는 위 `logs nginx` 명령으로 본다.

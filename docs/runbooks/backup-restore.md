# 런북 — 백업 / 복구

> D-92 (M2-C5). 대상: 로컬 docker compose 및 Fly.io 배포.
> 도구: `./scripts/backup.sh` (Postgres 전용).
> 이 문서의 절차는 2026-07-30 에 **실제로 파괴 후 복구까지 실행해 검증**했다
> (53 users / 37 matches / 148 participants 삭제 → 전량 복원).

## 0. 먼저 알아야 할 것 — 무엇이 복구 불가인가

**Redis 는 백업 대상이 아니다.** 이건 누락이 아니라 설계다.

| 데이터 | 저장소 | 복구 가능? |
| --- | --- | --- |
| 계정(username/password_hash), 전적, ELO, 탈주 수 | Postgres `users` | ✅ 백업으로 복구 |
| 매치 결과·참가자 | Postgres `tichu_match_results` / `_participants` | ✅ |
| 아바타 이미지 | Postgres `user_avatars` (BYTEA) | ✅ |
| 어드민 권한 | Postgres `admin_roles` | ✅ |
| **진행 중인 방·게임 상태·손패** | Redis `room:*` (TTL 6h) | ❌ **소실** |
| **방 단위 테이블 칩(판돈)** | Redis `room:{id}:chips` (TTL 6h) | ❌ **소실** |
| 로그인 실패 카운터·계정 잠금 | Redis `login:fail:*`, `lock:login:*` | ❌ (소실이 정상 — 잠금이 풀릴 뿐) |
| 레이트리밋 카운터 | Redis `ratelimit:*` | ❌ (소실이 정상) |
| 유저 정지 마커 | Redis `suspend:user:{id}` (TTL) | ❌ **정지가 조기 해제됨** — 아래 주의 |

**진행 중 매치의 소실은 정상 동작이다.** 칩을 계정에 두지 않기로 한 D-82 덕분에
방이 사라져도 계정 자산이 손상되지 않는다. 서버를 재시작하면 클라이언트는 방을 잃고
메인으로 돌아간다 — 데이터 정합성 문제가 아니라 사용자 경험 문제로 다룬다.

> ⚠️ **유저 정지(D-86)만 예외적으로 주의.** `suspend:user:{id}` 는 Redis TTL 마커라
> Redis 를 비우면 정지가 조기 해제된다. 장기 정지가 필요하면 해제 후 재적용하거나,
> 영속 정지를 별도 테이블로 승격하는 것을 검토한다(현재 미구현 — 후속 과제).

`docker-compose.yml` 의 Redis 는 `--appendonly yes` 지만, 이건 **컨테이너 재시작
생존용이지 백업이 아니다.** 볼륨이 날아가면 함께 사라진다.

---

## 1. 정기 백업

```bash
./scripts/backup.sh dump
```

- 산출물: `backups/mirboard-<UTC타임스탬프>.dump` (pg_dump custom format, 압축 내장)
- 덤프 직후 `pg_restore --list` 로 **무결성을 자동 확인**한다. 손상되거나 빈 파일이면
  스크립트가 그 파일을 지우고 실패로 끝난다 — 깨진 백업이 "성공"으로 남지 않는다.
- 보관 정리: `./scripts/backup.sh prune 7` (최근 7개만 유지)

라벨을 붙이면 나중에 찾기 쉽다:

```bash
./scripts/backup.sh dump before-v9
```

### 언제 반드시 뜨는가

1. **Flyway 마이그레이션 적용 전** (`dev.sh up` 이 자동 적용하므로 그 전에)
2. 어드민 권한 부여/회수 전
3. 배포 전

---

## 2. 복구

```bash
./scripts/backup.sh list                    # 후보 확인
./scripts/backup.sh verify <파일>            # 복구 전 무결성만 확인
./scripts/backup.sh restore <파일>           # 실제 복구 (DB 이름 입력 확인 프롬프트)
```

- `restore` 는 **기존 데이터를 덮어쓴다.** 확인 프롬프트에서 DB 이름(`mirboard`)을
  정확히 입력해야 진행된다. 자동화에서는 `MIRBOARD_BACKUP_YES=1`.
- `--clean --if-exists` 로 기존 객체를 지우고 재생성한다. 단일 트랜잭션을 쓰지 않으므로
  "drop 대상이 없다"는 경고는 정상이며 무시해도 된다.
- 복구가 끝나면 스크립트가 행 수를 출력한다. 기대값과 다르면 멈추고 원인을 찾을 것.
- **복구 후 서버를 재시작한다.** 커넥션 풀이 옛 스냅샷을 잡고 있을 수 있다.

### 복구 후 체크리스트

```bash
./scripts/check.sh infra                    # Postgres/Redis 헬스
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/api/games   # 401 = 정상(인증 필요)
```

그리고 로그인 → 랭킹 표시까지 눈으로 확인한다.

---

## 3. 재해 시나리오별 대응

### (a) Postgres 볼륨 손실

1. `docker compose down` → 볼륨 재생성 (`docker volume rm mirboard_postgres-data`)
2. `./scripts/dev.sh up` — Flyway 가 빈 스키마에 V1~V8 적용
3. `./scripts/backup.sh restore <최신 백업>`
4. 위 체크리스트

> Flyway 이력(`flyway_schema_history`)도 백업에 포함되므로, 복구 후 다시
> 마이그레이션을 돌릴 필요는 없다.

### (b) Redis 볼륨 손실 / 전체 flush

**복구 절차 없음. 다음을 확인만 한다.**

- 진행 중이던 방·매치는 사라진다 (정상)
- 계정/전적/ELO 는 Postgres 에 있으므로 무사
- 정지 중이던 유저가 있었다면 **정지를 재적용**해야 한다 (위 ⚠️)
- 서버 재시작 권장 — in-memory `WsSessionRegistry`(D-75)와 Redis 상태의 불일치 정리

### (c) 마이그레이션 실패

1. 즉시 `./scripts/backup.sh restore <적용 전 백업>`
2. 실패한 `V*.sql` 을 수정
3. `docker compose --profile migrate run --rm flyway` 로 재적용

> Flyway 는 실패한 마이그레이션을 이력에 남기므로, 복구로 이력째 되돌리는 편이
> `flyway repair` 보다 단순하고 안전하다.

---

## 4. Fly.io (배포 환경)

로컬과 다른 점만 적는다.

- Postgres 가 컨테이너가 아니라 Fly Postgres 앱이므로 `docker compose exec` 대신
  `fly postgres connect` / `fly proxy` 로 접속해 같은 `pg_dump -Fc` 를 쓴다.
- Upstash Redis 는 관리형이라 볼륨 관리가 불필요하지만, **복구 대상이 아니라는 점은
  동일**하다.
- 백업 파일은 로컬로 받아 보관한다 — 앱 볼륨에 두면 앱과 함께 사라진다.

```bash
fly proxy 15432:5432 -a <postgres-app> &
pg_dump -h localhost -p 15432 -U postgres -d mirboard -Fc -f mirboard-prod.dump
```

---

## 5. 한계와 후속 과제

- **PITR(특정 시점 복구) 없음.** 논리 백업만이라 마지막 `dump` 이후 변경분은 소실된다.
  지인 대상 서비스라 수용 범위로 판단했다. 필요해지면 Fly Postgres 스냅샷 또는
  WAL 아카이빙을 검토한다.
- **자동 스케줄 없음.** 현재는 수동 실행이다. cron/launchd 등록은 운영 빈도가
  올라간 뒤에.
- **유저 정지의 Redis 의존** — 위 ⚠️ 참조.

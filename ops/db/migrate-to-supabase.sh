#!/usr/bin/env bash
# D-169 — 운영 DB 이전: Fly Postgres(mirboard-db 의 public) → sideinfra Supabase(mirboard 스키마). 운영자가 직접 한 번 돌린다.
#
# 하는 일 (중간에 실패하면 그 자리에서 멈춘다. 접속 정보를 바꾸기 전이면 앱만 다시 켜면 원래대로다):
#   0. 저장소 루트에서 실행한다: bash ops/db/migrate-to-supabase.sh
#   1. 지금 접속 정보를 ~/.mirboard-db-rollback.env 에 보관(되돌리기용, 600)
#   2. mirboard_app 비밀번호를 새로 만들어 Supabase 에 설정(SCRAM 해시만 보낸다 — 평문은 이 셸 밖으로 안 나간다)
#   3. 앱 머신 내리기(scale 0 — 끝나면 같은 이미지로 다시 배포) → Fly DB public 덤프 → 임시 컨테이너에서 스키마 이름을 mirboard 로 → Supabase 에 한 트랜잭션으로 복원
#   4. 원본·대상 행 수 대조 → 접속 정보 교체 → 앱 다시 올리기 → 헬스 확인
#
# 필요: flyctl(로그인), supabase CLI(로그인, sideinfra 에 link), docker(OrbStack), python3, curl
# 사전 조건: sideinfra 마이그레이션 20261102120000_mirboard_schema 적용, mirboard 가 MIRBOARD_DB_SCHEMA 를 읽는 버전으로 배포됨
set -euo pipefail

REF=bonkcrwavdzqaedlktzi
POOLER=aws-0-ap-northeast-2.pooler.supabase.com
SIDEINFRA=${SIDEINFRA:-$HOME/Developer/sideinfra}
APP=mirboard
HEALTH=https://mirboard.fly.dev/actuator/health
ROLLBACK=$HOME/.mirboard-db-rollback.env
BACKUP=$HOME/mirboard-db-backup-$(date +%Y%m%d-%H%M%S).sql
TMP_PG=mirboard-migrate-$$
WORK=$(mktemp -d)
SCALED_DOWN=0
SWITCHED=0
IMAGE=
cd "$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"   # fly.toml 이 있는 곳 — 다시 올릴 때 flyctl deploy 가 읽는다

step() { printf '\n== %s\n' "$*"; }
die() { echo "오류: $*" >&2; exit 1; }

cleanup() {
  docker rm -f "$TMP_PG" >/dev/null 2>&1 || true
  rm -rf "$WORK"
  if [ "$SCALED_DOWN" = 1 ] && [ "$SWITCHED" = 0 ]; then
    echo
    echo "!! 중간에 멈췄다. 접속 정보는 그대로라 앱만 다시 올리면 원래대로다 (저장소 루트에서):"
    echo "   flyctl deploy -a $APP --image $IMAGE --ha=false"
  fi
}
trap cleanup EXIT

if ! docker info >/dev/null 2>&1; then
  export DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock
  docker info >/dev/null 2>&1 || die "docker 에 연결할 수 없다 (OrbStack 을 켜 둘 것)"
fi
for c in flyctl supabase python3 curl; do command -v "$c" >/dev/null || die "$c 이 없다"; done

sbq() { supabase db query --linked --workdir "$SIDEINFRA" -o json "$1" </dev/null 2>/dev/null; }

step "사전 확인"
sbq "select count(*) as n from pg_tables where schemaname = 'mirboard'" | grep -q '"n": *0' \
  || die "Supabase mirboard 스키마에 이미 테이블이 있다 (이미 옮겼거나 이전 시도가 남았다)"
flyctl secrets list -a "$APP" | grep -q MIRBOARD_DB_SCHEMA && die "이미 MIRBOARD_DB_SCHEMA 가 설정돼 있다 (이미 옮김?)"
read -r -p "진행 중인 게임이 없고, 앱이 몇 분 멈춰도 되나? [y/N] " ok
[ "$ok" = y ] || die "취소"

step "1. 지금 접속 정보 보관 → $ROLLBACK"
curl -s -o /dev/null --max-time 120 "$HEALTH" || true   # 멈춰 있으면 깨운다
( umask 077
  flyctl ssh console -a "$APP" -C "sh -c 'for k in MIRBOARD_DB_URL MIRBOARD_DB_USER MIRBOARD_DB_PASSWORD; do printf \"%s=%s\\n\" \$k \"\$(printenv \$k)\"; done'" \
    2>/dev/null | grep '^MIRBOARD_DB_' > "$ROLLBACK" )
[ "$(grep -c '^MIRBOARD_DB_[A-Z]*=.' "$ROLLBACK")" = 3 ] || die "지금 접속 정보를 읽지 못했다"

step "2. mirboard_app 비밀번호 설정 (Supabase)"
PW=$(openssl rand -hex 24)
VERIFIER=$(PW="$PW" python3 -c '
import base64, hashlib, hmac, os
pw = os.environ["PW"].encode(); salt = os.urandom(16); it = 4096
salted = hashlib.pbkdf2_hmac("sha256", pw, salt, it)
ck = hmac.new(salted, b"Client Key", "sha256").digest()
sk = hmac.new(salted, b"Server Key", "sha256").digest()
b = lambda x: base64.b64encode(x).decode()
print(f"SCRAM-SHA-256${it}:{b(salt)}${b(hashlib.sha256(ck).digest())}:{b(sk)}")')
sbq "alter role mirboard_app with password '$VERIFIER'" >/dev/null || die "비밀번호 설정 실패"
export PGPASSWORD="$PW"
SUPA="host=$POOLER port=5432 dbname=postgres user=mirboard_app.$REF sslmode=require"
docker run --rm -e PGPASSWORD postgres:17-alpine psql "$SUPA" -At -c "select current_user, current_setting('search_path')" \
  || die "mirboard_app 으로 Supabase 풀러에 접속하지 못했다"

step "3. 앱 내리기"
# 머신을 멈추기만 하면 접속이 올 때 옛 접속 정보로 다시 켜진다(auto_start) → 머신을 없애고, 끝나면 같은 이미지로 다시 배포한다
IMAGE=$(flyctl machine list -a "$APP" --json | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["config"]["image"])')
[ -n "$IMAGE" ] || die "지금 이미지를 읽지 못했다"
echo "이미지: $IMAGE"
flyctl scale count 0 -a "$APP" --yes
SCALED_DOWN=1

step "4. Fly DB 덤프 → $BACKUP"
( umask 077
  flyctl ssh console -a mirboard-db \
    -C 'sh -c "PGPASSWORD=\$OPERATOR_PASSWORD pg_dump -h localhost -p 5433 -U postgres -d postgres -n public --no-owner --no-acl"' \
    > "$BACKUP" 2>/dev/null )
grep -q 'PostgreSQL database dump complete' "$BACKUP" || die "덤프가 끝까지 받아지지 않았다"
cp "$BACKUP" "$WORK/fly-public.sql"

step "5. 스키마 이름 바꾸기 (임시 컨테이너)"
docker run -d --name "$TMP_PG" -e POSTGRES_PASSWORD=tmp -v "$WORK:/work" postgres:17-alpine >/dev/null
for _ in $(seq 1 30); do docker exec "$TMP_PG" pg_isready -U postgres >/dev/null 2>&1 && break; sleep 1; done
docker exec "$TMP_PG" sh -c '
set -e
psql -q -U postgres -v ON_ERROR_STOP=1 -c "drop schema public cascade"
psql -q -U postgres -v ON_ERROR_STOP=1 -f /work/fly-public.sql >/dev/null
psql -q -U postgres -v ON_ERROR_STOP=1 -c "alter schema public rename to mirboard" -c "comment on schema mirboard is null"
pg_dump -U postgres -n mirboard --no-owner --no-acl | grep -v "^CREATE SCHEMA mirboard;\$" > /work/mirboard.sql'

COUNTS="select 'users', count(*) from users union all select 'user_game_stats', count(*) from user_game_stats
union all select 'user_avatars', count(*) from user_avatars union all select 'admin_roles', count(*) from admin_roles
union all select 'chat_reports', count(*) from chat_reports union all select 'onecard_match_results', count(*) from onecard_match_results
union all select 'onecard_match_participants', count(*) from onecard_match_participants
union all select 'skullking_match_results', count(*) from skullking_match_results
union all select 'skullking_match_participants', count(*) from skullking_match_participants
union all select 'tichu_match_results', count(*) from tichu_match_results
union all select 'tichu_match_participants', count(*) from tichu_match_participants
union all select 'flyway_schema_history', count(*) from flyway_schema_history order by 1"
docker exec "$TMP_PG" psql -U postgres -At -c "set search_path = mirboard" -c "$COUNTS" | grep '|' > "$WORK/src.txt"

step "6. Supabase 에 복원 (한 트랜잭션 — 실패하면 아무것도 안 남는다)"
docker run --rm -i -e PGPASSWORD postgres:17-alpine psql "$SUPA" -q -v ON_ERROR_STOP=1 --single-transaction \
  < "$WORK/mirboard.sql" >/dev/null
docker run --rm -e PGPASSWORD postgres:17-alpine psql "$SUPA" -At -c "$COUNTS" | grep '|' > "$WORK/dst.txt"
echo "원본 / 대상 행 수:"; paste "$WORK/src.txt" "$WORK/dst.txt"
diff -q "$WORK/src.txt" "$WORK/dst.txt" >/dev/null || die "행 수가 다르다 — 접속 정보는 바꾸지 않았다"

step "7. 접속 정보 교체 → 앱 올리기"
printf 'MIRBOARD_DB_URL=jdbc:postgresql://%s:5432/postgres?sslmode=require\nMIRBOARD_DB_USER=mirboard_app.%s\nMIRBOARD_DB_PASSWORD=%s\nMIRBOARD_DB_SCHEMA=mirboard\n' \
  "$POOLER" "$REF" "$PW" | flyctl secrets import -a "$APP"
SWITCHED=1
flyctl deploy -a "$APP" --image "$IMAGE" --ha=false

step "8. 헬스 확인 (최대 5분)"
for i in $(seq 1 30); do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$HEALTH" || true)
  [ "$code" = 200 ] && { echo "UP (${i}회째)"; break; }
  sleep 10
done
[ "${code:-}" = 200 ] || {
  echo "!! 헬스가 200 이 아니다. 되돌리기:"
  echo "   flyctl secrets import -a $APP < $ROLLBACK && flyctl secrets unset MIRBOARD_DB_SCHEMA -a $APP"
  echo "   (로그: flyctl logs -a $APP --no-tail)"
  exit 1; }

cat <<EOF

완료. Fly DB(mirboard-db)는 그대로 남아 있다(되돌리기용).
- 되돌리기: flyctl secrets import -a $APP < $ROLLBACK && flyctl secrets unset MIRBOARD_DB_SCHEMA -a $APP
  (그 사이 Supabase 에 쌓인 기록은 따로 옮겨야 한다)
- 덤프 백업: $BACKUP
- 1주일 문제없으면: flyctl apps destroy mirboard-db, 그리고 위 두 파일 삭제
EOF

# D-114 — 운영 Redis (Fly 앱 `mirboard-redis`). 본 앱(`mirboard`)이 사설망으로만 붙는다.
#
# - 비밀번호는 Fly 시크릿 REDIS_PASSWORD 로 주입 → 셸로 전개해야 해서 sh -c 로 감싼다.
# - bind '* -::*' : Fly 사설망(6PN)은 IPv6 라 IPv4/IPv6 양쪽을 듣는다.
# - 영속 없음(--save '' --appendonly no): 이 프로젝트의 Redis 는 설계상 휘발 상태다
#   (docs/runbooks/backup-restore.md "복구 불가 범위").
# - maxmemory 200mb + noeviction: VM 256MB 안에서 여유를 두고, 꽉 차면 게임 상태를 조용히
#   지우는 대신 쓰기 실패로 드러낸다.
FROM redis:7-alpine

CMD ["sh", "-c", "exec redis-server --requirepass \"$REDIS_PASSWORD\" --bind '* -::*' --port 6379 --save '' --appendonly no --maxmemory 200mb --maxmemory-policy noeviction"]

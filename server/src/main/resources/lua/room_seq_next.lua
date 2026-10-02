-- room_seq_next.lua
-- 방 이벤트 seq 발급: INCR + EXPIRE 를 한 번에.
--
-- KEYS[1] = room:{roomId}:seq
-- ARGV[1] = ttl 초 (다른 room 키와 같은 6h)
--
-- 반환: 새 seq (1부터 단조 증가)
--
-- 왜 Lua 인가: 발행 경로는 이벤트마다 도는 핫패스라 INCR/EXPIRE 를 왕복 2회로
-- 나누면 (1) 라운드트립이 늘고 (2) 그 사이에 죽으면 TTL 없는 고아 키가 남는다.
-- 원자로 묶으면 "카운터가 존재하면 반드시 TTL 도 있다" 가 항상 참이다.
--
-- 왜 매번 EXPIRE 인가: 첫 발행에만 걸면 6h 넘게 이어지는 방에서 카운터가 매치
-- 도중 만료돼 INCR 이 1부터 다시 시작한다 — 클라의 seq gap 판정이 깨진다.
-- 발행 때마다 밀어주면 활동 중인 방의 카운터는 절대 사라지지 않고, 방이 조용해진
-- 뒤에야 다른 room 키들과 같은 시한으로 사그라든다.

local seq = redis.call('INCR', KEYS[1])
redis.call('EXPIRE', KEYS[1], ARGV[1])
return seq

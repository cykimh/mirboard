package com.mirboard.domain.lobby.auth;

import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase 9A — 시드 봇 user id 캐시.
 *
 * <p>마이그레이션이 INSERT 한 봇(V3: north/east/south/west 4명, V10: 대각 4명 추가 —
 * D-102, 스컬킹 8인 방 지원)을 부팅 시 전부 로드해서 메모리에 보관한다. 솔로 방
 * (`fillWithBots=true`) 이 빈 좌석을 봇 user id 로 채울 때, BotScheduler 가 어떤 좌석이
 * 봇인지 판별할 때 사용.
 */
@Component
public class BotUserRegistry {

    private static final Logger log = LoggerFactory.getLogger(BotUserRegistry.class);

    /** 최소 보장 수 (V3). 미만이면 마이그레이션 미적용 — 기동 실패가 맞다. */
    private static final int MIN_BOTS = 4;

    private final List<Long> botIds;
    private final Set<Long> botIdSet;

    public BotUserRegistry(UserRepository userRepository) {
        List<User> bots = userRepository.findBots();
        if (bots.size() < MIN_BOTS) {
            throw new IllegalStateException(
                    "Expected at least " + MIN_BOTS
                            + " seed bots (V3 migration), found " + bots.size());
        }
        this.botIds = bots.stream().map(User::getId).toList();
        this.botIdSet = Set.copyOf(this.botIds);
        log.info("BotUserRegistry initialized: botIds={}", botIds);
    }

    /** 시드 봇 user id 목록 (id 오름차순, 전체). */
    public List<Long> getBotIds() {
        return botIds;
    }

    /** 주어진 user id 가 시드 봇인지 여부. */
    public boolean isBot(long userId) {
        return botIdSet.contains(userId);
    }

    /** Phase 9B 가 빈 좌석을 채울 때 필요한 봇 수만큼 ids 반환. */
    public List<Long> takeBots(int count) {
        if (count < 0 || count > botIds.size()) {
            throw new IllegalArgumentException(
                    "count must be in [0, " + botIds.size() + "], got " + count);
        }
        return botIds.subList(0, count);
    }
}

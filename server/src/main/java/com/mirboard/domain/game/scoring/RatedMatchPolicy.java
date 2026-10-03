package com.mirboard.domain.game.scoring;

import com.mirboard.domain.lobby.auth.BotUserRegistry;
import com.mirboard.domain.lobby.auth.GuestPolicy;
import com.mirboard.domain.lobby.auth.UserRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 한 매치에 ELO 를 적용할지의 단일 판정. 게임 기록기(티츄·스컬킹, 그리고 앞으로의 게임)가
 * 공유한다 — 기록기마다 조건을 따로 쓰면 한쪽만 고쳐지는 일이 생긴다.
 *
 * <p>제외 조건은 둘이다.
 * <ul>
 *   <li>봇이 낀 매치(D-71) — 봇 상대로 레이팅을 쌓지 못하게.</li>
 *   <li>게스트가 낀 매치(D-117) — 일회용 신원이라 상대로 레이팅을 파밍할 수 있다. 게스트를
 *       1000 고정 입력으로 두고 정회원만 반영하면 합이 0 이 아니게 돼 레이팅이 부푼다.</li>
 * </ul>
 * 어느 쪽이든 승패·탈주는 기록한다 — 바뀌는 것은 rating 뿐이다.
 */
@Component
public class RatedMatchPolicy {

    private final BotUserRegistry bots;
    private final UserRepository users;

    public RatedMatchPolicy(BotUserRegistry bots, UserRepository users) {
        this.bots = bots;
        this.users = users;
    }

    public boolean eloApplies(List<Long> playerIds) {
        if (playerIds.stream().anyMatch(bots::isBot)) {
            return false;
        }
        return !users.existsByIdInAndUsernameStartingWith(playerIds, GuestPolicy.PREFIX);
    }
}

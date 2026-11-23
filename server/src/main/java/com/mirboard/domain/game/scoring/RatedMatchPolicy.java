package com.mirboard.domain.game.scoring;

import com.mirboard.domain.lobby.auth.BotUserRegistry;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 한 매치의 ELO 를 얼마나 적용할지의 단일 판정. 아홉 게임 기록기가 공유한다 — 기록기마다 조건을 따로 쓰면
 * 한쪽만 고쳐지는 일이 생긴다.
 *
 * <ul>
 *   <li>봇이 낀 매치(D-299) — {@link EloCalculator#BOT_MATCH_FACTOR} 만큼만(게스트가 같이 끼어도 봇 판 규칙). 예전(D-71)에는
 *       건너뛰었다. 봇은 전적 행이 없어 계산에 기본값 1000·0판으로 들어가고, 봇의 레이팅은 저장하지 않는다(기록기가 봇 행을
 *       쓰지 않는다). 봇과 연습 방·대기실에서 봇을 채운 방을 가르지 않는다.</li>
 *   <li>그 밖(사람끼리 — <b>게스트가 끼어도</b>, D-299 덧붙임) — 그대로(배율 1). 예전(D-117)에는 게스트가 낀 매치를 전원
 *       미적용으로 뒀다. 게스트 본인의 레이팅은 저장하지 않는다 — 전적 행은 남되(승패, 게스트 정리의 증거) rating 은 늘 1000 이고
 *       계산에도 1000 으로 들어간다({@link UserGameStatsService#record(long, String, boolean, Integer, boolean, double)} 가
 *       게스트면 rating 을 쓰지 않는다). 일회용 신원을 상대로 레이팅을 쌓을 수 있다는 것은 받아들인다.</li>
 * </ul>
 * 지금은 어느 판이든 0 보다 크다. {@link #UNRATED} 는 "이 판은 ELO·참가 점수 없음"을 뜻하는 값으로 남겨 둔다(기록기의
 * {@code rated} 판정과 배율 없는 {@code record} 가 쓴다).
 */
@Component
public class RatedMatchPolicy {

    /** ELO 를 적용하지 않는 판의 배율. */
    public static final double UNRATED = 0.0;

    private final BotUserRegistry bots;

    public RatedMatchPolicy(BotUserRegistry bots) {
        this.bots = bots;
    }

    /**
     * 이 판의 레이팅 변화 배율. {@link #UNRATED}(0)이면 ELO 를 계산하지 말 것 — 그 밖의 값은
     * {@link EloCalculator#applyMatch(List, List, boolean, double)}·{@link EloCalculator#applyFreeForAll(List, double)}
     * 와 {@link UserGameStatsService#record(long, String, boolean, Integer, boolean, double)} 에 그대로 넘긴다.
     */
    public double eloFactor(List<Long> playerIds) {
        return playerIds.stream().anyMatch(bots::isBot)
                ? EloCalculator.BOT_MATCH_FACTOR
                : EloCalculator.FULL_FACTOR;
    }
}

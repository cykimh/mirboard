package com.mirboard.domain.game.skullking.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.mirboard.domain.game.skullking.card.SkullSuit;
import com.mirboard.domain.game.skullking.trick.LeadSuitResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 진행 중인 트릭 하나.
 *
 * <p>리드 수트를 <b>필드로 갖지 않는 것이 요점</b>이다 — 지연 확정(§6.1) 때문에 갱신
 * 경로를 하나만 빠뜨려도 stale 해지므로 {@link #leadSuit()} 가 매번 파생한다.
 *
 * @param leadSeat 이 트릭을 리드한 좌석
 * @param played   제출 순서대로의 카드들
 */
public record TrickState(int leadSeat, List<PlayedCard> played) {

    public TrickState {
        played = List.copyOf(played);
    }

    public static TrickState lead(int leadSeat) {
        return new TrickState(leadSeat, List.of());
    }

    /** 지금 확정된 리드 수트 (§6.1). empty 면 follow 의무가 없다. */
    @JsonIgnore
    public Optional<SkullSuit> leadSuit() {
        return LeadSuitResolver.resolve(played);
    }

    /** 아직 아무도 안 낸 리드 시점인가. */
    @JsonIgnore
    public boolean isLead() {
        return played.isEmpty();
    }

    /** 지금 낼 차례인 좌석. 트릭이 다 찼으면 -1. */
    public int currentTurnSeat(int seatCount) {
        return played.size() >= seatCount ? -1 : (leadSeat + played.size()) % seatCount;
    }

    /** 전원이 1장씩 냈는가 (§9). */
    public boolean isComplete(int seatCount) {
        return played.size() >= seatCount;
    }

    public TrickState with(PlayedCard card) {
        List<PlayedCard> next = new ArrayList<>(played);
        next.add(card);
        return new TrickState(leadSeat, next);
    }
}

package com.mirboard.domain.game.skullking.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/**
 * 끝난 트릭 하나의 결과.
 *
 * <p>명세 §9 함정: <i>"승자가 '몇 번 이겼는지'만 세면 안 된다. 보너스가 무엇으로 무엇을
 * 잡았는지에 달려 있어서, 트릭마다 (승리 카드, 그 트릭의 전체 카드)를 보관해야 한다."</i>
 * → 승수 카운터가 아니라 본 record 를 라운드 내내 누적한다.
 *
 * @param winnerSeat  트릭을 가져간 좌석
 * @param winningCard 이긴 카드 — 포획 보너스(§11)의 "무엇으로" 에 해당
 * @param cards       제출 순서대로의 트릭 전체 카드 — "무엇을" 에 해당
 */
public record TrickResult(int winnerSeat, PlayedCard winningCard, List<PlayedCard> cards) {

    public TrickResult {
        cards = List.copyOf(cards);
    }

    /**
     * 승자가 이번 트릭에서 실제로 이긴 상대 카드들 (자기 카드 제외).
     *
     * <p>참조가 아니라 값으로 비교한다 — S5 가 상태를 JSON 으로 왕복시키면
     * {@code winningCard} 는 {@code cards} 안의 인스턴스와 다른 객체가 된다. 한 트릭에서
     * 한 좌석은 한 장만 내므로 {@code seat} 이 유일해 값 비교가 정확하다.
     */
    @JsonIgnore
    public List<PlayedCard> defeated() {
        return cards.stream().filter(pc -> !pc.equals(winningCard)).toList();
    }
}

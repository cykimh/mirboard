package com.mirboard.domain.game.skullking;

import com.mirboard.domain.game.skullking.card.Deck;
import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.state.PlayerState;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 라운드 카드 분배 (`docs/rules-skullking.md` §4).
 *
 * <p>라운드 N 에서 전원에게 N 장씩 나눠주고, <b>그 라운드의 트릭 수 = 손패 장수</b>가 된다.
 * 다만 덱이 70장이라 8인은 후반에 부족해진다 — 원문은 이 처리를 8명에 대해서만 서술하고,
 * 우리는 {@code min(라운드, ⌊70/N⌋)} 로 일반화했다 (§13-⑭). 70장 덱에서 실제로 부족한
 * 조합은 8인 라운드 9·10 뿐이라 다른 인원의 동작은 일반화 전후가 같다.
 *
 * <p>명세 함정 #2: <b>손패 크기가 라운드마다 변한다.</b> 고정 크기를 전제한 자료구조는
 * 후반 라운드에서 깨진다. 그리고 함정 #3: 트릭 수·예측 상한은 <b>손패 장수</b>를 따르고
 * 0승 보너스만 <b>라운드 번호</b>를 따른다.
 */
public final class Dealer {

    private Dealer() {
    }

    /**
     * 라운드 N 의 1인당 손패 장수.
     *
     * @param roundNumber 1 ~ 10
     * @param seatCount   2 ~ 8
     */
    public static int handSize(int roundNumber, int seatCount) {
        if (seatCount <= 0) {
            throw new IllegalArgumentException("seatCount must be positive: " + seatCount);
        }
        return Math.min(roundNumber, Deck.SIZE / seatCount);
    }

    /**
     * 덱을 새로 섞어 좌석별 손패를 만든다. 라운드마다 덱 전체를 다시 섞으므로 라운드 간
     * 카드가 이월되지 않는다 (§3).
     *
     * @return 좌석 순서대로의 초기 플레이어 상태 (예측 미제출)
     */
    public static List<PlayerState> deal(int roundNumber, int seatCount, Random rng) {
        int perSeat = handSize(roundNumber, seatCount);
        List<SkullCard> deck = Deck.shuffled(rng).cards();

        List<PlayerState> players = new ArrayList<>(seatCount);
        for (int seat = 0; seat < seatCount; seat++) {
            int from = seat * perSeat;
            players.add(PlayerState.initial(seat, List.copyOf(deck.subList(from, from + perSeat))));
        }
        return List.copyOf(players);
    }
}

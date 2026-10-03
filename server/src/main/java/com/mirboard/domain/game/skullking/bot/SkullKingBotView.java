package com.mirboard.domain.game.skullking.bot;

import com.mirboard.domain.game.skullking.card.Deck;
import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.state.PlayedCard;
import com.mirboard.domain.game.skullking.state.PlayerState;
import com.mirboard.domain.game.skullking.state.SkullKingState;
import com.mirboard.domain.game.skullking.state.TrickResult;
import java.util.ArrayList;
import java.util.List;

/**
 * D-119 — 봇이 볼 수 있는 것의 전부 (정보 경계). 정책 {@link SkullKingBotPolicy} 는 이
 * 타입만 받는다 — 어댑터는 전체 상태를 쥐고 있으므로, 이 경계가 없으면 후속 수정 한 줄로
 * 남의 손패가 정책에 새어 들어갈 수 있다.
 *
 * <p><b>공정성 기준은 "UI 노출"이 아니라 "공개 이벤트로 이미 공개된 정보"다</b>
 * (`docs/rules-skullking.md` §16). 그래서 담는 것은 본인 손패, 본인 예측·승수, 현재 트릭,
 * 이번 라운드에 {@code CARD_PLAYED} 로 공개된 카드 전부다. 공개 카드는 완전히 기억한다 —
 * UI 는 직전 트릭만 보여 주지만 사람도 기억할 수 있는 정보다.
 *
 * <p>담지 않는 것: 남의 손패(어느 단계에서도), 입찰 중 남의 제출 예측(§5 — 전원 제출 전
 * 비공개). 플레이 중엔 남의 예측·승수가 공개돼 있지만 지금 정책이 쓰지 않으므로 담지
 * 않는다(상대 모델링은 후속 — 넣을 때 이 경계를 함께 넓힌다).
 *
 * @param seat         봇 좌석
 * @param seatCount    인원 (2~8)
 * @param roundNumber  라운드 번호
 * @param bidding      입찰 단계인가 (아니면 플레이)
 * @param hand         본인 손패
 * @param ownBid       본인 예측. 입찰 단계에선 {@link PlayerState#NO_BID}
 * @param ownTricksWon 본인이 이번 라운드에 가져간 트릭 수
 * @param currentTrick 진행 중 트릭에 나온 카드 (제출 순)
 * @param seenCards    이번 라운드에 공개된 카드 — 완료 트릭 전부 + 진행 중 트릭
 */
public record SkullKingBotView(int seat,
                               int seatCount,
                               int roundNumber,
                               boolean bidding,
                               List<SkullCard> hand,
                               int ownBid,
                               int ownTricksWon,
                               List<PlayedCard> currentTrick,
                               List<SkullCard> seenCards) {

    public SkullKingBotView {
        hand = List.copyOf(hand);
        currentTrick = List.copyOf(currentTrick);
        seenCards = List.copyOf(seenCards);
    }

    /**
     * 전체 상태를 읽는 <b>유일한</b> 지점. 여기서 고른 필드만 정책에 넘어간다.
     *
     * @param seat 0 ~ seatCount-1 (범위 검사는 호출자 — 어댑터는 합법수가 빈 좌석을 먼저
     *             거른다)
     */
    public static SkullKingBotView of(SkullKingState state, int seat) {
        PlayerState me = state.players().get(seat);
        return switch (state) {
            case SkullKingState.Bidding bidding -> new SkullKingBotView(seat, bidding.seatCount(),
                    bidding.roundNumber(), true, me.hand(), PlayerState.NO_BID, 0,
                    List.of(), List.of());
            case SkullKingState.Playing playing -> new SkullKingBotView(seat, playing.seatCount(),
                    playing.roundNumber(), false, me.hand(), me.bid(), me.tricksWonCount(),
                    playing.trick().played(), seen(playing.players(), playing.trick().played()));
            case SkullKingState.RoundEnd ended -> new SkullKingBotView(seat, ended.seatCount(),
                    ended.roundNumber(), false, me.hand(), me.bid(), me.tricksWonCount(),
                    List.of(), seen(ended.players(), List.of()));
        };
    }

    /**
     * 아직 공개되지 않은 카드 — 덱 70장에서 손패와 공개 카드를 <b>multiset</b> 으로 뺀 것
     * (해적 5장 같은 중복 특수 카드가 정확히 빠진다). 남의 손패와 이번 라운드에 분배되지
     * 않은 카드(8인 라운드 9·10 의 6장)가 구별 없이 여기 남는다 — 봇은 그 둘을 모른다.
     * 순서는 덱 순서라 결정적이다.
     */
    public List<SkullCard> unseenCards() {
        List<SkullCard> pool = new ArrayList<>(Deck.unshuffled().cards());
        hand.forEach(pool::remove);
        seenCards.forEach(pool::remove);
        return pool;
    }

    /** 이번 트릭에서 내 뒤에 낼 사람 수 — 0 이면 내가 마지막 순번이다. */
    public int playersAfterMe() {
        return seatCount - currentTrick.size() - 1;
    }

    private static List<SkullCard> seen(List<PlayerState> players, List<PlayedCard> inProgress) {
        List<SkullCard> seen = new ArrayList<>();
        for (PlayerState player : players) {
            for (TrickResult trick : player.tricksWon()) {
                trick.cards().forEach(pc -> seen.add(pc.card()));
            }
        }
        inProgress.forEach(pc -> seen.add(pc.card()));
        return seen;
    }
}

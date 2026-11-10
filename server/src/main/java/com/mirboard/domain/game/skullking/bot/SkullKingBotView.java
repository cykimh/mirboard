package com.mirboard.domain.game.skullking.bot;

import com.mirboard.domain.game.skullking.card.Deck;
import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.scoring.RoundScore;
import com.mirboard.domain.game.skullking.state.PlayedCard;
import com.mirboard.domain.game.skullking.state.PlayerState;
import com.mirboard.domain.game.skullking.state.SkullKingMatchState;
import com.mirboard.domain.game.skullking.state.SkullKingState;
import com.mirboard.domain.game.skullking.state.TrickResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * D-119·D-230 — 봇이 볼 수 있는 것의 전부 (정보 경계). 정책 {@link SkullKingBotPolicy} 는 이
 * 타입만 받는다 — 어댑터는 전체 상태를 쥐고 있으므로, 이 경계가 없으면 후속 수정 한 줄로
 * 남의 손패가 정책에 새어 들어갈 수 있다.
 *
 * <p><b>공정성 기준은 "UI 노출"이 아니라 "공개 이벤트로 이미 공개된 정보"다</b>
 * (`docs/rules-skullking.md` §16.1). 담는 것: 본인 손패, 현재 트릭, 이번 라운드에
 * {@code CARD_PLAYED} 로 공개된 트릭 전부(누가 무엇을 냈는지 — 빈 수트 추론), 공개된 예측·승수
 * ({@code BIDS_REVEALED} 이후), 지난 라운드의 예측({@code ROUND_ENDED}), 탈주 좌석
 * ({@code SEAT_DESERTED}). 공개 카드는 완전히 기억한다 — 사람도 기억할 수 있는 정보다.
 *
 * <p>담지 않는 것: 남의 손패(어느 단계에서도), 입찰 중 남의 제출 예측(§5 — 전원 제출 전
 * 비공개). 입찰 단계의 {@code bids} 는 본인 것까지 전부 {@link PlayerState#NO_BID} 다.
 *
 * @param seat            봇 좌석
 * @param seatCount       인원 (2~8)
 * @param roundNumber     라운드 번호
 * @param bidding         입찰 단계인가 (아니면 플레이)
 * @param hand            본인 손패
 * @param leadSeat        진행 중 트릭을 리드한(할) 좌석 — 입찰 중엔 라운드 첫 리드 좌석
 * @param currentTrick    진행 중 트릭에 나온 카드 (제출 순)
 * @param completedTricks 이번 라운드에 끝난 트릭 (승자별로 묶여 있어 시간순은 아니다)
 * @param bids            좌석별 공개 예측 — 입찰 중엔 전부 NO_BID
 * @param tricksWon       좌석별 이번 라운드 승수
 * @param pastBids        지난 라운드들의 좌석별 예측 (라운드 순, 각 원소는 좌석 순)
 * @param deserted        탈주 좌석 (오름차순) — 최약수 자동조종으로 낸다 (D-104)
 */
public record SkullKingBotView(int seat,
                               int seatCount,
                               int roundNumber,
                               boolean bidding,
                               List<SkullCard> hand,
                               int leadSeat,
                               List<PlayedCard> currentTrick,
                               List<TrickResult> completedTricks,
                               List<Integer> bids,
                               List<Integer> tricksWon,
                               List<List<Integer>> pastBids,
                               List<Integer> deserted) {

    public SkullKingBotView {
        hand = List.copyOf(hand);
        currentTrick = List.copyOf(currentTrick);
        completedTricks = List.copyOf(completedTricks);
        bids = List.copyOf(bids);
        tricksWon = List.copyOf(tricksWon);
        pastBids = pastBids.stream().map(List::copyOf).toList();
        deserted = List.copyOf(deserted);
    }

    /** 매치 이력 없이 — 테스트·이력이 없는 1라운드용. */
    public static SkullKingBotView of(SkullKingState state, int seat) {
        return of(state, null, seat);
    }

    /**
     * 전체 상태를 읽는 <b>유일한</b> 지점. 여기서 고른 필드만 정책에 넘어간다.
     *
     * @param match 매치 상태 — 지난 라운드 예측·탈주 좌석만 읽는다. null 이면 이력 없음
     * @param seat  0 ~ seatCount-1 (범위 검사는 호출자 — 어댑터는 합법수가 빈 좌석을 먼저
     *              거른다)
     */
    public static SkullKingBotView of(SkullKingState state, SkullKingMatchState match, int seat) {
        PlayerState me = state.players().get(seat);
        int n = state.seatCount();
        List<List<Integer>> pastBids = pastBids(match, state.roundNumber(), n);
        List<Integer> deserted = match == null ? List.of()
                : match.desertedSeats().stream().filter(s -> s >= 0 && s < n).sorted().toList();
        return switch (state) {
            case SkullKingState.Bidding bidding -> new SkullKingBotView(seat, n,
                    bidding.roundNumber(), true, me.hand(), bidding.startSeat(),
                    List.of(), List.of(), Collections.nCopies(n, PlayerState.NO_BID),
                    Collections.nCopies(n, 0), pastBids, deserted);
            case SkullKingState.Playing playing -> new SkullKingBotView(seat, n,
                    playing.roundNumber(), false, me.hand(), playing.trick().leadSeat(),
                    playing.trick().played(), completed(playing.players()),
                    playing.players().stream().map(PlayerState::bid).toList(),
                    playing.players().stream().map(PlayerState::tricksWonCount).toList(),
                    pastBids, deserted);
            case SkullKingState.RoundEnd ended -> new SkullKingBotView(seat, n,
                    ended.roundNumber(), false, me.hand(), ended.startSeat(),
                    List.of(), completed(ended.players()),
                    ended.players().stream().map(PlayerState::bid).toList(),
                    ended.players().stream().map(PlayerState::tricksWonCount).toList(),
                    pastBids, deserted);
        };
    }

    /** 본인 예측 — 입찰 단계에선 {@link PlayerState#NO_BID}. */
    public int ownBid() {
        return bids.get(seat);
    }

    /** 본인이 이번 라운드에 가져간 트릭 수. */
    public int ownTricksWon() {
        return tricksWon.get(seat);
    }

    /** 이번 라운드에 공개된 카드 — 완료 트릭 전부 + 진행 중 트릭. */
    public List<SkullCard> seenCards() {
        List<SkullCard> seen = new ArrayList<>();
        for (TrickResult trick : completedTricks) {
            trick.cards().forEach(pc -> seen.add(pc.card()));
        }
        currentTrick.forEach(pc -> seen.add(pc.card()));
        return seen;
    }

    /**
     * 아직 공개되지 않은 카드 — 덱 70장에서 손패와 공개 카드를 <b>multiset</b> 으로 뺀 것
     * (해적 5장 같은 중복 특수 카드가 정확히 빠진다). 남의 손패와 이번 라운드에 분배되지
     * 않은 카드가 구별 없이 여기 남는다 — 봇은 그 둘을 모른다. 순서는 덱 순서라 결정적이다.
     */
    public List<SkullCard> unseenCards() {
        List<SkullCard> pool = new ArrayList<>(Deck.unshuffled().cards());
        hand.forEach(pool::remove);
        seenCards().forEach(pool::remove);
        return pool;
    }

    /** 이번 트릭에서 내 뒤에 낼 사람 수 — 0 이면 내가 마지막 순번이다. */
    public int playersAfterMe() {
        return seatCount - currentTrick.size() - 1;
    }

    private static List<TrickResult> completed(List<PlayerState> players) {
        List<TrickResult> tricks = new ArrayList<>();
        players.forEach(p -> tricks.addAll(p.tricksWon()));
        return tricks;
    }

    /** 지금 라운드보다 앞선 정산 라운드의 예측 — 정산 기록에 없는 좌석은 NO_BID. */
    private static List<List<Integer>> pastBids(SkullKingMatchState match, int round, int n) {
        if (match == null) {
            return List.of();
        }
        List<List<Integer>> result = new ArrayList<>();
        for (SkullKingMatchState.CompletedRound done : match.completedRounds()) {
            if (done.roundNumber() >= round) {
                continue;
            }
            List<Integer> bids = new ArrayList<>(n);
            for (int s = 0; s < n; s++) {
                RoundScore score = done.scores().get(s);
                bids.add(score == null ? PlayerState.NO_BID : score.bid());
            }
            result.add(bids);
        }
        return result;
    }
}

package com.mirboard.domain.game.skullking.bid;

import java.util.List;
import java.util.stream.IntStream;

/**
 * 승수 예측의 범위 규칙 (`docs/rules-skullking.md` §5, §13-⑪).
 *
 * <p><b>상한은 라운드 번호가 아니라 손패 장수다.</b> 원문에는 예측 상한을 규정한 문장이
 * 아예 없고, 손패보다 많이 이길 수 없으므로 {@code handSize} 로 확정했다. 일반 라운드에서는
 * 두 값이 같아 차이가 안 드러나지만 <b>8인 라운드 9·10</b> 에서 갈린다 — 손패가 8장인데
 * 라운드 번호로 상한을 잡으면 달성 불가능한 9·10 예측을 허용하게 된다 (명세 함정 #3).
 */
public final class BidRules {

    private BidRules() {
    }

    /** 최소 예측 — 0 은 유효하며 적중 시 별도 점수식을 탄다 (§10). */
    public static final int MIN_BID = 0;

    /** 최대 예측 = 그 라운드의 손패 장수. */
    public static int maxBid(int handSize) {
        return handSize;
    }

    public static boolean isValid(int bid, int handSize) {
        return bid >= MIN_BID && bid <= maxBid(handSize);
    }

    /** 봇·타임아웃이 고를 수 있는 예측 전체. 손패 장수에 따라 크기가 변한다. */
    public static List<Integer> legalBids(int handSize) {
        return IntStream.rangeClosed(MIN_BID, maxBid(handSize)).boxed().toList();
    }
}

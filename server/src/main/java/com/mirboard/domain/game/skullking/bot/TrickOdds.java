package com.mirboard.domain.game.skullking.bot;

import com.mirboard.domain.game.skullking.card.SkullCard;
import com.mirboard.domain.game.skullking.card.SpecialKind;
import com.mirboard.domain.game.skullking.card.TigressMode;
import com.mirboard.domain.game.skullking.state.PlayedCard;
import com.mirboard.domain.game.skullking.trick.TrickResolver;
import java.util.ArrayList;
import java.util.List;

/**
 * D-119 — "이 카드를 지금 내면 이 트릭을 가져갈 확률" 추정 (순수 정적 함수).
 *
 * <p><b>룰을 복제하지 않는다.</b> 판정은 전부 {@link TrickResolver} 에 맡긴다 — 정적
 * 승률표를 두면 사다리(§7) 지식을 숫자로 한 번 더 쓰는 셈이라 룰이 바뀌면 조용히 어긋난다.
 * 모델이 하는 일은 하나다: 미공개 카드 {@code u} 를 한 장씩 트릭 끝에 더해 보고 승패를
 * 뒤집는 {@code u} 의 수 F 를 센 뒤, 내 뒤의 k 명이 미공개 U 장에서 비복원으로 한 장씩
 * 낸다고 보고 아무도 뒤집지 않을 확률을 곱으로 낸다.
 *
 * <pre>
 *   noFlip = Π_{i=0}^{k-1} (U − F − i) / (U − i)      (U − F − i ≤ 0 이면 0)
 *   p      = winsNow ? noFlip : 1 − noFlip
 * </pre>
 *
 * <p>정확도: 현재 트릭 + 미공개 1장 조합까지는 정확하고, 마지막 순번(k=0)이면 정확히 0 또는
 * 1 이다. 미공개 2장 이상이 맞물리는 경우는 근사다 — 예: 인어 리드 뒤 해적과 스컬킹이 함께
 * 나오면 3자 예외로 인어가 이기지만, 모델은 "해적이 뒤집는다"만 보고 0 으로 본다. 상대가
 * follow 의무·전략 없이 균등하게 낸다는 가정도 근사다 (`docs/rules-skullking.md` §16).
 *
 * <p>사칙연산만 쓴다({@code pow} 없음) — JEP 306 (Java 17+) 의 strictfp 기본화로 플랫폼과
 * 무관하게 비트 단위로 재현된다.
 */
public final class TrickOdds {

    /** 가상 좌석 — 미공개 카드를 "누군가 낸 카드"로 트릭에 더할 때 쓴다. */
    private static final int SOMEONE_ELSE = -1;

    private TrickOdds() {
    }

    /**
     * @param played       현재 트릭에 이미 나온 카드 (제출 순)
     * @param mine         내가 낼 카드 — 좌석은 내 좌석 (티그리스면 선언 포함)
     * @param playersAfter 이번 트릭에서 내 뒤에 낼 사람 수
     * @param unseen       미공개 카드 풀 ({@link SkullKingBotView#unseenCards()})
     * @return 0.0 ~ 1.0. {@code playersAfter == 0} 이거나 풀이 비면 정확히 0 또는 1
     */
    public static double winProbability(List<PlayedCard> played, PlayedCard mine,
                                        int playersAfter, List<SkullCard> unseen) {
        List<PlayedCard> now = new ArrayList<>(played.size() + 2);
        now.addAll(played);
        now.add(mine);
        boolean winsNow = winnerIsMine(now, mine);
        if (playersAfter <= 0 || unseen.isEmpty()) {
            return winsNow ? 1.0 : 0.0;
        }

        int flips = 0;
        now.add(null);  // 마지막 칸을 미공개 카드 자리로 재사용한다
        int last = now.size() - 1;
        for (SkullCard card : unseen) {
            now.set(last, asThreat(card));
            if (winnerIsMine(now, mine) != winsNow) {
                flips++;
            }
        }

        int total = unseen.size();
        double noFlip = 1.0;
        for (int i = 0; i < playersAfter; i++) {
            int safe = total - flips - i;
            if (safe <= 0) {
                noFlip = 0.0;
                break;
            }
            // flips ≥ 0 이므로 safe > 0 이면 분모(total − i)도 양수다.
            noFlip *= (double) safe / (total - i);
        }
        return winsNow ? noFlip : 1.0 - noFlip;
    }

    private static boolean winnerIsMine(List<PlayedCard> trick, PlayedCard mine) {
        return TrickResolver.resolve(trick).winnerSeat() == mine.seat();
    }

    /** 미공개 티그리스는 해적 선언으로 본다 — 상대에게 위협이 최대인 쪽. */
    private static PlayedCard asThreat(SkullCard card) {
        return card.is(SpecialKind.TIGRESS)
                ? PlayedCard.tigress(SOMEONE_ELSE, TigressMode.PIRATE)
                : PlayedCard.of(SOMEONE_ELSE, card);
    }
}

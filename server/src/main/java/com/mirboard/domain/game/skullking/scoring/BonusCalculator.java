package com.mirboard.domain.game.skullking.scoring;

import com.mirboard.domain.game.skullking.state.EffectiveKind;
import com.mirboard.domain.game.skullking.state.PlayedCard;
import com.mirboard.domain.game.skullking.state.TrickResult;
import java.util.List;

/**
 * 보너스 점수 (`docs/rules-skullking.md` §11).
 *
 * <p><b>예측에 성공한 사람만 받는다</b> — 실패하면 획득 카드와 무관하게 전부 소멸한다.
 * 그 게이트는 호출자({@link RoundScorer})가 쥐고 있고, 본 클래스는 "적중했다면 얼마인가"만
 * 계산한다.
 *
 * <p><b>"잡았다"는 공존이 아니라 관계다 (§13-⑨, 명세 함정 #7).</b> 원문이 보너스 성립
 * 조건을 "트릭에서 얻은 카드 중 다음 카드가 포함되어 있다면"이라는 단순 포함으로 써 놓고
 * 정작 항목은 "해적으로 잡은 인어"처럼 관계형으로 쓴다. 인어·스컬킹·해적이 다 나온 트릭을
 * 인어가 이기면 포함 기준으로는 40+30+20=<b>90점</b>, 관계 기준으로는 <b>40점</b>이다.
 * 진 해적은 아무것도 가져가지 못했으므로 "잡았다"의 주체가 될 수 없다 → 관계 기준.
 *
 * <p>구현상으로는 {@link TrickResult#defeated()} (승리 카드를 뺀 나머지) 만 세는 것으로
 * 관계 기준이 그대로 성립한다.
 */
public final class BonusCalculator {

    /** 노랑·보라·초록 14 (§11). */
    public static final int FOURTEEN = 10;

    /** 검정 14 는 두 배 (§11). */
    public static final int BLACK_FOURTEEN = 20;

    /** 해적으로 이긴 트릭에 있던 인어 1장당. */
    public static final int MERMAID_TAKEN_BY_PIRATE = 20;

    /** 스컬킹으로 이긴 트릭에 있던 해적 1장당 (해적 선언 티그리스 포함, §13-⑩). */
    public static final int PIRATE_TAKEN_BY_SKULL_KING = 30;

    /** 인어로 스컬킹을 이겼을 때. */
    public static final int SKULL_KING_TAKEN_BY_MERMAID = 40;

    private BonusCalculator() {
    }

    /** 한 라운드에 가져간 트릭 전부의 보너스 합. */
    public static int bonusFor(List<TrickResult> wonTricks) {
        int total = 0;
        for (TrickResult trick : wonTricks) {
            total += fourteenBonus(trick);
            total += captureBonus(trick);
        }
        return total;
    }

    /**
     * 색상 14 보너스. 이쪽은 관계가 아니라 <b>단순 포함</b>이다 — 원문이 "트릭에서 얻은
     * 카드 중 포함되어 있다면"이라고 쓰고 승패 관계를 언급하지 않는다. 자기가 낸 14 도
     * 자기가 그 트릭을 가져갔으면 센다.
     */
    private static int fourteenBonus(TrickResult trick) {
        int total = 0;
        for (PlayedCard pc : trick.cards()) {
            if (pc.card().isBonusFourteen()) {
                total += pc.card().isTrump() ? BLACK_FOURTEEN : FOURTEEN;
            }
        }
        return total;
    }

    /** 캐릭터 포획 보너스 — 이긴 카드가 무엇이었는지로 갈린다. */
    private static int captureBonus(TrickResult trick) {
        List<PlayedCard> defeated = trick.defeated();
        return switch (trick.winningCard().kind()) {
            case PIRATE -> MERMAID_TAKEN_BY_PIRATE * count(defeated, EffectiveKind.MERMAID);
            case SKULL_KING -> PIRATE_TAKEN_BY_SKULL_KING * count(defeated, EffectiveKind.PIRATE);
            case MERMAID -> SKULL_KING_TAKEN_BY_MERMAID
                    * count(defeated, EffectiveKind.SKULL_KING);
            case SUIT, ESCAPE -> 0;
        };
    }

    private static int count(List<PlayedCard> cards, EffectiveKind kind) {
        return (int) cards.stream().filter(pc -> pc.isKind(kind)).count();
    }
}

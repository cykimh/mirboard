package com.mirboard.domain.game.skullking.trick;

import com.mirboard.domain.game.skullking.card.SkullSuit;
import com.mirboard.domain.game.skullking.state.EffectiveKind;
import com.mirboard.domain.game.skullking.state.PlayedCard;
import com.mirboard.domain.game.skullking.state.TrickResult;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * 트릭 승자 판정 (`docs/rules-skullking.md` §7).
 *
 * <p>해적 &gt; 인어, 인어 &gt; 스컬킹, 스컬킹 &gt; 해적 은 <b>비추이적 3자 순환</b>이라
 * 대소 비교로 표현할 수 없고, 여기에 "셋이 다 나오면 인어 승"이라는 명시 예외가 얹힌다.
 * 명세가 8조합 전수 대조로 이 전체가 아래 <b>순차 사다리</b>와 정확히 일치함을 확인했다
 * (불일치 0건).
 *
 * <pre>
 * 1. 스컬킹 &amp;&amp; 인어  → 인어    (3자 예외 + 인어&gt;스컬킹 을 한 줄로 흡수)
 * 2. 스컬킹          → 스컬킹
 * 3. 해적            → 해적
 * 4. 인어            → 인어
 * 5. 색상 카드       → §7.1 (검정 우선, 없으면 리드 수트)
 * 6. (전부 탈출)     → 먼저 낸 탈출
 * </pre>
 *
 * <p>명세 구현 지침: <i>"조건 분기를 늘리지 말고 위 사다리를 테이블/순차 규칙으로 구현한다.
 * if 를 조합별로 쓰면 8조합 × 동점 처리로 폭발하고, 상급자 카드를 넣을 때 다시 깨진다."</i>
 * → {@link #LADDER} 가 그 테이블이다. 상급자 카드(§14)를 넣게 되면 단을 추가한다.
 *
 * <p>동점(§8)은 사다리가 처리하지 않는다 — 각 단의 selector 가 전부
 * {@link #firstOfKind} 라서 "승자 종류가 정해진 뒤 그 종류가 여러 장이면 먼저 낸 사람"이
 * 자동으로 성립하고, 그 결과 §13-①(인어 2장 + 해적 + 스컬킹)·§13-③(해적보다 먼저 낸
 * 티그리스)이 별도 분기 없이 맞는다.
 */
public final class TrickResolver {

    /** 사다리 한 단 — 적용 조건 + 승자 선택. */
    private record Rung(Predicate<Context> applies, ToIntFunction<Context> pick) {
    }

    /** 한 트릭의 판정 입력. 사다리 각 단이 공유한다. */
    private record Context(List<PlayedCard> played, Optional<SkullSuit> leadSuit) {

        boolean has(EffectiveKind kind) {
            return played.stream().anyMatch(pc -> pc.isKind(kind));
        }
    }

    private static final List<Rung> LADDER = List.of(
            // 1. 스컬킹+인어 → 인어 (3자 예외)
            new Rung(c -> c.has(EffectiveKind.SKULL_KING) && c.has(EffectiveKind.MERMAID),
                    c -> firstOfKind(c, EffectiveKind.MERMAID)),
            // 2. 스컬킹 → 스컬킹
            new Rung(c -> c.has(EffectiveKind.SKULL_KING),
                    c -> firstOfKind(c, EffectiveKind.SKULL_KING)),
            // 3. 해적 → 해적
            new Rung(c -> c.has(EffectiveKind.PIRATE),
                    c -> firstOfKind(c, EffectiveKind.PIRATE)),
            // 4. 인어 → 인어
            new Rung(c -> c.has(EffectiveKind.MERMAID),
                    c -> firstOfKind(c, EffectiveKind.MERMAID)),
            // 5. 색상 카드 → §7.1
            new Rung(c -> c.has(EffectiveKind.SUIT),
                    TrickResolver::highestSuitCard),
            // 6. (전부 탈출) → 먼저 낸 탈출
            new Rung(c -> true,
                    c -> firstOfKind(c, EffectiveKind.ESCAPE)));

    private TrickResolver() {
    }

    /**
     * 완성된 트릭의 승자를 판정한다.
     *
     * @param played 제출 순서대로의 카드들 (비어 있으면 안 됨)
     */
    public static TrickResult resolve(List<PlayedCard> played) {
        if (played.isEmpty()) {
            throw new IllegalArgumentException("Cannot resolve an empty trick");
        }
        Context ctx = new Context(played, LeadSuitResolver.resolve(played));

        for (Rung rung : LADDER) {
            if (rung.applies().test(ctx)) {
                int index = rung.pick().applyAsInt(ctx);
                PlayedCard winner = played.get(index);
                return new TrickResult(winner.seat(), winner, played);
            }
        }
        // 마지막 단의 조건이 상수 true 라 도달 불가.
        throw new IllegalStateException("Trick ladder fell through: " + played);
    }

    /** §8 — 그 종류를 가장 먼저 낸 사람. */
    private static int firstOfKind(Context c, EffectiveKind kind) {
        List<PlayedCard> played = c.played();
        for (int i = 0; i < played.size(); i++) {
            if (played.get(i).isKind(kind)) {
                return i;
            }
        }
        throw new IllegalStateException("Rung claimed " + kind + " but none present: " + played);
    }

    /**
     * §7.1 색상 카드끼리의 판정. 이 단에 도달했다면 캐릭터는 트릭에 없고, 남은 후보는
     * 색상 카드와 탈출뿐이다 — 탈출은 반드시 패배하므로 후보에서 빠진다 (§13-⑧).
     *
     * <p>검정이 하나라도 있으면 검정 중 최대, 없으면 리드 수트 중 최대. 리드 수트가 아닌
     * 비-검정은 숫자가 아무리 커도 진다. 검정이 오프수트여도 이기는 것은 각주 [4] 가
     * 아니라 본문(으뜸패 정의)을 따른 결과다 (§13-⑦).
     */
    private static int highestSuitCard(Context c) {
        List<PlayedCard> played = c.played();
        boolean blackPresent = played.stream()
                .anyMatch(pc -> pc.isKind(EffectiveKind.SUIT) && pc.card().isTrump());

        // 검정이 있으면 검정끼리, 없으면 리드 수트끼리 겨룬다.
        // 이 단에 온 이상 색상 카드가 최소 1장 있고, 그러면 리드 수트는 반드시 확정돼
        // 있다 (캐릭터 리드였다면 위 단에서 걸렸고, 탈출 리드라도 그 색상 카드가
        // 리드 수트를 확정시킨다) — LeadSuitResolver 참조.
        SkullSuit contested = blackPresent
                ? SkullSuit.BLACK
                : c.leadSuit().orElseThrow(() -> new IllegalStateException(
                        "Suit card present but no lead suit resolved: " + played));

        int best = -1;
        int bestRank = Integer.MIN_VALUE;
        for (int i = 0; i < played.size(); i++) {
            PlayedCard pc = played.get(i);
            if (!pc.isKind(EffectiveKind.SUIT) || pc.card().suit() != contested) {
                continue;
            }
            if (pc.card().rank() > bestRank) {
                bestRank = pc.card().rank();
                best = i;
            }
        }
        if (best < 0) {
            throw new IllegalStateException(
                    "No card of contested suit " + contested + " in: " + played);
        }
        return best;
    }
}

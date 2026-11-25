package com.mirboard.domain.game.wordtemp.bot;

import com.mirboard.domain.game.wordtemp.rules.HintRules;
import com.mirboard.domain.game.wordtemp.rules.WordNormalizer;
import com.mirboard.domain.game.wordtemp.words.AnswerEntry;
import com.mirboard.domain.game.wordtemp.words.SimilarityTable;
import com.mirboard.domain.game.wordtemp.words.SimilarityTable.Neighbor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * 낱말 온도 봇 정책(`docs/rules-wordtemp.md` §11) — 순수·결정적(같은 뷰·같은 씨앗이면 같은 결정). 봇은 <b>표 안의 낱말만</b> 쓴다(실시간 채점
 * 호출 0)이고, 아는 것은 뷰에 담긴 것뿐이다: 출제 봇은 자기 정답과 표 이웃, 추리 봇은 <b>공개된</b> 힌트·온도·틀린 답과 표.
 * 추리 봇은 정답을 받지 않는다 — 그래서 "맞힐 확률 상한"은 정답을 아는 쪽의 보정이 아니라 "가장 그럴듯한 답을 그대로 내는 확률"로 건다(D-315).
 */
public final class WordTempBotPolicy {

    /** 추리 봇이 표에 없는 (후보, 힌트) 쌍에 어림하는 온도 — "관련 없음"에 가깝게. 채점에는 쓰이지 않는다(표 밖 = 모름, §6). */
    static final int UNKNOWN_TEMP = 20;
    /** 곁들이는 힌트(가장 높은 힌트가 아닌 것)의 아래 한계 — 너무 엉뚱한 낱말은 내지 않는다. */
    static final int DECOY_MIN_TEMP = 20;

    private final SimilarityTable table;

    public WordTempBotPolicy(SimilarityTable table) {
        this.table = table;
    }

    // ---- 출제 ----

    /**
     * @param answer            내 정답
     * @param floor             이 차수 최저 온도
     * @param closeTemp         이 온도 이상은 힌트 단계를 끝내 버린다 — 피한다
     * @param maxHints          차수당 상한
     * @param usedEarlier       앞 차수들에서 이미 낸 힌트(이 출제 차례)
     * @param countThisAttempt  이번 차수에 이미 낸 개수
     * @param planSeed          이 (판, 출제 차례, 차수, 좌석)의 씨앗 — 같은 차수 안에서는 같은 계획이 다시 나온다
     */
    public record SetterView(AnswerEntry answer, int floor, int closeTemp, int maxHints, Set<String> usedEarlier,
                             int countThisAttempt, WordTempBotLevel level, long planSeed) {
    }

    /** 다음에 낼 힌트. 빈 값 = [힌트 끝](낼 것이 없거나 계획을 다 냄). */
    public Optional<String> nextHint(SetterView view) {
        List<String> plan = hintPlan(view);
        return view.countThisAttempt() < plan.size() ? Optional.of(plan.get(view.countThisAttempt()))
                : Optional.empty();
    }

    /** 이번 차수의 힌트 계획 — 맨 앞이 목표 띠에 드는 "가장 높은 힌트", 뒤는 그보다 낮은 곁들이 힌트. */
    List<String> hintPlan(SetterView view) {
        SplittableRandom rng = new SplittableRandom(view.planSeed());
        WordTempBotLevel level = view.level();
        int count = Math.min(view.maxHints(), level.minHints() + rng.nextInt(level.maxHints() - level.minHints() + 1));
        List<Neighbor> usable = new ArrayList<>();
        for (Neighbor n : table.neighbors(view.answer().word())) {
            if (n.temp() < view.closeTemp() && !view.usedEarlier().contains(n.hint())
                    && WordNormalizer.normalize(n.hint()).filter(n.hint()::equals).isPresent()
                    && HintRules.containment(view.answer(), n.hint()).isEmpty()) {
                usable.add(n);
            }
        }
        if (usable.isEmpty() || count <= 0) {
            return List.of();
        }
        int low = level.bandLow(view.floor());
        int high = level.bandHigh(view.floor());
        List<Neighbor> inBand = usable.stream().filter(n -> n.temp() >= low && n.temp() <= high).toList();
        Neighbor main;
        if (!inBand.isEmpty()) {
            main = inBand.get(rng.nextInt(inBand.size()));
        } else {
            // 띠가 비면 띠에 가장 가까운 것(같으면 표의 앞 순서).
            main = usable.stream().min(Comparator.comparingInt(n -> distance(n.temp(), low, high))).orElseThrow();
        }
        List<String> plan = new ArrayList<>(count);
        plan.add(main.hint());
        List<Neighbor> decoys = new ArrayList<>(usable.stream()
                .filter(n -> n != main && n.temp() < main.temp() && n.temp() >= DECOY_MIN_TEMP).toList());
        while (plan.size() < count && !decoys.isEmpty()) {
            plan.add(decoys.remove(rng.nextInt(decoys.size())).hint());
        }
        return plan;
    }

    private static int distance(int temp, int low, int high) {
        return temp < low ? low - temp : temp > high ? temp - high : 0;
    }

    // ---- 추리 ----

    /** 공개 힌트 하나. */
    public record Clue(String hint, int temp) {
    }

    /**
     * @param clues         지금까지 공개된 힌트·온도(이 출제 차례)
     * @param ruledOut      내지 않을 낱말 — 이 출제 차례에 공개된 틀린 답, 내 후보 낱말
     * @param knowledgeSeed 이 (판, 좌석)의 씨앗 — "아는 은행"이 판 내내 같다
     * @param decisionSeed  이 (판, 출제 차례, 차수, 좌석)의 씨앗
     */
    public record GuesserView(List<Clue> clues, Set<String> ruledOut, WordTempBotLevel level, long knowledgeSeed,
                              long decisionSeed) {
    }

    /** 낼 답. 빈 문자열 = 패스. */
    public String guess(GuesserView view) {
        List<String> ranked = rank(view);
        if (ranked.isEmpty()) {
            return "";
        }
        SplittableRandom rng = new SplittableRandom(mix(view.decisionSeed(), 0x6A09E667F3BCC909L));
        if (rng.nextInt(100) < view.level().hitCapPct()) {
            return ranked.get(0);
        }
        return ranked.size() >= 2 && rng.nextBoolean() ? ranked.get(1) : "";
    }

    /** 아는 은행 안에서 공개 힌트·온도와 표가 가장 잘 맞는 순서(잡음 섞어 읽은 온도 기준). */
    List<String> rank(GuesserView view) {
        if (view.clues().isEmpty()) {
            return List.of();
        }
        SplittableRandom rng = new SplittableRandom(view.decisionSeed());
        int noise = view.level().noise();
        int[] read = new int[view.clues().size()];
        for (int i = 0; i < read.length; i++) {
            read[i] = view.clues().get(i).temp() + rng.nextInt(2 * noise + 1) - noise;
        }
        record Scored(String word, int cost, int index) {
        }
        List<Scored> scored = new ArrayList<>();
        List<String> answers = table.answers();
        for (int index = 0; index < answers.size(); index++) {
            String candidate = answers.get(index);
            if (!knows(view, candidate) || view.ruledOut().contains(candidate) || excludedByClues(view, candidate)) {
                continue;
            }
            int cost = 0;
            for (int i = 0; i < read.length; i++) {
                cost += Math.abs(read[i] - table.lookup(candidate, view.clues().get(i).hint()).orElse(UNKNOWN_TEMP));
            }
            scored.add(new Scored(candidate, cost, index));
        }
        scored.sort(Comparator.comparingInt(Scored::cost).thenComparingInt(Scored::index));
        return scored.stream().map(Scored::word).toList();
    }

    /** 이 봇이 아는 낱말인가 — 은행의 {@code knownPct}% (정답이 그 안에 드는지도 운이다). */
    static boolean knows(GuesserView view, String answer) {
        int pct = view.level().knownPct();
        return pct >= 100 || new SplittableRandom(mix(view.knowledgeSeed(), answer.hashCode())).nextInt(100) < pct;
    }

    /** 힌트는 정답을 품을 수 없고 2자 이상이면 정답 안에 들 수도 없다(§5.2) — 그런 후보는 정답이 아니다. */
    private static boolean excludedByClues(GuesserView view, String candidate) {
        AnswerEntry entry = AnswerEntry.of(candidate, "");
        for (Clue clue : view.clues()) {
            if (HintRules.containment(entry, clue.hint()).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /** 씨앗 섞기(결정적). */
    public static long mix(long a, long b) {
        long z = a ^ (b * 0x9E3779B97F4A7C15L + 0xBF58476D1CE4E5B9L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}

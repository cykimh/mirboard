package com.mirboard.domain.game.wordtemp;

import com.mirboard.domain.game.wordtemp.words.AnswerEntry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * 시뮬레이션·봇 테스트용 <b>합성</b> 정답 은행과 유사도 표 — 진짜 낱말이 아니라 무작위 음절 묶음이다(진짜 표는 S1 데이터 작업이 만든다).
 * 띠 분포만 설계서 §5.1 의 "은행에 남기는 기준"을 흉내 낸다: 정답마다 96 이상 1~4개, 75~95 약 18~30개, 40~74 약 60~90개, 40 미만 약 40~70개.
 * 힌트 어휘는 정답들이 나눠 쓴다(같은 낱말이 여러 정답의 이웃 — 추리 봇의 역추적이 뜻이 있게).
 */
public final class SyntheticWords {

    private static final String SYLLABLES = "가나다라마바사아자차카타파하고노도로모보소오조초코토포호구누두루무부수우주추"
            + "기니디리미비시이지치강남단달말발살알잘장정청풍향";

    public final List<AnswerEntry> bank;
    public final InMemorySimilarityTable table;
    /** 힌트 어휘(정답·같은 말과 겹치지 않는다). */
    public final List<String> vocabulary;

    private SyntheticWords(List<AnswerEntry> bank, InMemorySimilarityTable table, List<String> vocabulary) {
        this.bank = List.copyOf(bank);
        this.table = table;
        this.vocabulary = List.copyOf(vocabulary);
    }

    public static SyntheticWords generate(long seed, int answers, int vocabularySize) {
        SplittableRandom rng = new SplittableRandom(seed);
        Set<String> seen = new HashSet<>();
        List<String> vocabulary = new ArrayList<>(vocabularySize);
        while (vocabulary.size() < vocabularySize) {
            String w = word(rng, 2 + rng.nextInt(2));
            if (seen.add(w)) {
                vocabulary.add(w);
            }
        }
        List<AnswerEntry> bank = new ArrayList<>(answers);
        while (bank.size() < answers) {
            String w = word(rng, 3 + rng.nextInt(2));
            if (!seen.add(w)) {
                continue;
            }
            List<String> aliases = new ArrayList<>();
            if (rng.nextInt(10) == 0) {
                String alias = word(rng, 4);
                if (seen.add(alias)) {
                    aliases.add(alias);
                }
            }
            bank.add(new AnswerEntry(w, "범주" + (bank.size() % 12), "", aliases));
        }
        InMemorySimilarityTable table = new InMemorySimilarityTable();
        for (AnswerEntry e : bank) {
            Set<Integer> used = new HashSet<>();
            addBand(table, e, vocabulary, used, rng, 1 + rng.nextInt(4), 96, 99);
            addBand(table, e, vocabulary, used, rng, 18 + rng.nextInt(13), 75, 95);
            addBand(table, e, vocabulary, used, rng, 60 + rng.nextInt(31), 40, 74);
            addBand(table, e, vocabulary, used, rng, 40 + rng.nextInt(31), 5, 39);
        }
        return new SyntheticWords(bank, table, vocabulary);
    }

    private static void addBand(InMemorySimilarityTable table, AnswerEntry answer, List<String> vocabulary,
                                Set<Integer> used, SplittableRandom rng, int count, int low, int high) {
        int added = 0;
        while (added < count) {
            int i = rng.nextInt(vocabulary.size());
            String hint = vocabulary.get(i);
            // 포함 규칙에 걸리는 낱말은 표에 싣지 않는다(진짜 표의 검증 단계와 같다 — 설계서 §5.2-4)
            if (!used.add(i) || hint.contains(answer.word()) || answer.word().contains(hint)) {
                continue;
            }
            table.put(answer.word(), hint, low + rng.nextInt(high - low + 1));
            added++;
        }
    }

    private static String word(SplittableRandom rng, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(SYLLABLES.charAt(rng.nextInt(SYLLABLES.length())));
        }
        return sb.toString();
    }
}

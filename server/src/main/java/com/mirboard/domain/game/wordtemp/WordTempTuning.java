package com.mirboard.domain.game.wordtemp;

import java.util.List;

/**
 * 조정할 수 있는 수치(`docs/rules-wordtemp.md` §15 [수치]) — 코드 상수로 흩지 않고 한 곳에 모은다. 시뮬레이션이 다른 값으로 돌려 보고
 * (`server/build/wordtemp-sim-stats.txt`), 바꿀 때는 룰 문서·결정과 함께 {@link #DEFAULT} 를 고친다.
 *
 * @param floors       차수별 최저 온도(1·2·3차) — 공개 힌트가 이 값 미만이면 전원 오답이어도 출제자 0점
 * @param closeTemp    이 온도 이상의 힌트가 나오면 힌트 단계가 그 자리에서 끝난다
 * @param maxHints     차수당 힌트 상한
 * @param maxAttempts  출제 차례당 차수 상한
 * @param selectMs     낱말 고르기
 * @param guessMs      추리
 * @param resultMs     차수 결과 보기
 * @param turnEndMs    출제 차례 끝(정답 공개)
 * @param liveWaitMs   실시간 채점 기다림 상한 — 지나면 그 힌트는 무효(개수 반환)
 * @param botHintMinMs 봇 힌트 사이 간격(최소~최대)
 * @param botHintMaxMs 〃
 * @param botGuessMinMs 봇 추리 시각(추리 단계 시작부터, 최소~최대) — 사람 추리자가 모두 내면 봇도 그 자리에서 낸다
 * @param botGuessMaxMs 〃
 * @param botQuickMs   사람이 기다릴 것이 없는 단계(추리자가 전원 봇)의 봇 간격 상한
 */
public record WordTempTuning(List<Integer> floors,
                             int closeTemp,
                             int maxHints,
                             int maxAttempts,
                             long selectMs,
                             long guessMs,
                             long resultMs,
                             long turnEndMs,
                             long liveWaitMs,
                             long botHintMinMs,
                             long botHintMaxMs,
                             long botGuessMinMs,
                             long botGuessMaxMs,
                             long botQuickMs) {

    /** 룰 v1 의 값(D-311). */
    public static final WordTempTuning DEFAULT = new WordTempTuning(List.of(50, 60, 70), 96, 5, 3,
            20_000L, 30_000L, 5_000L, 6_000L, 8_000L, 4_000L, 9_000L, 5_000L, 20_000L, 1_500L);

    public WordTempTuning {
        floors = List.copyOf(floors);
        if (floors.size() != maxAttempts) {
            throw new IllegalArgumentException("floors must have one value per attempt: " + floors);
        }
    }

    /** 차수(1 부터)의 최저 온도. */
    public int floor(int attempt) {
        return floors.get(attempt - 1);
    }

    public WordTempTuning withFloors(int first, int second, int third) {
        return new WordTempTuning(List.of(first, second, third), closeTemp, maxHints, maxAttempts, selectMs, guessMs,
                resultMs, turnEndMs, liveWaitMs, botHintMinMs, botHintMaxMs, botGuessMinMs, botGuessMaxMs, botQuickMs);
    }

    /**
     * 시간만 배율로 줄인 수치(S2) — 낱말 고르기·추리·결과·차례 끝·봇 간격. 운영은 1.0 이고 봇끼리 도는 통합 테스트만 줄인다
     * ({@code mirboard.wordtemp.time-scale}). 힌트 제한(방 설정)과 채점 기다림 상한은 줄이지 않는다.
     */
    public WordTempTuning scaled(double scale) {
        if (scale == 1.0) {
            return this;
        }
        return new WordTempTuning(floors, closeTemp, maxHints, maxAttempts, scale(selectMs, scale), scale(guessMs, scale),
                scale(resultMs, scale), scale(turnEndMs, scale), liveWaitMs, scale(botHintMinMs, scale),
                scale(botHintMaxMs, scale), scale(botGuessMinMs, scale), scale(botGuessMaxMs, scale),
                scale(botQuickMs, scale));
    }

    private static long scale(long ms, double scale) {
        return Math.max(1L, Math.round(ms * scale));
    }
}

package com.mirboard.domain.game.wordtemp.state;

import java.util.List;
import java.util.Set;

/**
 * 누가 언제 무엇을 보나(`docs/rules-wordtemp.md` §12 의 표) — <b>판정은 여기 한 곳</b>이다. 공개 뷰·비공개 뷰({@link WordTempViews})와 엔진의
 * 비공개 이벤트가 모두 이 함수들을 거친다. 비밀을 내보내는 새 경로를 만들면 여기를 거칠 것.
 *
 * <p>{@code viewer} 는 좌석 번호이고 범위 밖(관전자 −1)·빠진 좌석은 어떤 비밀도 못 본다. 봇 좌석을 거르는 것은
 * {@link #privateRecipients}(봇 집합을 받는다)다 — 상태는 봇 좌석을 모른다.
 */
public final class WordTempVisibility {

    private WordTempVisibility() {
    }

    /** 비공개 정보를 받을 수 있는 좌석 — 살아 있는 <b>사람</b>만(봇·빠진 좌석·관전자 제외). */
    public static List<Integer> privateRecipients(WordTempState state, Set<Integer> bots) {
        return state.aliveSeats().stream().filter(s -> !bots.contains(s)).toList();
    }

    /** {@code owner} 의 후보 낱말·고른 낱말을 {@code viewer} 가 보는가 — 본인만. 안 쓴 후보는 판이 끝나도 남에게 공개하지 않는다. */
    public static boolean seesCandidates(WordTempState state, int viewer, int owner) {
        return viewer == owner && state.alive(viewer);
    }

    /** 이번 정답 낱말을 {@code viewer} 가 <b>비공개로</b> 보는가 — 진행 중인 출제 차례의 출제자만. */
    public static boolean seesAnswer(WordTempState state, int viewer) {
        return state.turnInProgress() && viewer == state.setterSeat() && state.alive(viewer);
    }

    /** 이번 출제 차례의 힌트 목록·각 온도·채점 중 표시를 {@code viewer} 가 비공개로 보는가 — 출제자만(정답과 같은 조건). */
    public static boolean seesHints(WordTempState state, int viewer) {
        return seesAnswer(state, viewer);
    }

    /** 공개 전의 내 추리 답을 {@code viewer} 가 보는가 — 추리 단계의 추리자 본인만(출제자도 남의 답을 못 본다). */
    public static boolean seesOwnGuess(WordTempState state, int viewer) {
        return state.phase() == Phase.GUESS && viewer != state.setterSeat() && state.alive(viewer);
    }

    /** 정답 낱말과 그 차례의 힌트 전체·온도가 <b>모두에게</b> 공개돼 있는가 — 출제 차례 끝(`TURN_END`) 동안만. */
    public static boolean turnSecretsPublic(WordTempState state) {
        return state.phase() == Phase.TURN_END;
    }

    /** 가려야 하는 답인가 — 금칙어를 품은 답은 남에게 가려 보인다(본인은 자기가 낸 답을 안다). */
    public static boolean masked(String normalizedGuess, Set<String> blockedWords) {
        if (normalizedGuess == null || normalizedGuess.isEmpty()) {
            return false;
        }
        for (String blocked : blockedWords) {
            if (!blocked.isEmpty() && normalizedGuess.contains(blocked)) {
                return true;
            }
        }
        return false;
    }
}

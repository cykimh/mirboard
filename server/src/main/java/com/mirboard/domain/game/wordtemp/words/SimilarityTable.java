package com.mirboard.domain.game.wordtemp.words;

import java.util.List;
import java.util.OptionalInt;

/**
 * 유사도 표(`docs/rules-wordtemp.md` §6.1, D-312) — (정답, 힌트) → 온도 0~100. 엔진·봇은 이 인터페이스만 본다. 운영 구현은 부팅 때 읽어 둔
 * 정렬 배열(S1 데이터 작업·S2), 테스트는 메모리 표를 쓴다. <b>조회는 메모리에서 끝나야 한다</b> — 방 잠금 안에서 불린다.
 *
 * <p>표에 없는 쌍은 "모름"이지 "낮은 점수"가 아니다({@link OptionalInt#empty()}).
 */
public interface SimilarityTable {

    /** 정규화된 (정답, 힌트)의 온도. 표에 없으면 빈 값. */
    OptionalInt lookup(String answer, String hint);

    /** 정답의 표 이웃 전부(순서는 구현이 고정 — 같은 표면 늘 같은 순서). 정답이 표에 없으면 빈 목록. 봇이 쓴다(§11). */
    List<Neighbor> neighbors(String answer);

    /** 표가 아는 정답 낱말 전부(고정 순서). 추리 봇의 "아는 은행"의 바탕(§11). */
    List<String> answers();

    /** 표 이웃 하나. */
    record Neighbor(String hint, int temp) {
    }
}

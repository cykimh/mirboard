package com.mirboard.domain.game.wordtemp.feedback;

import java.time.Instant;
import java.util.List;

/**
 * "온도가 이상해요" 신고의 저장소(D-323). <b>낱말만</b> 남긴다 — (정답, 힌트, 방향, 그때 온도, 표·채점 버전)과 횟수. 사용자·방·좌석
 * 식별자는 받지도 남기지도 않는다(개인정보 최소화). 다음 표 갱신·채점 기준문 검수의 재료다.
 */
public interface TempFeedbackStore {

    /** 같은 (정답, 힌트, 방향, 버전)이면 횟수를 올리고 마지막 본 때·온도를 갱신한다. */
    void record(String answer, String hint, String direction, int temp, String version);

    /** 많이 신고된 쌍부터. */
    List<Row> top(int limit);

    record Row(String answer, String hint, String direction, int temp, String version, int count, Instant firstSeen,
               Instant lastSeen) {
    }
}

package com.mirboard.domain.game.core;

import java.util.List;
import java.util.Map;

/**
 * 게임이 모은 <b>플레이어 신고·의견</b>을 운영자가 읽는 선택 포트(D-323) — {@link HeadToHeadSource}·{@link BestScoreSource} 처럼 게임이
 * Bean 으로 내놓으면 게임 중립 어드민 경로({@code GET /api/admin/games/{gameType}/feedback})가 찾아 쓴다. 인프라는 게임 이름도 줄의
 * 모양도 모른다 — 줄은 게임이 정한 이름·값의 표다. 개인을 가리키는 값(사용자·방·좌석)은 싣지 않는다.
 */
public interface GameFeedbackSource {

    /** 이 목록을 내놓는 게임(`GameDefinition.id()`). */
    String gameType();

    /** 많이 모인 것부터 {@code limit} 줄. */
    List<Map<String, Object>> top(int limit);
}

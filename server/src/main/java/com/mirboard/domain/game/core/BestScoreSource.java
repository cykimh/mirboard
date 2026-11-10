package com.mirboard.domain.game.core;

import java.util.OptionalInt;

/**
 * D-201 — 게임이 자기 기록에서 한 사람의 "개인 최고 점수"를 낸다. 점수를 겨루는 게임(요트)만 구현한다 — 승패만 있는 게임은 Bean 이
 * 없다. 어떤 판을 셀지(끝까지 친 판만 등)는 게임이 정하고, 게스트·봇 거르기는 모으는 쪽({@code BestScoreService})이 한다
 * ({@link HeadToHeadSource} 와 같은 모양).
 */
public interface BestScoreSource {

    /** {@link GameDefinition#id()} 와 같은 게임 id. */
    String gameType();

    /** 이 사용자의 최고 점수. 셀 판이 없으면 empty. */
    OptionalInt bestScore(long userId);
}

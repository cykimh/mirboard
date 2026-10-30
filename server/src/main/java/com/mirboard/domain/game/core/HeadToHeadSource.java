package com.mirboard.domain.game.core;

import java.util.List;

/**
 * D-154 — 게임이 자기 매치 기록에서 "나 대 상대" 승·패·무를 세어 준다(상대 전적). 무엇을 이긴 것으로 볼지는 게임이 정한다 — 티츄는
 * 상대 팀만(내 팀 승패), 스컬킹은 최종 점수, 원카드는 최종 순위. 게스트·봇 거르기와 이름 붙이기는 모으는 쪽
 * ({@code HeadToHeadService})이 한다.
 */
public interface HeadToHeadSource {

    /** {@link GameDefinition#id()} 와 같은 게임 id. */
    String gameType();

    /** 이 사용자와 같은 매치에 있었던 상대별 집계(상대가 게스트·봇이어도 그대로 — 거르는 것은 모으는 쪽). */
    List<Row> against(long userId);

    record Row(long opponentId, int wins, int losses, int draws) {
    }
}

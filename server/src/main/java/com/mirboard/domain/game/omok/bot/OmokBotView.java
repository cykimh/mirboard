package com.mirboard.domain.game.omok.bot;

import com.mirboard.domain.game.omok.OmokRule;
import com.mirboard.domain.game.omok.board.Board;
import com.mirboard.domain.game.omok.board.Stone;
import com.mirboard.domain.game.omok.state.Move;
import com.mirboard.domain.game.omok.state.OmokState;
import java.util.List;

/**
 * 봇이 보는 것 (D-118·D-119 원칙 — 공개 정보만). 오목은 상태 전부가 공개라 판·룰·내 색·수순이면 된다. 2:2 에서 팀원 돌은 그냥
 * 같은 색 돌이다.
 */
public record OmokBotView(Board board, OmokRule rule, Stone me, List<Move> moves) {

    public OmokBotView {
        moves = List.copyOf(moves);
    }

    public static OmokBotView of(OmokState state) {
        return new OmokBotView(state.board(), state.rule(), state.nextColor(), state.moves());
    }

    /** 수순 해시 — 쉬움의 "흔들림"을 무작위 없이 결정적으로 고르는 데 쓴다. */
    public int movesHash() {
        int h = 17;
        for (Move m : moves) {
            h = h * 31 + m.x() * 15 + m.y();
        }
        return h & 0x7fffffff;
    }
}

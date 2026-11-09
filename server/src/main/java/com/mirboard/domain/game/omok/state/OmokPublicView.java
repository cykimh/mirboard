package com.mirboard.domain.game.omok.state;

import com.mirboard.domain.game.omok.OmokMode;
import com.mirboard.domain.game.omok.OmokRule;
import com.mirboard.domain.game.omok.board.Stone;
import com.mirboard.domain.game.omok.rules.ForbiddenPoint;
import java.util.List;

/**
 * 공개 뷰 (설계서 §3.1-3) — 오목은 모든 상태가 공개다. 비공개 뷰는 없다({@code privateView} = empty). 봇의 탐색 결과·평가
 * 점수 같은 서버 내부 값은 여기 싣지 않는다(설계서 §3.2).
 *
 * @param seatColors 좌석별 색
 * @param forbidden  흑 차례일 때 금수 자리, 그 밖은 빈 목록
 */
public record OmokPublicView(OmokMode mode,
                             OmokRule rule,
                             List<Stone> seatColors,
                             List<Move> moves,
                             int turnSeat,
                             List<ForbiddenPoint> forbidden,
                             List<Integer> timeouts,
                             MatchResult result) {

    public OmokPublicView {
        seatColors = List.copyOf(seatColors);
        moves = List.copyOf(moves);
        forbidden = List.copyOf(forbidden);
        timeouts = List.copyOf(timeouts);
    }
}

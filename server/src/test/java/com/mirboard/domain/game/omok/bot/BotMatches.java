package com.mirboard.domain.game.omok.bot;

import com.mirboard.domain.game.omok.OmokEngine;
import com.mirboard.domain.game.omok.OmokMode;
import com.mirboard.domain.game.omok.OmokRule;
import com.mirboard.domain.game.omok.action.OmokAction;
import com.mirboard.domain.game.omok.board.Point;
import com.mirboard.domain.game.omok.board.Stone;
import com.mirboard.domain.game.omok.state.OmokState;
import java.util.List;
import java.util.Random;

/** 봇끼리 1:1 대국 — 강도 평가·시간 예산 테스트가 쓴다. 정책 null 은 무작위(합법수 균등). */
final class BotMatches {

    static final OmokEngine ENGINE = new OmokEngine();

    private BotMatches() {
    }

    /** 결과: 흑이 이겼으면 BLACK, 백이 이겼으면 WHITE, 무승부 null. */
    static Stone play(OmokRule rule, BotLevel black, BotLevel white, long seed) {
        Random rng = new Random(seed);
        OmokState s = ENGINE.startMatch(OmokMode.SOLO, rule, 2, 0, 0);
        // 여는 두 수는 가운데 5×5 에서 무작위 — 결정적인 봇끼리 같은 판만 반복하지 않게.
        for (int i = 0; i < 2; i++) {
            Point p;
            do {
                p = new Point(5 + rng.nextInt(5), 5 + rng.nextInt(5));
            } while (!s.board().isEmpty(p));
            s = ENGINE.apply(s, s.turnSeat(), new OmokAction.Place(p.x(), p.y())).newState();
        }
        while (!s.isOver()) {
            int seat = s.turnSeat();
            BotLevel level = s.colorOf(seat) == Stone.BLACK ? black : white;
            List<OmokAction> legal = ENGINE.legalActions(s, seat);
            OmokAction a = level == null
                    ? legal.get(rng.nextInt(legal.size()))
                    : OmokBotPolicy.choose(OmokBotView.of(s), legal, level);
            s = ENGINE.apply(s, seat, a).newState();
        }
        return s.result().winnerColor();
    }

    /**
     * a 의 승률(무승부 0.5) — 듀플리케이트: 여는 수 하나로 흑백을 바꿔 두 판씩, games 판. 결정적인 봇끼리는 여는 수가 판을
     * 정하므로, 색마다 다른 여는 수를 주면 여는 수의 유불리가 그대로 승률에 섞인다(같은 봇끼리도 0.5 가 안 나온다).
     */
    static double winRate(OmokRule rule, BotLevel a, BotLevel b, int games, long seed) {
        double score = 0;
        int openings = Math.max(1, games / 2);
        for (int g = 0; g < openings; g++) {
            score += points(play(rule, a, b, seed + g), Stone.BLACK);
            score += points(play(rule, b, a, seed + g), Stone.WHITE);
        }
        return score / (openings * 2);
    }

    private static double points(Stone winner, Stone aColor) {
        return winner == null ? 0.5 : winner == aColor ? 1 : 0;
    }
}

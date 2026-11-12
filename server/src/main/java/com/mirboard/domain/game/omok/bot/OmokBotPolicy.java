package com.mirboard.domain.game.omok.bot;

import com.mirboard.domain.game.omok.OmokRule;
import com.mirboard.domain.game.omok.action.OmokAction;
import com.mirboard.domain.game.omok.OmokEngine;
import com.mirboard.domain.game.omok.board.Board;
import com.mirboard.domain.game.omok.board.Point;
import com.mirboard.domain.game.omok.board.Stone;
import com.mirboard.domain.game.omok.rules.ForbiddenRule;
import com.mirboard.domain.game.omok.rules.Shapes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 오목 봇 정책 3단계 (D-179, 설계서 §4.4). 공개 정보 뷰만 보고 {@code legal}(지금 둘 수 있는 자리 — 흑이면 금수 제외)의
 * 원소를 고른다. 무작위를 쓰지 않는다 — 같은 국면이면 같은 수다(쉬움의 흔들림도 수순 해시).
 *
 * <p>계산 상한은 시간이 아니라 <b>노드 수</b>다({@link #NODE_BUDGET}). 봇은 방 액션 락(2초 TTL) 안에서 계산되고, 시간 상한은
 * 결정성을 깬다.
 *
 * <ul>
 *   <li><b>쉬움</b>: 내 5 → 둔다, 상대 5 → 막는다. 상대의 열린 3·4-3 은 수순 해시로 절반만 막고, 나머지는 점수 상위 3개 중
 *       해시로 고른다(방어 가중 0.5).
 *   <li><b>보통</b>: 필수수 규칙 — 내 5 · 상대 5 막기 · 내 열린 4/4-3 · 상대 열린 3·4-3 막기(또는 4 로 역공) · 그 밖은 공격 +
 *       0.9 × 방어 점수 최댓값.
 *   <li><b>어려움</b>({@link HardSearch}, D-191): 내 5 · 상대 5 막기 · VCF · VCT(4 와 열린 3 을 섞어 몰아 이기기) · 반복 심화
 *       알파베타 · 상대 VCT 안전 확인. 칸 점수는 줄 모양 표({@link LinePatterns})를 돌을 놓을 때마다 바뀐 줄만 고친다.
 * </ul>
 */
public final class OmokBotPolicy {

    /** 어려움 한 결정의 노드 상한(돌 놓기 1번 · 금수 판정 1번 = 노드 1). */
    public static final int NODE_BUDGET = HardSearch.DEFAULT.budget();

    private OmokBotPolicy() {
    }

    public static OmokAction choose(OmokBotView view, List<OmokAction> legal, BotLevel level) {
        Decision d = decide(view, legal, level);
        return d == null ? null : d.action();
    }

    /** 고른 수와 쓴 노드 수 — 시간 예산 테스트가 노드 상한을 확인한다. */
    record Decision(OmokAction action, int nodes) {
    }

    static Decision decide(OmokBotView view, List<OmokAction> legal, BotLevel level) {
        if (legal.isEmpty()) {
            return null;
        }
        Set<Point> legalPoints = new HashSet<>();
        for (OmokAction a : legal) {
            if (a instanceof OmokAction.Place p) {
                legalPoints.add(new Point(p.x(), p.y()));
            }
        }
        if (view.board().stoneCount() == 0) {
            Point center = OmokEngine.CENTER;
            return new Decision(place(legalPoints.contains(center) ? center : first(legalPoints)), 0);
        }
        if (level == BotLevel.HARD) {
            HardSearch h = new HardSearch(view, legalPoints, HardSearch.DEFAULT);
            Point p = h.choose();
            return new Decision(place(p != null && legalPoints.contains(p) ? p : first(legalPoints)), h.nodes);
        }
        Search s = new Search(view, legalPoints);
        Point p = level == BotLevel.EASY ? s.easy() : s.normal();
        return new Decision(place(p != null && legalPoints.contains(p) ? p : first(legalPoints)), s.nodes);
    }

    private static OmokAction place(Point p) {
        return new OmokAction.Place(p.x(), p.y());
    }

    private static Point first(Set<Point> points) {
        return points.stream().min(Comparator.comparingInt(Point::y).thenComparingInt(Point::x)).orElseThrow();
    }

    /** 한 결정 동안의 계산 문맥. */
    static final class Search {
        final byte[] cells;
        final OmokRule rule;
        final Stone me;
        final Stone opp;
        final Set<Point> legal;
        final int hash;
        int nodes;

        Search(OmokBotView view, Set<Point> legal) {
            this.cells = view.board().cells();
            this.rule = view.rule();
            this.me = view.me();
            this.opp = view.me().opposite();
            this.legal = legal;
            this.hash = view.movesHash();
        }

        PatternEvaluator.Summary analyze(Point p, Stone color) {
            nodes++;
            return PatternEvaluator.analyze(cells, p.x(), p.y(), color, rule);
        }

        /** 기존 돌에서 거리 2 이내 빈칸, y·x 순. */
        List<Point> candidates() {
            List<Point> out = new ArrayList<>();
            for (int y = 0; y < Board.SIZE; y++) {
                for (int x = 0; x < Board.SIZE; x++) {
                    if (cells[Board.index(x, y)] != Board.EMPTY || !nearStone(x, y)) {
                        continue;
                    }
                    out.add(new Point(x, y));
                }
            }
            return out;
        }

        private boolean nearStone(int x, int y) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    int nx = x + dx;
                    int ny = y + dy;
                    if ((dx != 0 || dy != 0) && PatternEvaluator.inside(nx, ny)
                            && cells[Board.index(nx, ny)] != Board.EMPTY) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** 그 색이 한 수로 5 를 만드는 자리들(y·x 순). */
        List<Point> fivePoints(Stone color, List<Point> cands) {
            List<Point> out = new ArrayList<>();
            for (Point p : cands) {
                nodes++;
                if (Shapes.makesFive(cells, p.x(), p.y(), color, rule)) {
                    out.add(p);
                }
            }
            return out;
        }

        Point easy() {
            List<Point> cands = candidates();
            List<Point> mine = filterPlayable(cands);
            Point win = firstFive(me, mine);
            if (win != null) {
                return win;
            }
            Point block = blockFive(cands, mine);
            if (block != null) {
                return block;
            }
            if (hash % 2 == 0) {
                Point threat = answerThreat(cands, mine);
                if (threat != null) {
                    return threat;
                }
            }
            List<Scored> ranked = rank(mine, 0.5);
            int pick = Math.min(ranked.size(), 3);
            return ranked.get(hash % pick).p;
        }

        Point normal() {
            List<Point> cands = candidates();
            List<Point> mine = filterPlayable(cands);
            Point forced = forced(cands, mine);
            if (forced != null) {
                return forced;
            }
            return rank(mine, 0.9).getFirst().p;
        }

        /** 보통의 필수수 단계. 없으면 null. */
        private Point forced(List<Point> cands, List<Point> mine) {
            Point win = firstFive(me, mine);
            if (win != null) {
                return win;
            }
            Point block = blockFive(cands, mine);
            if (block != null) {
                return block;
            }
            Point strong = null;
            int strongScore = PatternEvaluator.FOUR_THREE - 1;
            for (Point p : mine) {
                int sc = analyze(p, me).score();
                if (sc > strongScore) {
                    strong = p;
                    strongScore = sc;
                }
            }
            if (strong != null) {
                return strong;
            }
            return answerThreat(cands, mine);
        }

        private List<Point> filterPlayable(List<Point> cands) {
            List<Point> out = new ArrayList<>();
            for (Point p : cands) {
                if (legal.contains(p)) {
                    out.add(p);
                }
            }
            if (out.isEmpty()) {
                out.addAll(legal.stream()
                        .sorted(Comparator.comparingInt(Point::y).thenComparingInt(Point::x)).toList());
            }
            return out;
        }

        private Point firstFive(Stone color, List<Point> points) {
            List<Point> fives = fivePoints(color, points);
            return fives.isEmpty() ? null : fives.getFirst();
        }

        /** 상대가 다음 수에 5 를 만들 자리가 있으면 그중 내가 둘 수 있는 첫 자리. */
        private Point blockFive(List<Point> cands, List<Point> mine) {
            for (Point p : fivePoints(opp, cands)) {
                if (mine.contains(p)) {
                    return p;
                }
            }
            return null;
        }

        /** 상대의 열린 4·4-3·44 위협(그 자리에 두면 다음에 반드시 5) — 막거나 내 4 로 역공. 없으면 null. */
        private Point answerThreat(List<Point> cands, List<Point> mine) {
            List<Point> threats = new ArrayList<>();
            for (Point p : cands) {
                if (!playableFor(opp, p)) {
                    continue;
                }
                if (analyze(p, opp).score() >= PatternEvaluator.DOUBLE_THREE) {
                    threats.add(p);
                }
            }
            if (threats.isEmpty()) {
                return null;
            }
            Point best = null;
            double bestValue = Double.NEGATIVE_INFINITY;
            for (Point p : mine) {
                PatternEvaluator.Summary a = analyze(p, me);
                if (!threats.contains(p) && a.fours() == 0) {
                    continue;
                }
                double value = a.score() + 0.9 * analyze(p, opp).score();
                if (value > bestValue) {
                    bestValue = value;
                    best = p;
                }
            }
            return best;
        }

        private boolean playableFor(Stone color, Point p) {
            if (color != Stone.BLACK || !rule.hasForbiddenMoves()) {
                return true;
            }
            return ForbiddenRule.checkCells(cells, p.x(), p.y(), rule).isEmpty();
        }

        record Scored(Point p, double score) {
        }

        /** 공격 + w × 방어 점수 내림차순(같으면 y·x 순). */
        private List<Scored> rank(List<Point> mine, double defenseWeight) {
            return rankFor(me, mine, defenseWeight);
        }

        private List<Scored> rankFor(Stone side, List<Point> points, double defenseWeight) {
            Stone other = side.opposite();
            List<Scored> out = new ArrayList<>();
            for (Point p : points) {
                double attack = analyze(p, side).score();
                double defense = playableFor(other, p) ? analyze(p, other).score() : 0;
                out.add(new Scored(p, attack + defenseWeight * defense));
            }
            out.sort(Comparator.comparingDouble((Scored sc) -> -sc.score)
                    .thenComparingInt(sc -> sc.p.y()).thenComparingInt(sc -> sc.p.x()));
            return out;
        }

    }
}

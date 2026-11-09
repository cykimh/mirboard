package com.mirboard.domain.game.omok.bot;

import com.mirboard.domain.game.omok.OmokRule;
import com.mirboard.domain.game.omok.action.OmokAction;
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
 *   <li><b>어려움</b>: 보통 + 내 VCF(연속 4 로 이기는 수순) · 상대 VCF 막기 · 내 VCT(열린 3·4 로 몰아 이기기, 깊이 {@link #VCT_DEPTH}).
 * </ul>
 */
public final class OmokBotPolicy {

    /** 한 결정의 노드 상한(분석 1회 = 노드 1). */
    public static final int NODE_BUDGET = 150_000;
    /** VCF 탐색 깊이 — 내 수 기준. */
    static final int VCF_DEPTH = 12;
    static final int TOP_K = 8;
    /** VCT 깊이(내 열린 3 수)와 한 단계에서 보는 위협 수. */
    static final int VCT_DEPTH = 5;
    static final int VCT_WIDTH = 8;
    /** 안전 확인에서 상대 VCT 를 보는 깊이. */
    static final int SAFETY_DEPTH = 2;

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
            Point center = new Point(7, 7);
            return new Decision(place(legalPoints.contains(center) ? center : first(legalPoints)), 0);
        }
        Search s = new Search(view, legalPoints);
        Point p = switch (level) {
            case EASY -> s.easy();
            case NORMAL -> s.normal();
            case HARD -> s.hard();
        };
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

        int limit = NODE_BUDGET;

        boolean exhausted() {
            return nodes >= limit;
        }

        /** 노드 상한을 잠시 낮춰 한 단계를 돌린다 — 끝나면 원래 상한. */
        private Point withLimit(int phaseLimit, java.util.function.Supplier<Point> phase) {
            int saved = limit;
            limit = Math.min(saved, phaseLimit);
            try {
                return exhausted() ? null : phase.get();
            } finally {
                limit = saved;
            }
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

        Point hard() {
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
            Point vcf = vcf(me, VCF_DEPTH);
            if (vcf != null) {
                return vcf;
            }
            Point forced = forced(cands, mine);
            List<Scored> ranked = rank(mine, 0.9);
            if (forced != null) {
                return forced;
            }
            // VCT — 열린 3·4 로 몰아 이기는 수순. 노드 예산의 절반까지만 쓴다(안전 확인에 남겨 둔다).
            Point vct = withLimit(NODE_BUDGET / 2, () -> vct(me, VCT_DEPTH));
            if (vct != null) {
                return vct;
            }
            // 안전 확인: 상위 후보를 차례로, 두고 나서 상대 VCT(3·3 처럼 한두 수로 몰아 이기는 수순, VCF 포함)가 없는 첫 수.
            // 흑은 금수 자리를 노리는 백의 4 도 여기서 피한다.
            for (Scored sc : ranked.subList(0, Math.min(ranked.size(), TOP_K))) {
                if (exhausted()) {
                    break;
                }
                set(sc.p, me);
                Point oppWin = vct(opp, SAFETY_DEPTH);
                unset(sc.p);
                if (oppWin == null && !exhausted()) {
                    return sc.p;
                }
            }
            return ranked.getFirst().p;
        }

        /**
         * color 의 VCT — 4 나 열린 3 을 연달아 만들어 이기는 수순의 첫 수(VCF 포함). 열린 3 에는 상대가 막는 자리(그 줄의
         * 열린 4 완성 자리를 지우는 빈칸)와 상대의 4(역공)를 모두 응수로 보고, 전부에 대해 이어서 이길 때만 성공이다. 없거나
         * 노드가 다하면 null.
         */
        Point vct(Stone color, int depth) {
            if (depth <= 0 || exhausted()) {
                return null;
            }
            Point vcf = vcf(color, depth * 2);
            if (vcf != null) {
                return vcf;
            }
            Stone other = color.opposite();
            List<Point> cands = candidates();
            if (!fivePoints(other, cands).isEmpty()) {
                return null;
            }
            // 상대에게 VCF 가 있으면 내 열린 3 은 강제가 아니다 — 상대가 4 로 먼저 이긴다.
            if (vcf(other, VCF_DEPTH / 2) != null) {
                return null;
            }
            List<Scored> threats = new ArrayList<>();
            for (Point m : cands) {
                if (exhausted()) {
                    return null;
                }
                if (!playableFor(color, m)) {
                    continue;
                }
                PatternEvaluator.Summary a = analyze(m, color);
                if (a.openThrees() > 0 && a.fours() == 0) {
                    threats.add(new Scored(m, a.score()));
                }
            }
            threats.sort(Comparator.comparingDouble((Scored sc) -> -sc.score)
                    .thenComparingInt(sc -> sc.p.y()).thenComparingInt(sc -> sc.p.x()));
            for (Scored t : threats.subList(0, Math.min(threats.size(), VCT_WIDTH))) {
                if (exhausted()) {
                    return null;
                }
                set(t.p, color);
                try {
                    List<Point> defenses = defenses(color, t.p);
                    boolean allWin = !defenses.isEmpty();
                    for (Point d : defenses) {
                        if (exhausted()) {
                            allWin = false;
                            break;
                        }
                        set(d, other);
                        try {
                            Point next;
                            List<Point> oppFives = fivePoints(other, candidates());
                            if (oppFives.size() >= 2) {
                                next = null;                         // 역공 4 가 열린 4 — 진다
                            } else if (oppFives.size() == 1) {
                                Point w = oppFives.getFirst();       // 역공 4 를 막고 이어 간다
                                if (!playableFor(color, w)) {
                                    next = null;
                                } else {
                                    set(w, color);
                                    try {
                                        next = analyzeFive(color) ? w : vct(color, depth - 1);
                                    } finally {
                                        unset(w);
                                    }
                                }
                            } else {
                                next = vct(color, depth - 1);
                            }
                            if (next == null) {
                                allWin = false;
                            }
                        } finally {
                            unset(d);
                        }
                        if (!allWin) {
                            break;
                        }
                    }
                    if (allWin) {
                        return t.p;
                    }
                } finally {
                    unset(t.p);
                }
            }
            return null;
        }

        /** color 가 지금 5 를 만들 자리가 있는가. */
        private boolean analyzeFive(Stone color) {
            return !fivePoints(color, candidates()).isEmpty();
        }

        /**
         * 놓인 color 돌 p 가 만든 열린 3 을 상대가 막는 자리 — 그 칸에 상대 돌을 두면 p 를 지나는 줄들에서 열린 4 완성 자리가
         * 사라지는 빈칸 — 와 상대가 4 를 만드는 자리(역공). y·x 순, 중복 없음.
         */
        private List<Point> defenses(Stone color, Point p) {
            Stone other = color.opposite();
            Set<Point> out = new java.util.LinkedHashSet<>();
            for (com.mirboard.domain.game.omok.rules.Direction d : com.mirboard.domain.game.omok.rules.Direction.values()) {
                if (Shapes.straightFourCompletionCount(cells, p.x(), p.y(), d, color, rule) == 0) {
                    continue;
                }
                for (int k = -5; k <= 5; k++) {
                    int x = p.x() + k * d.dx;
                    int y = p.y() + k * d.dy;
                    if (k == 0 || !PatternEvaluator.inside(x, y) || cells[Board.index(x, y)] != Board.EMPTY) {
                        continue;
                    }
                    Point q = new Point(x, y);
                    if (!playableFor(other, q)) {
                        continue;
                    }
                    set(q, other);
                    boolean blocked;
                    try {
                        nodes++;
                        blocked = Shapes.straightFourCompletionCount(cells, p.x(), p.y(), d, color, rule) == 0;
                    } finally {
                        unset(q);
                    }
                    if (blocked) {
                        out.add(q);
                    }
                }
            }
            for (Point q : candidates()) {
                if (playableFor(other, q) && analyze(q, other).fours() > 0) {
                    out.add(q);
                }
            }
            List<Point> sorted = new ArrayList<>(out);
            sorted.sort(Comparator.comparingInt(Point::y).thenComparingInt(Point::x));
            return sorted;
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

        private void set(Point p, Stone color) {
            cells[Board.index(p.x(), p.y())] = Board.code(color);
        }

        private void unset(Point p) {
            cells[Board.index(p.x(), p.y())] = Board.EMPTY;
        }

        /**
         * color 의 VCF — 4 를 연달아 만들어(상대는 매번 막을 수밖에 없다) 이기는 수순의 첫 수. 없거나 노드가 다하면 null.
         * 상대가 막는 수로 4 가 생기면(역 4) 그 갈래는 실패로 본다 — 보수적으로.
         */
        Point vcf(Stone color, int depth) {
            if (depth <= 0 || exhausted()) {
                return null;
            }
            Stone other = color.opposite();
            List<Point> cands = candidates();
            if (!fivePoints(other, cands).isEmpty()) {
                return null;                               // 상대가 먼저 5 를 만든다
            }
            for (Point m : cands) {
                if (exhausted()) {
                    return null;
                }
                if (!playableFor(color, m)) {
                    continue;
                }
                PatternEvaluator.Summary a = analyze(m, color);
                if (a.five()) {
                    return m;
                }
                if (a.fours() == 0) {
                    continue;
                }
                set(m, color);
                try {
                    List<Point> wins = fivePoints(color, candidates());
                    if (wins.size() >= 2) {
                        return m;
                    }
                    if (wins.size() == 1) {
                        Point w = wins.getFirst();
                        if (!playableFor(other, w)) {
                            return m;                      // 흑이 막을 자리가 금수
                        }
                        set(w, other);
                        try {
                            if (fivePoints(other, candidates()).isEmpty() && vcf(color, depth - 1) != null) {
                                return m;
                            }
                        } finally {
                            unset(w);
                        }
                    }
                } finally {
                    unset(m);
                }
            }
            return null;
        }
    }
}

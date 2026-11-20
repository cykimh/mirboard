package com.mirboard.domain.game.dambaduk.bot;

import com.mirboard.domain.game.dambaduk.action.DambadukAction;
import com.mirboard.domain.game.dambaduk.board.Cell;
import com.mirboard.domain.game.dambaduk.board.Dir;
import com.mirboard.domain.game.dambaduk.board.Grid;
import com.mirboard.domain.game.dambaduk.state.MoveOption;
import com.mirboard.domain.game.dambaduk.state.Phase;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 담바둑 봇 정책 (D-277) — 공개 정보만 담은 {@link DambadukBotView} 와 합법 수 목록을 받아 하나를 고른다. 결정적이다({@code random}
 * 미사용 — 동점은 판 해시로 고른다). 시간 상한 대신 <b>평가 횟수 상한</b>({@link #NODE_BUDGET})으로 방 락(2초) 안에 머문다.
 *
 * <ul>
 *   <li><b>쉬움</b> — 남을 보지 않고 내 영향권 + 집 × 3 이 큰 수(4로 나눠 거칠게 — 동점이 많아 해시로 흔들린다), 옮긴 돌이 한 면만
 *       남게 갇히는 수는 깎는다.</li>
 *   <li><b>보통</b> — 1수 전수 × 평가 = 영향권 차 × 10 + 집 차 × 3 (남 = 가장 앞선 상대). 판이 끝나는 수는 이기면 크게 더하고 지면
 *       크게 뺀다.</li>
 *   <li><b>어려움</b> — 보통 평가로 내 수 상위 {@link #HARD_WIDTH}개를 고르고, 다음에 옮길 사람의 응수까지 본다. 2인은 상대가 나에게
 *       가장 나쁜 수(최소화), 3인은 다음 사람이 <i>자기</i> 평가로 고르는 수(그 사람의 보통)를 가정한다 — 3인 평가의 "남 = 가장 앞선
 *       상대"가 곧 1등 견제다.</li>
 * </ul>
 * 배치 단계는 셋 다 빈 칸 × 종류를 평가한다(쉬움은 가장자리를 피한 칸 중 해시로 하나).
 */
public final class DambadukBotPolicy {

    /** 어려움 한 수의 평가 상한(국면 수). 넘기면 남은 후보는 보통 평가로 끝낸다. */
    public static final int NODE_BUDGET = 20_000;
    /** 어려움이 응수까지 읽는 내 수 후보 수. */
    public static final int HARD_WIDTH = 40;

    private static final int WIN = 100_000;

    private DambadukBotPolicy() {
    }

    /** 고른 수와 쓴 평가 수(예산 테스트용). */
    public record Decision(DambadukAction action, int nodes) {
    }

    public static DambadukAction choose(DambadukBotView view, List<DambadukAction> legal, DambadukBotLevel level) {
        return decide(view, legal, level).action();
    }

    public static Decision decide(DambadukBotView view, List<DambadukAction> legal, DambadukBotLevel level) {
        if (legal.isEmpty()) {
            return new Decision(null, 0);
        }
        BotBoard board = BotBoard.of(view);
        if (view.phase() == Phase.PLACING) {
            return place(view, board, legal, level);
        }
        List<Candidate> cands = candidates(view, board, legal);
        if (cands.isEmpty()) {
            return new Decision(legal.getFirst(), 0);
        }
        return switch (level) {
            case EASY -> easy(view, cands);
            case NORMAL -> normal(view, cands);
            case HARD -> hard(view, cands);
        };
    }

    // ---------- 후보 ----------

    /** 합법 수 하나와 그 수를 둔 판. */
    private record Candidate(DambadukAction.Move action, MoveOption option, Dir wall, BotBoard after) {
    }

    private static List<Candidate> candidates(DambadukBotView view, BotBoard board, List<DambadukAction> legal) {
        List<Candidate> out = new ArrayList<>();
        List<MoveOption> options = board.options(view.me());
        for (DambadukAction a : legal) {
            if (!(a instanceof DambadukAction.Move m)) {
                continue;
            }
            MoveOption o = options.stream()
                    .filter(x -> x.stoneId() == m.stoneId() && x.path().equals(m.path()) && x.jump() == m.jump())
                    .findFirst().orElse(null);
            if (o == null) {
                continue;
            }
            out.add(new Candidate(m, o, m.wall(), board.afterMove(view.me(), o, m.wall())));
        }
        return out;
    }

    // ---------- 평가 ----------

    /** {@code seat} 기준 평가 — 남은 빠지지 않은 상대 중 가장 앞선 사람과의 차. */
    static int evaluate(BotBoard b, int seat) {
        Influence inf = Influence.of(b);
        int oppHome = 0;
        int oppInf = 0;
        for (int o = 0; o < b.seatCount; o++) {
            if (o != seat && b.active[o]) {
                oppHome = Math.max(oppHome, inf.home()[o]);
                oppInf = Math.max(oppInf, inf.influence()[o]);
            }
        }
        if (!inf.contested()) {
            int diff = inf.home()[seat] - oppHome;
            return (diff > 0 ? WIN : diff < 0 ? -WIN : 0) + diff * 100;
        }
        return 10 * (inf.influence()[seat] - oppInf) + 3 * (inf.home()[seat] - oppHome);
    }

    // ---------- 단계별 ----------

    private static Decision easy(DambadukBotView view, List<Candidate> cands) {
        int me = view.me();
        int best = Integer.MIN_VALUE;
        List<Candidate> ties = new ArrayList<>();
        for (Candidate c : cands) {
            Influence inf = Influence.of(c.after());
            Cell to = new Cell(c.option().toX(), c.option().toY());
            int free = 0;
            for (Dir d : Dir.values()) {
                if (!c.after().walls.blocked(to, d)) {
                    free++;
                }
            }
            // 남을 보지 않는다 — 내 영향권·집만, 갇히는 수는 깎는다. 값 폭이 좁아 동점이 많고 해시로 흔들린다.
            int v = (inf.influence()[me] + 3 * inf.home()[me]) / 4 - (free <= 1 ? 4 : 0);
            if (v > best) {
                best = v;
                ties.clear();
            }
            if (v == best) {
                ties.add(c);
            }
        }
        return new Decision(pick(view, ties).action(), cands.size());
    }

    private static Decision normal(DambadukBotView view, List<Candidate> cands) {
        List<Scored> scored = score(view, cands);
        return new Decision(pick(view, bestOf(scored)).action(), cands.size());
    }

    private static Decision hard(DambadukBotView view, List<Candidate> cands) {
        int me = view.me();
        List<Scored> scored = score(view, cands);
        int nodes = cands.size();
        List<Scored> ordered = new ArrayList<>(scored);
        ordered.sort(Comparator.comparingInt(Scored::value).reversed());
        List<Scored> deep = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            Scored s = ordered.get(i);
            if (i >= HARD_WIDTH || nodes >= NODE_BUDGET || Math.abs(s.value()) >= WIN / 2) {
                deep.add(s);                                    // 예산 밖·판이 끝나는 수는 1수 평가 그대로
                continue;
            }
            BotBoard after = s.candidate().after();
            int next = after.nextMover(me);
            if (next < 0 || next == me) {
                deep.add(s);
                continue;
            }
            List<MoveOption> replies = after.options(next);
            int worst = Integer.MAX_VALUE;
            int theirBest = Integer.MIN_VALUE;
            int mineAtTheirBest = s.value();
            for (MoveOption r : replies) {
                for (Dir w : r.walls()) {
                    BotBoard reply = after.afterMove(next, r, w);
                    nodes++;
                    if (view.seatCount() == 2) {
                        worst = Math.min(worst, evaluate(reply, me));
                    } else {
                        int theirs = evaluate(reply, next);
                        if (theirs > theirBest) {
                            theirBest = theirs;
                            mineAtTheirBest = evaluate(reply, me);
                            nodes++;
                        }
                    }
                }
            }
            int value = view.seatCount() == 2 ? (worst == Integer.MAX_VALUE ? s.value() : worst) : mineAtTheirBest;
            deep.add(new Scored(s.candidate(), value));
        }
        return new Decision(pick(view, bestOf(deep)).action(), nodes);
    }

    private record Scored(Candidate candidate, int value) {
    }

    private static List<Scored> score(DambadukBotView view, List<Candidate> cands) {
        List<Scored> out = new ArrayList<>(cands.size());
        for (Candidate c : cands) {
            out.add(new Scored(c, evaluate(c.after(), view.me())));
        }
        return out;
    }

    private static List<Candidate> bestOf(List<Scored> scored) {
        int best = scored.stream().mapToInt(Scored::value).max().orElseThrow();
        return scored.stream().filter(s -> s.value() == best).map(Scored::candidate).toList();
    }

    private static <T> T pick(DambadukBotView view, List<T> ties) {
        return ties.get(Math.floorMod(view.boardHash(), ties.size()));
    }

    // ---------- 배치 ----------

    private static Decision place(DambadukBotView view, BotBoard board, List<DambadukAction> legal,
                                  DambadukBotLevel level) {
        List<DambadukAction.Place> places = legal.stream().filter(DambadukAction.Place.class::isInstance)
                .map(DambadukAction.Place.class::cast).toList();
        if (places.isEmpty()) {
            return new Decision(legal.getFirst(), 0);
        }
        if (level == DambadukBotLevel.EASY) {
            List<DambadukAction.Place> inner = places.stream()
                    .filter(p -> p.x() > 0 && p.x() < Grid.SIZE - 1 && p.y() > 0 && p.y() < Grid.SIZE - 1).toList();
            return new Decision(pick(view, inner.isEmpty() ? places : inner), 0);
        }
        int best = Integer.MIN_VALUE;
        List<DambadukAction.Place> ties = new ArrayList<>();
        for (DambadukAction.Place p : places) {
            int v = evaluate(board.afterPlace(view.me(), p.kind(), new Cell(p.x(), p.y())), view.me());
            if (v > best) {
                best = v;
                ties.clear();
            }
            if (v == best) {
                ties.add(p);
            }
        }
        return new Decision(pick(view, ties), places.size());
    }
}

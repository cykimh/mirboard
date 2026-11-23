package com.mirboard.domain.game.dambaduk.rules;

import com.mirboard.domain.game.dambaduk.board.Cell;
import com.mirboard.domain.game.dambaduk.board.Dir;
import com.mirboard.domain.game.dambaduk.board.Grid;
import com.mirboard.domain.game.dambaduk.board.Walls;
import com.mirboard.domain.game.dambaduk.state.MoveOption;
import com.mirboard.domain.game.dambaduk.state.Stone;
import java.util.ArrayList;
import java.util.List;

/**
 * 옮김의 판 위 검사와 합법 수 전수 (룰 §4). 상태 레코드를 모른다 — 돌 목록·담·담 넘기 가능 여부만 받는다(봇·불변식이 같이 쓴다).
 */
public final class MoveRules {

    private MoveRules() {
    }

    /** 칸 번호 → 돌이 있나. 옮기는 돌의 자리는 비운 채로 본다. */
    public static boolean[] occupancy(List<Stone> stones, int movingStoneId) {
        boolean[] occ = new boolean[Grid.CELLS];
        for (Stone s : stones) {
            if (s.id() != movingStoneId) {
                occ[s.cell().index()] = true;
            }
        }
        return occ;
    }

    /** 경로를 걸어 본다 — 모양은 따로 검사({@link Paths#validShape}). */
    public static Walk walk(Cell from, List<Dir> path, Walls walls, boolean[] occupied) {
        Cell cur = from;
        List<Integer> crossed = new ArrayList<>(2);
        boolean blocked = false;
        for (Dir d : path) {
            int face = Grid.face(cur, d);
            if (face < 0) {
                blocked = true;
                break;
            }
            Cell next = cur.step(d);
            if (occupied[next.index()]) {
                blocked = true;
                break;
            }
            if (walls.has(face)) {
                crossed.add(face);
            }
            cur = next;
        }
        return new Walk(blocked, cur, crossed);
    }

    /** 도착 칸에서 세울 수 있는 면들(방향 순) — 바깥담·담이 아닌 면. */
    public static List<Dir> wallSides(Cell at, Walls walls) {
        List<Dir> out = new ArrayList<>(4);
        for (Dir d : Dir.values()) {
            if (!walls.blocked(at, d)) {
                out.add(d);
            }
        }
        return out;
    }

    /**
     * 한 사람의 고를 수 있는 옮김 전부(룰 §4) — {@code stoneId} → 경로 순. 담을 지나는 경로는 {@code canJump} 이고 담 하나일 때만
     * 담 넘기 수로 넣는다.
     *
     * @param includeWallless 세울 면이 없는 도착도 넣는다(불변식 검사용 — 룰 §4.3 이 옳다면 늘 없다)
     */
    public static List<MoveOption> options(List<Stone> stones, int seat, Walls walls, boolean canJump,
                                           boolean includeWallless) {
        List<MoveOption> out = new ArrayList<>();
        for (Stone s : stones) {
            if (s.seat() != seat) {
                continue;
            }
            boolean[] occ = occupancy(stones, s.id());
            for (List<Dir> path : Paths.of(s.kind())) {
                Walk w = walk(s.cell(), path, walls, occ);
                if (w.blocked() || w.crossed().size() > 1 || (w.crossed().size() == 1 && !canJump)) {
                    continue;
                }
                boolean jump = w.crossed().size() == 1;
                int jumped = jump ? w.crossed().getFirst() : -1;
                Walls after = jump ? walls.without(jumped) : walls;
                List<Dir> sides = wallSides(w.to(), after);
                if (sides.isEmpty() && !includeWallless) {
                    continue;
                }
                out.add(new MoveOption(s.id(), path, w.to().x(), w.to().y(), jump, jumped, sides));
            }
        }
        return out;
    }

    /**
     * 돌 하나가 지금 옮길 수 있나(첫 수를 찾으면 바로 참) — 굳음 판정(룰 §6 ②)이 다툼 터의 돌마다 부른다. {@link #options} 와 같은 조건이다.
     */
    public static boolean hasMove(List<Stone> stones, Stone stone, Walls walls, boolean canJump) {
        boolean[] occ = occupancy(stones, stone.id());
        for (List<Dir> path : Paths.of(stone.kind())) {
            Walk w = walk(stone.cell(), path, walls, occ);
            if (w.blocked() || w.crossed().size() > 1 || (w.crossed().size() == 1 && !canJump)) {
                continue;
            }
            Walls after = w.crossed().isEmpty() ? walls : walls.without(w.crossed().getFirst());
            if (!wallSides(w.to(), after).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasOption(List<Stone> stones, int seat, Walls walls, boolean canJump) {
        return !options(stones, seat, walls, canJump, false).isEmpty();
    }
}

package com.mirboard.domain.game.dambaduk.bot;

import com.mirboard.domain.game.dambaduk.board.Cell;
import com.mirboard.domain.game.dambaduk.board.Dir;
import com.mirboard.domain.game.dambaduk.board.Grid;
import com.mirboard.domain.game.dambaduk.board.Walls;
import com.mirboard.domain.game.dambaduk.rules.MoveRules;
import com.mirboard.domain.game.dambaduk.state.MoveOption;
import com.mirboard.domain.game.dambaduk.state.Stone;
import com.mirboard.domain.game.dambaduk.state.StoneKind;
import java.util.ArrayList;
import java.util.List;

/**
 * 봇의 수읽기용 가벼운 판 — 돌·담·담 넘기 남음만 들고, 수를 둬 본 판을 새로 만든다. 끝 판정·건너뜀·이벤트는 하지 않는다(엔진이 아니다).
 * 합법성은 엔진의 {@code MoveRules} 를 그대로 쓴다.
 */
final class BotBoard {

    final int seatCount;
    final List<Stone> stones;
    final Walls walls;
    final int[] jumpsLeft;
    final boolean[] active;

    BotBoard(int seatCount, List<Stone> stones, Walls walls, int[] jumpsLeft, boolean[] active) {
        this.seatCount = seatCount;
        this.stones = stones;
        this.walls = walls;
        this.jumpsLeft = jumpsLeft;
        this.active = active;
    }

    static BotBoard of(DambadukBotView v) {
        int[] jumps = v.jumpsLeft().stream().mapToInt(Integer::intValue).toArray();
        boolean[] active = new boolean[v.seatCount()];
        v.activeSeats().forEach(s -> active[s] = true);
        return new BotBoard(v.seatCount(), v.stones(), Walls.of(v.walls()), jumps, active);
    }

    List<MoveOption> options(int seat) {
        return MoveRules.options(stones, seat, walls, jumpsLeft[seat] > 0, false);
    }

    BotBoard afterMove(int seat, MoveOption o, Dir wall) {
        List<Stone> next = new ArrayList<>(stones.size());
        Cell to = new Cell(o.toX(), o.toY());
        for (Stone s : stones) {
            next.add(s.id() == o.stoneId() ? s.movedTo(to) : s);
        }
        Walls w = o.jump() ? walls.without(o.jumpedFace()) : walls;
        w = w.with(Grid.face(to, wall));
        int[] jumps = jumpsLeft;
        if (o.jump()) {
            jumps = jumpsLeft.clone();
            jumps[seat] = 0;
        }
        return new BotBoard(seatCount, next, w, jumps, active);
    }

    BotBoard afterPlace(int seat, StoneKind kind, Cell c) {
        List<Stone> next = new ArrayList<>(stones);
        next.add(new Stone(10_000 + next.size(), seat, kind, c.x(), c.y()));
        return new BotBoard(seatCount, next, walls, jumpsLeft, active);
    }

    /** 다음에 옮길 좌석 — {@code after} 다음부터 좌석 +1 로 옮길 수 있는 첫 사람, 없으면 −1. */
    int nextMover(int after) {
        for (int k = 1; k <= seatCount; k++) {
            int s = (after + k) % seatCount;
            if (active[s] && !options(s).isEmpty()) {
                return s;
            }
        }
        return -1;
    }
}

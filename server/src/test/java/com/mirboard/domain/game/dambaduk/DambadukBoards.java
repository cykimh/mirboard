package com.mirboard.domain.game.dambaduk;

import com.mirboard.domain.game.dambaduk.board.Cell;
import com.mirboard.domain.game.dambaduk.board.Dir;
import com.mirboard.domain.game.dambaduk.board.Grid;
import com.mirboard.domain.game.dambaduk.board.Walls;
import com.mirboard.domain.game.dambaduk.state.DambadukState;
import com.mirboard.domain.game.dambaduk.state.Phase;
import com.mirboard.domain.game.dambaduk.state.Stone;
import com.mirboard.domain.game.dambaduk.state.StoneKind;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 룰 문서 §12 의 미로 그림을 그대로 읽는 테스트 보조. 줄 앞의 글자(열 머리·행 번호)는 첫 {@code |}·{@code +} 앞에서 버린다.
 * 칸 글자: 대문자 = 곧은돌, 소문자 = 꺾은돌, 좌석은 {@code A/a} 0 · {@code B/b} 1 · {@code C/c} 2. 룰 문서의 {@code x}·{@code o}
 * 는 좌석 0·1 의 곧은돌로, {@code I}·{@code L} 은 좌석 0 의 곧은돌·꺾은돌로 읽는다. {@code .}·{@code *} 는 빈칸이고 {@code *} 는
 * {@link Diagram#marks()} 에 모은다.
 */
public final class DambadukBoards {

    public static final DambadukEngine ENGINE = new DambadukEngine();

    private DambadukBoards() {
    }

    public record Diagram(List<Stone> stones, Walls walls, List<Cell> marks) {
    }

    public static Diagram parse(String... lines) {
        List<String> rows = new ArrayList<>();
        for (String raw : lines) {
            int bar = indexOfAny(raw, '|', '+');
            if (bar < 0) {
                continue;                                         // 열 머리 줄
            }
            rows.add(raw.substring(bar));
        }
        if (rows.size() != 2 * Grid.SIZE + 1) {
            throw new IllegalArgumentException("Expected 15 board lines, got " + rows.size());
        }
        List<Stone> stones = new ArrayList<>();
        List<Cell> marks = new ArrayList<>();
        Walls walls = Walls.NONE;
        for (int y = 0; y < Grid.SIZE; y++) {
            String row = pad(rows.get(1 + 2 * y));
            for (int x = 0; x < Grid.SIZE; x++) {
                char ch = row.charAt(2 + 4 * x);
                Cell c = new Cell(x, y);
                if (ch == '*') {
                    marks.add(c);
                } else if (ch != '.' && ch != ' ') {
                    stones.add(stoneOf(ch, stones.size(), c));
                }
                if (x < Grid.SIZE - 1 && row.charAt(4 + 4 * x) == '|') {
                    walls = walls.with(Grid.face(c, Dir.RIGHT));
                }
            }
            if (y < Grid.SIZE - 1) {
                String sep = pad(rows.get(2 + 2 * y));
                for (int x = 0; x < Grid.SIZE; x++) {
                    if (sep.charAt(1 + 4 * x) == '-') {
                        walls = walls.with(Grid.face(new Cell(x, y), Dir.DOWN));
                    }
                }
            }
        }
        return new Diagram(stones, walls, marks);
    }

    private static Stone stoneOf(char ch, int id, Cell c) {
        int seat;
        StoneKind kind;
        switch (ch) {
            case 'x', 'I' -> { seat = 0; kind = StoneKind.STRAIGHT; }
            case 'L' -> { seat = 0; kind = StoneKind.BENT; }
            case 'o' -> { seat = 1; kind = StoneKind.STRAIGHT; }
            default -> {
                seat = Character.toLowerCase(ch) - 'a';
                if (seat < 0 || seat > 2) {
                    throw new IllegalArgumentException("Unknown board char '" + ch + "'");
                }
                kind = Character.isUpperCase(ch) ? StoneKind.STRAIGHT : StoneKind.BENT;
            }
        }
        return new Stone(id, seat, kind, c.x(), c.y());
    }

    /**
     * 그림대로의 옮김 단계 상태 — 선 = 좌석 0, 담 수만큼 옮긴 것으로 친다(불변식의 담 짝). 3인이면 모두 담 넘기 1번.
     */
    public static DambadukState playing(int seatCount, int turnSeat, String... lines) {
        Diagram d = parse(lines);
        List<Integer> jumps = IntStream.range(0, seatCount).map(i -> seatCount == 3 ? 1 : 0).boxed().toList();
        return new DambadukState(seatCount, 0, Phase.PLAYING, d.stones(), d.walls().toList(), List.of(), turnSeat,
                jumps, null, List.of(), d.stones().size(), d.walls().count(), 0, null);
    }

    private static int indexOfAny(String s, char a, char b) {
        int ia = s.indexOf(a);
        int ib = s.indexOf(b);
        if (ia < 0) {
            return ib;
        }
        if (ib < 0) {
            return ia;
        }
        return Math.min(ia, ib);
    }

    private static String pad(String s) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < 4 * Grid.SIZE + 1) {
            b.append(' ');
        }
        return b.toString();
    }

    public static Cell c(String label) {
        return new Cell(label.charAt(0) - 'A', Integer.parseInt(label.substring(1)) - 1);
    }
}

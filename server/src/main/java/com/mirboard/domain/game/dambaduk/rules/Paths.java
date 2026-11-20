package com.mirboard.domain.game.dambaduk.rules;

import com.mirboard.domain.game.dambaduk.board.Dir;
import com.mirboard.domain.game.dambaduk.state.StoneKind;
import java.util.ArrayList;
import java.util.List;

/**
 * 돌 종류별 경로 모양 (룰 §4.1·§4.2). 판·돌·담과 무관한 모양만 본다.
 *
 * <p>정렬 순서(룰 §8 — 시간 초과 자동 수의 기준): 1걸음 경로를 {@link Dir} 순으로, 그다음 2걸음 경로를 (첫 방향, 둘째 방향) 순으로.
 */
public final class Paths {

    private static final List<List<Dir>> STRAIGHT = build(StoneKind.STRAIGHT);
    private static final List<List<Dir>> BENT = build(StoneKind.BENT);

    private Paths() {
    }

    /** 그 종류가 가질 수 있는 모든 경로 — 곧은돌 8개, 꺾은돌 12개. */
    public static List<List<Dir>> of(StoneKind kind) {
        return kind == StoneKind.STRAIGHT ? STRAIGHT : BENT;
    }

    /** 모양 검사 — 1~2걸음, 곧은돌은 같은 방향, 꺾은돌은 둘째 걸음이 수직(곧게 2칸·되돌아오기 불가). */
    public static boolean validShape(StoneKind kind, List<Dir> path) {
        if (path == null || path.isEmpty() || path.size() > 2) {
            return false;
        }
        for (Dir d : path) {                                   // List.of 의 contains(null) 은 NPE 라 직접 본다
            if (d == null) {
                return false;
            }
        }
        if (path.size() == 1) {
            return true;
        }
        Dir a = path.get(0);
        Dir b = path.get(1);
        return kind == StoneKind.STRAIGHT ? a == b : a.isPerpendicularTo(b);
    }

    private static List<List<Dir>> build(StoneKind kind) {
        List<List<Dir>> out = new ArrayList<>();
        for (Dir d : Dir.values()) {
            out.add(List.of(d));
        }
        for (Dir a : Dir.values()) {
            for (Dir b : Dir.values()) {
                List<Dir> p = List.of(a, b);
                if (validShape(kind, p)) {
                    out.add(p);
                }
            }
        }
        return List.copyOf(out);
    }
}

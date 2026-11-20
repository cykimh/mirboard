package com.mirboard.domain.game.starsquare.puzzle;

/**
 * 진행도(`docs/rules-starsquare.md` §5.1) — 남에게 보이는 두 수.
 *
 * @param filled 0 이 아니고 판에서 한 번만 쓰인 값이 든 칸의 수(0~N²)
 * @param lines  칸이 모두 채운 칸이고 합이 M 인 줄의 수(0~2N+2)
 */
public record Progress(int filled, int lines) {

    /** 완성 ⇔ 채운 칸 N², 맞은 줄 2N+2 (§5.1). */
    public boolean complete(int n) {
        return filled == n * n && lines == 2 * n + 2;
    }
}

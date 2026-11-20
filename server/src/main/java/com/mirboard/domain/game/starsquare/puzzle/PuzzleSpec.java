package com.mirboard.domain.game.starsquare.puzzle;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 매치에 들어간 퍼즐 하나(`docs/rules-starsquare.md` §3·§4) — 대칭 변환까지 입힌 뒤의 단서와 정답. <b>상태 안에만 있다</b>:
 * 공개 뷰·이벤트 타입은 이 레코드를 싣지 않는다(정답 은닉, §11).
 *
 * @param clues    단서 판(0 = 단서 아님)
 * @param solution 단서와 맞는 마방진 하나 — 판정에 쓰지 않는다(§5). 봇 판·결과 공개용
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PuzzleSpec(Band band, Grid clues, Grid solution) {

    public PuzzleSpec {
        if (band == null || clues == null || solution == null) {
            throw new IllegalArgumentException("PuzzleSpec needs band, clues and solution");
        }
        if (clues.n() != band.size() || solution.n() != band.size()) {
            throw new IllegalArgumentException("PuzzleSpec size mismatch for band " + band);
        }
    }

    public int n() {
        return band.size();
    }

    /** 같은 변환을 단서·정답에 함께 입힌다(§4.2). */
    public PuzzleSpec transformed(Symmetry symmetry) {
        return new PuzzleSpec(band, symmetry.apply(clues), symmetry.apply(solution));
    }
}

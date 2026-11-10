package com.mirboard.domain.game.artillery.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 지형 연산 하나 (§6.2). 상태는 이 목록과 시드만으로 지형을 다시 만든다(D-220).
 *
 * <p>{@code fill} 이 거짓이면 원 지우기(구멍), 참이면 원 채우기(덩굴탄 둔덕, D-224) — 채울 때는 {@code spare} 의 몸 중심
 * (x, y 를 이어 붙인 목록)에서 반경 {@link #SPARE_RADIUS} 안을 비운다(포대가 흙에 묻히지 않게). 비운 자리를 연산에 함께 저장하므로
 * 재구성이 그때의 포대 위치에 기대지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Crater(int x, int y, int r, boolean fill, List<Integer> spare) {

    /** 둔덕이 비워 두는 몸 둘레 반경(칸) — 맞는 범위 6 + 1. */
    public static final int SPARE_RADIUS = 7;

    public Crater {
        spare = spare == null ? List.of() : List.copyOf(spare);
    }

    /** 구멍. */
    public Crater(int x, int y, int r) {
        this(x, y, r, false, List.of());
    }
}

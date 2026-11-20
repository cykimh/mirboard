package com.mirboard.domain.game.starsquare.persistence;

import com.mirboard.domain.game.starsquare.puzzle.PuzzleBank;
import org.springframework.stereotype.Component;

/**
 * 퍼즐 은행 Bean(`docs/rules-starsquare.md` §4.2). 기동 때 자원 4개를 한 번 읽는다 — 하나라도 없거나 비면 서버가 뜨지 않는 편이 낫다
 * ({@link PuzzleBank#fromClasspath()}). 런타임에는 풀거나 만들지 않고 여기서 꺼내기만 한다.
 */
@Component
public class StarSquarePuzzles {

    private final PuzzleBank bank = PuzzleBank.fromClasspath();

    public PuzzleBank bank() {
        return bank;
    }
}

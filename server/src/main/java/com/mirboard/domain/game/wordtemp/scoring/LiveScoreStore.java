package com.mirboard.domain.game.wordtemp.scoring;

import java.time.Instant;
import java.util.Optional;

/**
 * 실시간 점수 캐시(D-312·D-317) — Postgres {@code word_temp_live_scores}(V25). 운영 Redis 는 저장하지 않아 부적합하다.
 * 키는 (정답, 힌트, 모델, 기준문 버전)이고 <b>처음 쓴 값이 정본</b>이다. {@code hits} 로 놓친 낱말 수집을 겸한다 — 낱말과 횟수뿐이고
 * 사용자·방 식별자는 없다.
 *
 * <p>방 잠금 밖(채점기의 가상 스레드)에서만 부른다.
 */
public interface LiveScoreStore {

    record Key(String answer, String hint, String model, String promptVersion) {
    }

    /** @param score {@code valid} 가 아니면 0 */
    record Stored(boolean valid, int score) {
    }

    /** 이미 매긴 쌍이면 그 값 — 그리고 {@code hits} +1·{@code last_seen_at} 갱신(다시 나온 낱말). */
    Optional<Stored> findAndTouch(Key key);

    /** 처음이면 쓰고, 누가 먼저 썼으면 그 값을 돌려준다(먼저 쓴 쪽이 이긴다). */
    Stored insertFirst(Key key, Stored value);

    /** {@code since} 뒤에 새로 매긴 쌍의 수 — 하루 호출 상한을 재시작 뒤에도 잇는다. */
    int createdSince(Instant since);
}

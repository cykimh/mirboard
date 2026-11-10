package com.mirboard.domain.game.yacht.persistence;

import com.mirboard.domain.game.core.BestScoreSource;
import com.mirboard.domain.game.yacht.YachtGameDefinition;
import java.util.OptionalInt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * D-201 — 요트 개인 최고 점수: 혼자 끝까지 친 판({@code yacht_solo_scores})과 여럿 판에서 탈주하지 않고 12칸을 다 채운 판
 * ({@code FINISHED} 매치의 참가자 총점) 중 최고. 사람이 다 나가 끝난 판({@code NO_HUMANS})·액션 상한({@code ACTION_LIMIT})은 세지
 * 않는다 — 점수표가 덜 찼을 수 있다.
 */
@Component
public class YachtBestScore implements BestScoreSource {

    private final JdbcTemplate jdbc;

    public YachtBestScore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String gameType() {
        return YachtGameDefinition.ID;
    }

    @Override
    public OptionalInt bestScore(long userId) {
        Integer best = jdbc.queryForObject("""
                SELECT MAX(score) FROM (
                    SELECT total_score AS score FROM yacht_solo_scores WHERE user_id = ?
                    UNION ALL
                    SELECT p.total_score FROM yacht_match_participants p
                      JOIN yacht_match_results r ON r.id = p.match_id
                     WHERE p.user_id = ? AND NOT p.deserted AND r.end_reason = 'FINISHED'
                ) scores
                """, Integer.class, userId, userId);
        return best == null ? OptionalInt.empty() : OptionalInt.of(best);
    }
}

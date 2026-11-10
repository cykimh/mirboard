package com.mirboard.domain.game.scoring;

import com.mirboard.domain.game.core.BestScoreSource;
import com.mirboard.domain.lobby.auth.GuestPolicy;
import com.mirboard.domain.lobby.auth.User;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * D-201 — 게임별 개인 최고 점수. 게임마다 {@link BestScoreSource} 가 자기 기록에서 낸 값을 모은다. 게스트·봇은 빈 결과다(랭킹 제외와
 * 같은 규칙 — 기록기도 쓰지 않지만, 여럿 판 참가자 표에는 게스트 행이 있어 여기서도 거른다).
 */
@Service
public class BestScoreService {

    private final List<BestScoreSource> sources;

    public BestScoreService(List<BestScoreSource> sources) {
        this.sources = sources;
    }

    /** gameType → 최고 점수. 셀 판이 있는 게임만, 소스 등록 순서. */
    @Transactional(readOnly = true)
    public Map<String, Integer> bestScores(User user) {
        if (user.isBot() || GuestPolicy.isGuest(user.getUsername())) {
            return Map.of();
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        for (BestScoreSource source : sources) {
            OptionalInt best = source.bestScore(user.getId());
            if (best.isPresent()) {
                out.put(source.gameType(), best.getAsInt());
            }
        }
        return Collections.unmodifiableMap(out);
    }
}

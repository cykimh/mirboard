package com.mirboard.domain.game.scoring;

import com.mirboard.domain.game.core.HeadToHeadSource;
import com.mirboard.domain.lobby.auth.GuestPolicy;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UserRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * D-154 — 상대 전적. 게임마다 {@link HeadToHeadSource} 가 매치 기록에서 센 것을 모으고, 상대가 게스트·봇이면 뺀 뒤 이름을 붙인다.
 * 많이 붙어 본 상대부터.
 */
@Service
public class HeadToHeadService {

    private final List<HeadToHeadSource> sources;
    private final UserRepository users;

    public HeadToHeadService(List<HeadToHeadSource> sources, UserRepository users) {
        this.sources = sources;
        this.users = users;
    }

    public record Entry(String gameType, long opponentId, String opponentName, int wins, int losses, int draws) {
        int games() {
            return wins + losses + draws;
        }
    }

    /** @param gameType null 이면 모든 게임. */
    @Transactional(readOnly = true)
    public List<Entry> against(long userId, String gameType) {
        List<Map.Entry<String, HeadToHeadSource.Row>> rows = new ArrayList<>();
        for (HeadToHeadSource source : sources) {
            if (gameType != null && !gameType.equals(source.gameType())) {
                continue;
            }
            for (HeadToHeadSource.Row row : source.against(userId)) {
                rows.add(Map.entry(source.gameType(), row));
            }
        }
        Set<Long> ids = new HashSet<>();
        rows.forEach(r -> ids.add(r.getValue().opponentId()));
        Map<Long, User> people = users.findAllById(ids).stream()
                .filter(u -> !u.isBot() && !GuestPolicy.isGuest(u.getUsername()))
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return rows.stream()
                .filter(r -> people.containsKey(r.getValue().opponentId()))
                .map(r -> new Entry(r.getKey(), r.getValue().opponentId(),
                        people.get(r.getValue().opponentId()).getUsername(),
                        r.getValue().wins(), r.getValue().losses(), r.getValue().draws()))
                .sorted(Comparator.comparingInt(Entry::games).reversed()
                        .thenComparing(Entry::opponentName))
                .toList();
    }
}

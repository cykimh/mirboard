package com.mirboard.domain.game.scoring;

import com.mirboard.domain.lobby.auth.GuestPolicy;
import com.mirboard.domain.lobby.auth.User;
import com.mirboard.domain.lobby.auth.UsernamePolicy;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * D-198 — 명예 계급. 운영자가 설정({@code mirboard.tier.honorary-usernames})에 적은 계정은 모든 게임에서 {@link #HONORARY_TIER} 로
 * <b>보이기만</b> 한다. 레이팅·랭킹 순서·ELO·다른 사람의 왕관 순위는 건드리지 않는다.
 *
 * <p>계급 덮어쓰기는 이 클래스 한 곳이다 — 서버가 계급을 내보내는 출구는 전부 {@link UserGameStatsService} 를 거쳐 여기에 묻는다.
 * 이름은 NFC 로 맞춰 비교한다(D-149 별명은 NFC 로 저장된다). 게스트({@code guest-})·봇은 설정에 적혀도 대상이 아니다.
 */
@Component
public class HonoraryTierPolicy {

    /** 명예 계정이 모든 게임에서 받는 계급 — D-263 으로 별왕관에서 금메달로 낮춤. */
    public static final Tier HONORARY_TIER = Tier.GOLD_MEDAL;

    private final Set<String> usernames;

    public HonoraryTierPolicy(@Value("${mirboard.tier.honorary-usernames:}") List<String> usernames) {
        this.usernames = usernames == null ? Set.of() : usernames.stream()
                .map(n -> n == null ? "" : UsernamePolicy.normalize(n.strip()))
                .filter(n -> !n.isEmpty() && !GuestPolicy.isGuest(n))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 이름만으로 판정한다(봇이 빠진 목록 — 랭킹 — 에서 쓴다). */
    public boolean isHonorary(String username) {
        if (username == null || GuestPolicy.isGuest(username)) {
            return false;
        }
        return usernames.contains(UsernamePolicy.normalize(username));
    }

    public boolean isHonorary(User user) {
        return user != null && !user.isBot() && isHonorary(user.getUsername());
    }

    /** 명예 계정이면 {@link #HONORARY_TIER}, 아니면 받은 계급 그대로. */
    public String apply(String username, String tier) {
        return isHonorary(username) ? HONORARY_TIER.name() : tier;
    }

    public String apply(User user, String tier) {
        return isHonorary(user) ? HONORARY_TIER.name() : tier;
    }
}

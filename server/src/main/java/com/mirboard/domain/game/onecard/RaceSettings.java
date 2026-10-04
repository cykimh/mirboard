package com.mirboard.domain.game.onecard;

/**
 * 외치기 경쟁의 시간 설정 (`docs/rules-onecard.md` §9). 운영값은 {@code mirboard.onecard.*} 설정에서 온다(D-128,
 * {@code OneCardGameDefinition}) — 잘못된 값이면 기동 때 어느 조건이 틀렸는지 말하며 실패한다.
 *
 * @param windowMillis     창 길이
 * @param ownerMinMillis   1장 남은 봇이 "원카드!" 를 누르는 반응 시간 하한
 * @param ownerMaxMillis   〃 상한(포함)
 * @param catcherMinMillis 다른 봇이 "잡기!" 를 누르는 반응 시간 하한
 * @param catcherMaxMillis 〃 상한(포함)
 * @param slotCount        버튼 위치 슬롯 수 — 클라와 맞춘 프로토콜 상수
 */
public record RaceSettings(long windowMillis,
                           long ownerMinMillis,
                           long ownerMaxMillis,
                           long catcherMinMillis,
                           long catcherMaxMillis,
                           int slotCount) {

    /** 설계서 기본값 — 창 3초, 봇 반응 1.0~2.5초, 슬롯 8개. */
    public static final RaceSettings DEFAULT = new RaceSettings(3_000, 1_000, 2_500, 1_000, 2_500, 8);

    public RaceSettings {
        require(windowMillis > 0, "windowMillis must be positive: " + windowMillis);
        require(slotCount >= 1, "slotCount must be at least 1: " + slotCount);
        require(ownerMinMillis >= 0 && ownerMinMillis <= ownerMaxMillis,
                "owner reaction range must be 0 <= min <= max: " + ownerMinMillis + ".." + ownerMaxMillis);
        require(catcherMinMillis >= 0 && catcherMinMillis <= catcherMaxMillis,
                "catcher reaction range must be 0 <= min <= max: " + catcherMinMillis + ".." + catcherMaxMillis);
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException("invalid race settings — " + message);
        }
    }
}

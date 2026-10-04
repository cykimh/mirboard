package com.mirboard.domain.game.onecard;

/**
 * 외치기 경쟁의 시간 설정 (`docs/rules-onecard.md` §9). 운영값은 S3 에서 설정으로 주입한다.
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
        if (windowMillis <= 0 || slotCount < 1
                || ownerMinMillis < 0 || ownerMinMillis > ownerMaxMillis
                || catcherMinMillis < 0 || catcherMinMillis > catcherMaxMillis) {
            throw new IllegalArgumentException("invalid race settings");
        }
    }
}

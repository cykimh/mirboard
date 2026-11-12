package com.mirboard.domain.sound;

import java.util.Set;

/**
 * D-265 — 올릴 수 있는 음성 클립 키의 허용 목록. 여기 없는 키는 올리지도 내려받지도 못한다(임의 파일 저장소가 되지 않게).
 * 얏추 키의 끝은 족보 칸 이름(요트 {@code Category}), 원카드 키는 경쟁 결과({@code CALLED}·{@code CAUGHT})다.
 * 클라의 {@code features/sound/voiceClips.ts} 목록과 같아야 한다.
 */
public final class SoundClipKey {

    public static final Set<String> ALLOWED = Set.of(
            "yacht.made.SMALL_STRAIGHT",
            "yacht.made.FULL_HOUSE",
            "yacht.made.LARGE_STRAIGHT",
            "yacht.made.FOUR_OF_A_KIND",
            "yacht.made.YACHT",
            "onecard.call",
            "onecard.catch");

    private SoundClipKey() {
    }

    public static boolean isAllowed(String key) {
        return key != null && ALLOWED.contains(key);
    }
}

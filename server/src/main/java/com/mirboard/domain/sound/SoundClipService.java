package com.mirboard.domain.sound;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * D-265 — 음성 클립 저장·검증. 키는 허용 목록만, 크기는 {@link #MAX_BYTES} 이하, 형식은 요청이 선언한 Content-Type 을 믿지 않고
 * <b>파일 앞머리 바이트</b>로 판정해 그 결과를 저장한다(브라우저마다 {@code audio/x-wav}·{@code audio/mp3} 처럼 다르게 부른다).
 */
@Service
public class SoundClipService {

    /** 한 클립 최대 크기. 짧은 외침 한마디라 넉넉하다. */
    public static final int MAX_BYTES = 500 * 1024;

    private final SoundClipRepository clips;
    private final Clock clock;

    public SoundClipService(SoundClipRepository clips, Clock clock) {
        this.clips = clips;
        this.clock = clock;
    }

    public List<SoundClipRepository.Summary> list() {
        return clips.findSummaries();
    }

    /** 허용 목록에 없는 키는 저장소를 보지 않고 비어 있다. */
    public Optional<SoundClip> find(String key) {
        return SoundClipKey.isAllowed(key) ? clips.findById(key) : Optional.empty();
    }

    /** 올리기(같은 키는 덮어쓴다). */
    public SoundClip store(String key, byte[] data) {
        requireKey(key);
        if (data == null || data.length == 0) {
            throw new InvalidSoundClipException("INVALID_SOUND", "파일이 비어 있습니다");
        }
        if (data.length > MAX_BYTES) {
            throw new InvalidSoundClipException("SOUND_TOO_LARGE", "파일이 너무 큽니다(최대 500KB)");
        }
        String contentType = sniff(data);
        if (contentType == null) {
            throw new InvalidSoundClipException("UNSUPPORTED_SOUND_TYPE",
                    "지원하지 않는 형식입니다(mp3·ogg·wav·m4a·aac·webm)");
        }
        // 버전(= updated_at 밀리초)이 저장 정밀도에 잘려 달라지지 않게 밀리초로 맞춘다.
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
        // clip_key 가 assigned PK 라 save() 는 upsert.
        return clips.save(new SoundClip(key, contentType, data, now));
    }

    /** 지우기(기본 음성으로 돌아간다). 없어도 조용히 지나간다. */
    public void delete(String key) {
        requireKey(key);
        if (clips.existsById(key)) {
            clips.deleteById(key);
        }
    }

    private static void requireKey(String key) {
        if (!SoundClipKey.isAllowed(key)) {
            throw new InvalidSoundClipException("UNKNOWN_SOUND_KEY", "모르는 음성 키입니다");
        }
    }

    /** 앞머리 바이트로 오디오 형식을 판정한다. 모르는 형식이면 null. */
    static String sniff(byte[] d) {
        if (d.length < 12) {
            return null;
        }
        if (ascii(d, 0, "ID3")) {
            return "audio/mpeg";
        }
        if (ascii(d, 0, "OggS")) {
            return "audio/ogg";
        }
        if (ascii(d, 0, "RIFF") && ascii(d, 8, "WAVE")) {
            return "audio/wav";
        }
        if (ascii(d, 4, "ftyp")) {
            return "audio/mp4";
        }
        if ((d[0] & 0xFF) == 0x1A && (d[1] & 0xFF) == 0x45 && (d[2] & 0xFF) == 0xDF && (d[3] & 0xFF) == 0xA3) {
            return "audio/webm";
        }
        // MPEG 오디오 프레임 동기(11비트). 계층 비트가 00 이면 ADTS(AAC), 아니면 mp3.
        if ((d[0] & 0xFF) == 0xFF && (d[1] & 0xE0) == 0xE0) {
            return (d[1] & 0x06) == 0 ? "audio/aac" : "audio/mpeg";
        }
        return null;
    }

    private static boolean ascii(byte[] d, int offset, String text) {
        byte[] t = text.getBytes(StandardCharsets.US_ASCII);
        if (d.length < offset + t.length) {
            return false;
        }
        for (int i = 0; i < t.length; i++) {
            if (d[offset + i] != t[i]) {
                return false;
            }
        }
        return true;
    }
}

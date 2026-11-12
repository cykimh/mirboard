package com.mirboard.domain.sound;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** D-265 — 앞머리 바이트로 오디오 형식을 판정한다(선언된 Content-Type 은 믿지 않는다). */
class SoundClipSniffTest {

    private static byte[] head(int... bytes) {
        byte[] d = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            d[i] = (byte) bytes[i];
        }
        return d;
    }

    private static byte[] at(int offset, String text, byte[] into) {
        byte[] t = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(t, 0, into, offset, t.length);
        return into;
    }

    @Test
    void recognizes_supported_audio_headers() {
        assertThat(SoundClipService.sniff(at(0, "ID3", new byte[32]))).isEqualTo("audio/mpeg");
        assertThat(SoundClipService.sniff(head(0xFF, 0xFB, 0x90))).isEqualTo("audio/mpeg");
        assertThat(SoundClipService.sniff(head(0xFF, 0xF1, 0x50))).isEqualTo("audio/aac");
        assertThat(SoundClipService.sniff(at(0, "OggS", new byte[32]))).isEqualTo("audio/ogg");
        assertThat(SoundClipService.sniff(at(8, "WAVE", at(0, "RIFF", new byte[32])))).isEqualTo("audio/wav");
        assertThat(SoundClipService.sniff(at(4, "ftypM4A ", new byte[32]))).isEqualTo("audio/mp4");
        assertThat(SoundClipService.sniff(head(0x1A, 0x45, 0xDF, 0xA3))).isEqualTo("audio/webm");
    }

    @Test
    void rejects_everything_else() {
        assertThat(SoundClipService.sniff(new byte[32])).isNull();
        assertThat(SoundClipService.sniff(at(0, "RIFF", new byte[32]))).isNull(); // RIFF 지만 WAVE 아님(AVI 등)
        assertThat(SoundClipService.sniff(head(0x89, 'P', 'N', 'G'))).isNull();
        assertThat(SoundClipService.sniff("<html>hello world</html>".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(SoundClipService.sniff(at(0, "ID3", new byte[4]))).isNull(); // 너무 짧다
    }
}

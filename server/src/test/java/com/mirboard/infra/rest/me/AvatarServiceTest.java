package com.mirboard.infra.rest.me;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class AvatarServiceTest {

    private final AvatarService service = new AvatarService();

    private static byte[] pngOf(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void normalizes_to_128px_square_png() throws Exception {
        byte[] result = service.normalizeToPng(pngOf(200, 100));

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(AvatarService.SIZE);
        assertThat(decoded.getHeight()).isEqualTo(AvatarService.SIZE);
    }

    @Test
    void rejects_non_image_bytes() {
        assertThatThrownBy(() -> service.normalizeToPng("not an image".getBytes()))
                .isInstanceOf(InvalidAvatarException.class);
    }

    @Test
    void rejects_empty() {
        assertThatThrownBy(() -> service.normalizeToPng(new byte[0]))
                .isInstanceOf(InvalidAvatarException.class);
    }

    @Test
    void rejects_oversized() {
        assertThatThrownBy(() -> service.normalizeToPng(new byte[5 * 1024 * 1024]))
                .isInstanceOf(InvalidAvatarException.class);
    }

    @Test
    void rejects_oversized_dimensions_before_decoding() throws Exception {
        // 압축 바이트는 작지만 헤더가 4096px 초과 → 디코드(거대 raster 할당) 전에 거부.
        byte[] png = pngOf(AvatarService.MAX_DIMENSION + 4, 64);
        assertThatThrownBy(() -> service.normalizeToPng(png))
                .isInstanceOf(InvalidAvatarException.class)
                .hasMessageContaining("해상도");
    }
}

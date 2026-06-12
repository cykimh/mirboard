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
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_empty() {
        assertThatThrownBy(() -> service.normalizeToPng(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_oversized() {
        assertThatThrownBy(() -> service.normalizeToPng(new byte[5 * 1024 * 1024]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

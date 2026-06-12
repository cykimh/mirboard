package com.mirboard.infra.rest.me;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;

/**
 * 업로드 아바타를 정규화 — 128x128 정사각 PNG 로 center-crop + 리사이즈(D-80).
 * 새 의존성 없이 표준 {@link ImageIO} 만 사용(PNG/JPEG 입력). 부적합 입력은
 * {@link IllegalArgumentException} 으로 거부(컨트롤러가 400 으로 매핑).
 */
@Service
public class AvatarService {

    public static final int SIZE = 128;
    private static final long MAX_INPUT_BYTES = 4L * 1024 * 1024;

    public byte[] normalizeToPng(byte[] input) {
        if (input == null || input.length == 0) {
            throw new IllegalArgumentException("빈 이미지입니다");
        }
        if (input.length > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException("이미지가 너무 큽니다(최대 4MB)");
        }
        BufferedImage src;
        try {
            src = ImageIO.read(new ByteArrayInputStream(input));
        } catch (IOException e) {
            throw new IllegalArgumentException("이미지를 읽을 수 없습니다");
        }
        if (src == null) {
            throw new IllegalArgumentException("지원하지 않는 이미지 형식입니다(PNG/JPEG)");
        }
        BufferedImage out = cropSquareAndScale(src, SIZE);
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(out, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("이미지 변환에 실패했습니다");
        }
    }

    private static BufferedImage cropSquareAndScale(BufferedImage src, int size) {
        int w = src.getWidth();
        int h = src.getHeight();
        int side = Math.min(w, h);
        int x = (w - side) / 2;
        int y = (h - side) / 2;
        BufferedImage cropped = src.getSubimage(x, y, side, side);

        BufferedImage dst = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(cropped, 0, 0, size, size, null);
        g.dispose();
        return dst;
    }
}

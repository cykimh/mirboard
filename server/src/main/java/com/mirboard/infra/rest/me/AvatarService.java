package com.mirboard.infra.rest.me;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.stereotype.Service;

/**
 * 업로드 아바타를 정규화 — 128x128 정사각 PNG 로 center-crop + 리사이즈(D-80).
 * 새 의존성 없이 표준 {@link ImageIO} 만 사용(PNG/JPEG 입력). 부적합 입력은
 * {@link InvalidAvatarException} 으로 거부(GlobalExceptionHandler 가 400 매핑).
 *
 * <p>보안: 압축 바이트뿐 아니라 <b>디코드 전에 헤더 픽셀 크기</b>를 먼저 검사한다 —
 * 작게 압축됐지만 거대한 캔버스를 선언한 이미지(decompression bomb)가 width*height*4
 * 바이트의 raster 를 할당해 OOM 을 내는 것을 막는다.
 */
@Service
public class AvatarService {

    public static final int SIZE = 128;
    /** 디코드 허용 최대 변(픽셀). 4096^2 * 4B ≈ 64MB 상한. */
    static final int MAX_DIMENSION = 4096;
    private static final long MAX_INPUT_BYTES = 4L * 1024 * 1024;

    public byte[] normalizeToPng(byte[] input) {
        if (input == null || input.length == 0) {
            throw new InvalidAvatarException("빈 이미지입니다");
        }
        if (input.length > MAX_INPUT_BYTES) {
            throw new InvalidAvatarException("이미지가 너무 큽니다(최대 4MB)");
        }
        BufferedImage src = decodeBounded(input);
        BufferedImage out = cropSquareAndScale(src, SIZE);
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(out, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new InvalidAvatarException("이미지 변환에 실패했습니다");
        }
    }

    /** 헤더에서 크기 확인 → 과대 해상도 거부 → 그 다음에만 raster 디코드. */
    private static BufferedImage decodeBounded(byte[] input) {
        try (ImageInputStream iis =
                ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
            if (iis == null) {
                throw new InvalidAvatarException("이미지를 읽을 수 없습니다");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new InvalidAvatarException("지원하지 않는 이미지 형식입니다(PNG/JPEG)");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w > MAX_DIMENSION || h > MAX_DIMENSION) {
                    throw new InvalidAvatarException(
                            "이미지 해상도가 너무 큽니다(최대 " + MAX_DIMENSION + "px)");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new InvalidAvatarException("이미지를 읽을 수 없습니다");
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

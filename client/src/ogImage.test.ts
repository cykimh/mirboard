/* eslint-disable @typescript-eslint/ban-ts-comment */
// @ts-ignore — 클라 tsconfig 에 @types/node 가 없다. vitest 는 Node 에서 돌아 런타임은 안전하다(indexHtml.test 와 같은 패턴).
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

/**
 * D-138 — 카톡 링크 미리보기 이미지. 서버 방 미리보기(`RoomPreviewRenderer`)가 `{사이트}/og-image.jpg` 를 `og:image` 로 싣는다 —
 * 빌드가 `public/` 을 그대로 복사하므로 파일이 여기 있어야 하고, 카톡·트위터가 큰 카드로 보이는 1200×630 이어야 한다(SVG 는 받지 않는다).
 */
function readBytes(relative: string): Uint8Array {
  const read = readFileSync as unknown as (p: URL) => Uint8Array;
  return read(new URL(relative, import.meta.url));
}

/** JPEG SOFn 마커에서 가로·세로를 읽는다(SOF0~SOF15, DHT·JPG·DAC 제외). */
function jpegSize(bytes: Uint8Array): { width: number; height: number } | null {
  let i = 2;
  while (i + 9 < bytes.length) {
    if (bytes[i] !== 0xff) return null;
    const marker = bytes[i + 1];
    const length = (bytes[i + 2] << 8) | bytes[i + 3];
    if (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) {
      return { height: (bytes[i + 5] << 8) | bytes[i + 6], width: (bytes[i + 7] << 8) | bytes[i + 8] };
    }
    i += 2 + length;
  }
  return null;
}

describe('public/og-image.jpg — 링크 미리보기 이미지 (D-138)', () => {
  const bytes = readBytes('../public/og-image.jpg');

  it('JPEG 이다', () => {
    expect([bytes[0], bytes[1]]).toEqual([0xff, 0xd8]);
  });

  it('1200×630 이다 — 카톡·트위터 큰 카드 비율', () => {
    expect(jpegSize(bytes)).toEqual({ width: 1200, height: 630 });
  });

  it('300KB 를 넘지 않는다 — 스크래퍼가 가볍게 받는다', () => {
    expect(bytes.length).toBeLessThan(300 * 1024);
  });
});

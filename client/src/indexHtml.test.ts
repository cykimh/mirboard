/* eslint-disable @typescript-eslint/ban-ts-comment */
// @ts-ignore — 클라 tsconfig 에 @types/node 가 없다. vitest 는 Node 에서 돌아 런타임은 안전하다
// (styles/cssSources.ts 와 같은 패턴).
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

/**
 * D-133 — 링크 미리보기(Open Graph)의 화면 틀. 카톡 같은 스크래퍼는 자바스크립트를 실행하지 않으므로 미리보기 메타는
 * `index.html` 원문에 있어야 한다. 서버(`RoomPagePreviewController`)는 `GET /rooms/{roomId}` 에서 빌드된 이 파일의
 * `<!--og:start-->`…`<!--og:end-->` 구간을 그 방의 미리보기로 바꾼다 — 그래서 표식과 구간 안의 내용을 원문으로 고정한다.
 */

/** 이 파일 기준 상대 경로의 원문. 경로를 인자로 받는 이유: `new URL('리터럴', import.meta.url)` 은 Vite 가 자산 주소로 바꿔 버린다. */
function readSource(relative: string): string {
  const read = readFileSync as unknown as (p: URL, enc: string) => string;
  return read(new URL(relative, import.meta.url), 'utf-8');
}

const html = readSource('../index.html');

const START = '<!--og:start-->';
const END = '<!--og:end-->';

function count(haystack: string, needle: string): number {
  return haystack.split(needle).length - 1;
}

function block(): string {
  return html.slice(html.indexOf(START) + START.length, html.indexOf(END));
}

function outside(): string {
  return html.slice(0, html.indexOf(START)) + html.slice(html.indexOf(END) + END.length);
}

describe('index.html — 사이트 공통 미리보기와 표식 (D-133)', () => {
  it('표식이 <head> 안에 한 쌍, 시작이 끝보다 앞에 있다', () => {
    expect(count(html, START)).toBe(1);
    expect(count(html, END)).toBe(1);
    expect(html.indexOf(START)).toBeLessThan(html.indexOf(END));
    expect(html.indexOf(END)).toBeLessThan(html.indexOf('</head>'));
  });

  it('구간 안에 사이트 공통 Open Graph 가 있다 — 제목은 "미르보드카페 — 티츄·스컬킹·원카드"', () => {
    const og = block();
    expect(og).toContain('<meta property="og:title" content="미르보드카페 — 티츄·스컬킹·원카드" />');
    expect(og).toContain('<meta property="og:site_name" content="미르보드카페" />');
    expect(og).toContain('<meta property="og:type" content="website" />');
    expect(og).toContain('<meta property="og:locale" content="ko_KR" />');
    expect(og).toContain('<meta name="twitter:card" content="summary" />');
    expect(og).toMatch(/<meta property="og:description" content="[^"]+" \/>/);
    expect(og).toMatch(/<meta name="description" content="[^"]+" \/>/);
  });

  it('<title> 은 구간 안에 하나뿐이다 — 서버가 방 제목으로 바꿔도 제목이 둘이 되지 않게', () => {
    expect(count(block(), '<title>')).toBe(1);
    expect(count(outside(), '<title>')).toBe(0);
  });

  it('구간 밖에는 미리보기 메타가 없고, 공통 미리보기에 사이트 주소(og:url)를 박지 않는다', () => {
    expect(outside()).not.toMatch(/og:|twitter:|name="description"/);
    expect(block()).not.toContain('og:url');
  });
});

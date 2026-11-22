import { describe, expect, it } from 'vitest';
import { gameSurfaceCssSource, indexCssSource } from './cssSources';
// @ts-ignore — 테스트에서만 쓰는 Node 모듈(cssSources 와 같은 이유).
import { readFileSync } from 'node:fs';

/**
 * D-292 — 게임판에서 글자 선택·길게 누르기 메뉴·그림 끌기를 막는 규칙은 `28-game-surface.css` 한 곳에만 있고, `RoomPage` 가 두
 * 게임판 갈래(티츄 / 나머지 여섯) 모두의 `<main>` 에 `game-surface` 를 얹는다.
 */
const cssCode = gameSurfaceCssSource.replace(/\/\*[\s\S]*?\*\//g, '');
const bodyOf = (selector: string) => {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(`(^|})\\s*${escaped}\\s*\\{([^}]*)\\}`).exec(cssCode)?.[2] ?? '';
};
// 리터럴 `new URL('…', import.meta.url)` 은 Vite 가 자산 주소로 바꾼다 — 인자로 받아 넘긴다.
const source = (relative: string) =>
  (readFileSync as unknown as (p: URL, enc: string) => string)(new URL(relative, import.meta.url), 'utf-8');

describe('28-game-surface.css — 게임판 공통 조작(D-292)', () => {
  it('게임판 루트에서 글자 선택·길게 누르기 메뉴를 막는다', () => {
    const root = bodyOf('.game-surface');
    expect(root).toContain('user-select: none');
    expect(root).toContain('-webkit-user-select: none');
    expect(root).toContain('-webkit-touch-callout: none');
  });

  it('그림·링크 끌기를 막는다', () => {
    expect(cssCode).toMatch(/\.game-surface img,[^{]*\{\s*-webkit-user-drag: none;/);
  });

  it('입력란과 game-selectable 은 예외로 선택된다', () => {
    const m = /\.game-surface input,([^{]*)\{([^}]*)\}/.exec(cssCode);
    expect(m).not.toBeNull();
    const selectors = `.game-surface input,${m![1]}`;
    for (const s of ['.game-surface textarea', '.game-surface select', '[contenteditable]', '.game-surface .game-selectable']) {
      expect(selectors, s).toContain(s);
    }
    expect(m![2]).toContain('user-select: text');
    expect(m![2]).toContain('-webkit-user-select: text');
  });

  it('모든 선택자가 .game- 클래스를 포함하고 미디어가 없다(공용 클래스 재정의 금지)', () => {
    const groups = [...cssCode.matchAll(/(^|})\s*([^{}@]+?)\s*\{/g)].map((g) => g[2].trim());
    expect(groups.length).toBeGreaterThan(2);
    expect(groups.flatMap((g) => g.split(',')).filter((one) => !one.includes('.game-'))).toEqual([]);
    expect(cssCode).not.toMatch(/@media/);
  });

  it('index.css 에서 게임판 part 뒤, 17 앞에 import 된다', () => {
    const i25 = indexCssSource.indexOf("'./parts/25-tichu-lacquer.css'");
    const i28 = indexCssSource.indexOf("'./parts/28-game-surface.css'");
    const i17 = indexCssSource.indexOf("'./parts/17-responsive.css'");
    expect(i28).toBeGreaterThan(i25);
    expect(i17).toBeGreaterThan(i28);
  });

  it('RoomPage 의 게임판 <main> 은 모두 game-surface 를 단다 — 대기실 셸은 달지 않는다', () => {
    const page = source('../pages/RoomPage.tsx');
    const mains = [...page.matchAll(/<main className="([^"]*)"/g)].map((m) => m[1]);
    expect(mains.length).toBe(2);
    for (const cls of mains) expect(cls.split(' ')).toEqual(expect.arrayContaining(['room-page', 'game-surface']));
    expect(page.match(/game-surface/g)?.length).toBe(2);
  });

  it('방 채팅 목록은 복사할 수 있게 game-selectable 을 단다', () => {
    const chat = source('../features/chat/RoomChat.tsx');
    expect(chat).toContain('className="room-chat-list game-selectable"');
  });
});

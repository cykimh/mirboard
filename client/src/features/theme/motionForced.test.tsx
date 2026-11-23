import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
// @ts-ignore — 테스트에서만 쓰는 Node 모듈(cssSources 와 같은 이유).
import { readFileSync, readdirSync } from 'node:fs';
import { MOTION_FORCED_CLASS, motionReduced, useCardAnimStore } from './cardAnimStore';
import { GameMenu } from '@/components/GameMenu';
import { GoldBurst } from '@/features/yacht/GoldBurst';
import { prefersReducedMotion as yachtReduced } from '@/features/yacht/yachtTheme';
import { prefersReducedMotion as tichuReduced } from '@/features/tichu/tichuTheme';
import { prefersReducedMotion as onecardReduced } from '@/features/onecard/useCardPlayFlight';

/**
 * D-291 — 연출을 보일지의 단일 기준. 메뉴에서 '연출'을 **직접 켠** 사람(저장값 '1')은 OS '동작 줄이기'여도 연출을 본다:
 * `<html>` 에 `motion-forced` 가 붙고, CSS 의 동작 줄이기 블록은 전부 `:root:not(.motion-forced)` 아래라 닿지 않으며,
 * JS 연출은 `motionReduced()` 하나로 묻는다. 저장값이 없으면 OS 를 따르고, 직접 끄면 항상 꺼진다.
 */
const KEY = 'mirboard.cardAnim';
const html = () => document.documentElement;

function osReduce(on: boolean) {
  vi.stubGlobal('matchMedia', (q: string) => ({
    matches: on && q.includes('reduce'),
    media: q,
    onchange: null,
    addEventListener() {},
    removeEventListener() {},
    addListener() {},
    removeListener() {},
    dispatchEvent: () => false,
  }));
}

describe('D-291 — 연출 설정이 OS 동작 줄이기보다 앞선다', () => {
  beforeEach(() => {
    localStorage.clear();
    html().classList.remove(MOTION_FORCED_CLASS);
    useCardAnimStore.setState({ enabled: true, forced: false });
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
    html().classList.remove(MOTION_FORCED_CLASS);
    useCardAnimStore.setState({ enabled: true, forced: false });
  });

  it('OS 동작 줄이기 + 직접 켬(저장값 1) → 연출 켬, <html> 에 motion-forced, JS 연출도 돈다', () => {
    osReduce(true);
    localStorage.setItem(KEY, '1');
    useCardAnimStore.getState().init();
    expect(useCardAnimStore.getState().enabled).toBe(true);
    expect(useCardAnimStore.getState().forced).toBe(true);
    expect(html()).toHaveClass(MOTION_FORCED_CLASS);
    expect(motionReduced()).toBe(false);
    // 게임별 물음도 같은 답이다(각자 matchMedia 를 읽지 않는다).
    expect(yachtReduced()).toBe(false);
    expect(tichuReduced()).toBe(false);
    expect(onecardReduced()).toBe(false);
  });

  it('OS 동작 줄이기 + 저장값 없음 → OS 를 따른다(연출 끔, 클래스 없음, JS 연출도 쉰다)', () => {
    osReduce(true);
    useCardAnimStore.getState().init();
    expect(useCardAnimStore.getState().enabled).toBe(false);
    expect(useCardAnimStore.getState().forced).toBe(false);
    expect(html()).not.toHaveClass(MOTION_FORCED_CLASS);
    expect(motionReduced()).toBe(true);
    expect(yachtReduced()).toBe(true);
    expect(tichuReduced()).toBe(true);
    expect(onecardReduced()).toBe(true);
  });

  it('직접 끔(저장값 0)이면 OS 와 무관하게 꺼진다 — 클래스도 없다', () => {
    for (const reduce of [true, false]) {
      osReduce(reduce);
      localStorage.setItem(KEY, '0');
      useCardAnimStore.getState().init();
      expect(useCardAnimStore.getState().enabled).toBe(false);
      expect(html()).not.toHaveClass(MOTION_FORCED_CLASS);
    }
  });

  it('OS 가 동작 줄이기가 아니면 저장값이 없어도 켜져 있고, 줄일 것이 없다', () => {
    osReduce(false);
    useCardAnimStore.getState().init();
    expect(useCardAnimStore.getState().enabled).toBe(true);
    expect(motionReduced()).toBe(false);
  });

  it('OS 동작 줄이기에서도 게임 메뉴에 "연출"이 보이고, 켜면 그때부터 연출이 돈다(껐다 켜도 마찬가지)', () => {
    osReduce(true);
    useCardAnimStore.getState().init();
    render(<GameMenu />);
    fireEvent.click(screen.getByRole('button', { name: '게임 메뉴' }));
    const toggle = screen.getByRole('menuitemcheckbox', { name: /연출/ });
    expect(toggle).toHaveAttribute('aria-checked', 'false');
    expect(toggle).not.toBeDisabled();
    fireEvent.click(toggle);
    expect(localStorage.getItem(KEY)).toBe('1');
    expect(useCardAnimStore.getState().enabled).toBe(true);
    expect(html()).toHaveClass(MOTION_FORCED_CLASS);
    expect(motionReduced()).toBe(false);
    useCardAnimStore.getState().toggle();
    expect(html()).not.toHaveClass(MOTION_FORCED_CLASS);
    expect(motionReduced()).toBe(true);
  });

  it('게임이 제 이름의 토글(card-anim)을 주면 그것만 보인다(티츄 "카드 애니메이션")', () => {
    render(<GameMenu toggles={[{ key: 'card-anim', label: '카드 애니메이션', checked: true, onToggle: vi.fn() }]} />);
    fireEvent.click(screen.getByRole('button', { name: '게임 메뉴' }));
    expect(screen.getByRole('menuitemcheckbox', { name: /카드 애니메이션/ })).toBeInTheDocument();
    expect(screen.queryByRole('menuitemcheckbox', { name: /^연출/ })).toBeNull();
  });

  it('금가루(GoldBurst)는 그려진다 — 컴포넌트가 깨지지 않는다', () => {
    osReduce(true);
    localStorage.setItem(KEY, '1');
    useCardAnimStore.getState().init();
    const { container } = render(<GoldBurst x={10} y={10} count={4} />);
    expect(container.querySelector('.yt-burst')).not.toBeNull();
  });
});

describe('D-291 — 소스 규칙(단일 기준)', () => {
  // 리터럴 `new URL('…', import.meta.url)` 은 Vite 가 자산 주소로 바꾼다 — 인자로 받아 넘긴다.
  const at = (relative: string) => new URL(relative, import.meta.url);
  const read = (u: URL) => (readFileSync as unknown as (p: URL, enc: string) => string)(u, 'utf-8');
  const list = (u: URL) =>
    (readdirSync as unknown as (p: URL, o: { recursive: boolean }) => string[])(u, { recursive: true }).map(String);

  it('CSS 의 동작 줄이기 블록은 전부 :root:not(.motion-forced) 아래에 있다', () => {
    const dir = at('../../styles/');
    const files = list(dir).filter((f) => f.endsWith('.css'));
    expect(files.length).toBeGreaterThan(10);
    let blocks = 0;
    const offenders: string[] = [];
    for (const f of files) {
      const css = read(new URL(f, dir)).replace(/\/\*[\s\S]*?\*\//g, '');
      const head = '@media (prefers-reduced-motion: reduce) {';
      // 다른 꼴의 동작 줄이기 미디어(조건을 섞은 것)를 몰래 두지 않는다.
      expect(css.split('prefers-reduced-motion').length - 1, f).toBe(css.split(head).length - 1);
      let from = 0;
      for (;;) {
        const i = css.indexOf(head, from);
        if (i < 0) break;
        let depth = 1;
        let j = i + head.length;
        while (depth > 0) {
          if (css[j] === '{') depth++;
          else if (css[j] === '}') depth--;
          j++;
        }
        const inner = css.slice(i + head.length, j - 1);
        blocks++;
        for (const m of inner.matchAll(/(^|})\s*([^{}]+?)\s*\{/g)) {
          for (const one of m[2].split(/,(?![^(]*\))/)) {
            if (!one.trim().startsWith(':root:not(.motion-forced) ')) offenders.push(`${f}: ${one.trim()}`);
          }
        }
        from = j;
      }
    }
    expect(blocks).toBeGreaterThanOrEqual(12);
    expect(offenders).toEqual([]);
  });

  it('JS 는 OS 동작 줄이기를 cardAnimStore 한 곳에서만 읽는다', () => {
    const dir = at('../../');
    const files = list(dir).filter((f) => /\.tsx?$/.test(f) && !/\.test\.tsx?$/.test(f));
    expect(files.length).toBeGreaterThan(100);
    const readers = files.filter((f) => read(new URL(f, dir)).includes('prefers-reduced-motion'));
    expect(readers.map((f) => f.replace(/\\/g, '/'))).toEqual(['features/theme/cardAnimStore.ts']);
  });
});

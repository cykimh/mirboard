import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { yachtCssSource } from '@/styles/cssSources';
import { YachtDie } from './YachtDie';
import { YachtSkinPicker } from './YachtSkinPicker';
import {
  BOARD_SKINS,
  BOARD_SKIN_KEY,
  BOARD_SKIN_LABEL,
  DICE_PALETTES,
  DICE_SKINS,
  DICE_SKIN_KEY,
  DICE_SKIN_LABEL,
  loadSkin,
  useYachtSkinStore,
} from './yachtSkins';

/** WCAG 상대 휘도·명암비 — 색이 아니라 밝기 차이(색약에서도 같다). */
function luminance(hex: string): number {
  const n = parseInt(hex.slice(1), 16);
  const lin = (v: number) => {
    const c = v / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * lin((n >> 16) & 255) + 0.7152 * lin((n >> 8) & 255) + 0.0722 * lin(n & 255);
}
function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

afterEach(() => {
  cleanup();
  localStorage.clear();
  useYachtSkinStore.setState({ dice: 'ivory', board: 'felt' });
});

describe('D-296 — 얏추 스킨', () => {
  it('주사위 4종·경기판 4종이 기본 말고 더 있고, 저마다 이름이 있다', () => {
    expect(DICE_SKINS.length).toBeGreaterThanOrEqual(4);
    expect(BOARD_SKINS.length).toBeGreaterThanOrEqual(4);
    expect(DICE_SKINS[0]).toBe('ivory');
    expect(BOARD_SKINS[0]).toBe('felt');
    for (const s of DICE_SKINS) expect(DICE_SKIN_LABEL[s], s).toMatch(/\S/);
    for (const s of BOARD_SKINS) expect(BOARD_SKIN_LABEL[s], s).toMatch(/\S/);
    expect(new Set(Object.values(DICE_SKIN_LABEL)).size).toBe(DICE_SKINS.length);
    expect(new Set(Object.values(BOARD_SKIN_LABEL)).size).toBe(BOARD_SKINS.length);
  });

  it('눈은 밝기 차이로 읽힌다 — 어느 스킨이든 눈과 몸통의 명암비 4.5 이상, 작은 주사위는 7 이상', () => {
    for (const skin of DICE_SKINS) {
      const p = DICE_PALETTES[skin];
      // 큰 주사위: 눈의 대부분을 칠하는 가운데 색 ↔ 몸통 그라데이션의 세 단계 모두.
      for (const body of p.body) expect(contrast(p.pip[1], body), `${skin} 눈 ${p.pip[1]} / 몸통 ${body}`).toBeGreaterThanOrEqual(4.5);
      // 눈의 가장 약한 단계도 몸통 가운데 색과는 3 이상(그림 요소 기준).
      for (const pip of p.pip) expect(contrast(pip, p.body[1]), `${skin} 눈 ${pip}`).toBeGreaterThanOrEqual(3);
      expect(contrast(p.miniPip, p.mini), `${skin} 작은 주사위`).toBeGreaterThanOrEqual(7);
    }
  });

  it('기본(상아) 색은 그대로다 — 스킨을 고르지 않은 사람의 화면은 바뀌지 않는다', () => {
    expect(DICE_PALETTES.ivory.body).toEqual(['#fffaf0', '#f3e9d2', '#d9caa6']);
    expect(DICE_PALETTES.ivory.mini).toBe('#f7efdc');
    expect(DICE_PALETTES.ivory.miniPip).toBe('#1b1c1f');
    const { container } = render(<YachtDie face={3} mini />);
    expect(container.querySelector('.yt-die-svg-body')).toHaveAttribute('fill', '#f7efdc');
    expect(container.querySelectorAll('.yt-die-svg-pip')).toHaveLength(3);
  });

  it('고른 주사위 스킨으로 칠한다 — 큰 주사위·작은 주사위 모두, 눈 수는 그대로', () => {
    useYachtSkinStore.getState().setDice('obsidian');
    const { container } = render(
      <>
        <YachtDie face={6} />
        <YachtDie face={2} mini />
      </>,
    );
    const [big, mini] = Array.from(container.querySelectorAll('svg'));
    expect(big.querySelectorAll('.yt-die-svg-pip')).toHaveLength(6);
    expect(big.querySelector('stop')).toHaveAttribute('stop-color', DICE_PALETTES.obsidian.body[0]);
    expect(mini.querySelector('.yt-die-svg-body')).toHaveAttribute('fill', DICE_PALETTES.obsidian.mini);
    expect(mini.querySelector('.yt-die-svg-pip')).toHaveAttribute('fill', DICE_PALETTES.obsidian.miniPip);
  });

  it('고른 값을 이 브라우저에 기억하고, 모르는 값·저장 불가는 기본값으로 읽는다', () => {
    useYachtSkinStore.getState().setDice('amber');
    useYachtSkinStore.getState().setBoard('night');
    expect(localStorage.getItem(DICE_SKIN_KEY)).toBe('amber');
    expect(localStorage.getItem(BOARD_SKIN_KEY)).toBe('night');
    expect(loadSkin(DICE_SKIN_KEY, DICE_SKINS, 'ivory')).toBe('amber');
    expect(loadSkin(BOARD_SKIN_KEY, BOARD_SKINS, 'felt')).toBe('night');

    localStorage.setItem(DICE_SKIN_KEY, 'neon-pink');
    expect(loadSkin(DICE_SKIN_KEY, DICE_SKINS, 'ivory')).toBe('ivory');
    // 허용 목록 밖의 값은 스토어도 받지 않는다.
    useYachtSkinStore.getState().setBoard('lava' as never);
    expect(useYachtSkinStore.getState().board).toBe('night');

    const get = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('막힘');
    });
    const set = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('막힘');
    });
    expect(loadSkin(DICE_SKIN_KEY, DICE_SKINS, 'ivory')).toBe('ivory');
    // 저장이 막혀도 이번 화면에는 적용된다(던지지 않는다).
    expect(() => useYachtSkinStore.getState().setDice('jade')).not.toThrow();
    expect(useYachtSkinStore.getState().dice).toBe('jade');
    get.mockRestore();
    set.mockRestore();
  });

  it('경기판 스킨마다 라이트·다크 토큰과 무늬가 CSS 에 있고, 외부 에셋을 부르지 않는다', () => {
    for (const skin of BOARD_SKINS) {
      const light = new RegExp(`\\n\\.yt-board-${skin} \\{([^}]*)\\}`).exec(yachtCssSource)?.[1] ?? '';
      const dark = new RegExp(`\\nhtml\\.dark \\.yt-board-${skin} \\{([^}]*)\\}`).exec(yachtCssSource)?.[1] ?? '';
      for (const token of ['--yt-s-felt:', '--yt-s-felt-hi:', '--yt-s-felt-lo:', '--yt-s-rail:', '--yt-s-rail-hi:', '--yt-s-rail-lo:']) {
        expect(light, `${skin} 라이트 ${token}`).toContain(token);
        expect(dark, `${skin} 다크 ${token}`).toContain(token);
      }
      expect(light, skin).toContain('--yt-s-felt-pad:');
      expect(light, skin).toContain('--yt-board-pattern:');
    }
    // 그림은 인라인(data:)뿐 — http(s) 주소는 SVG 이름공간 선언 말고는 없다.
    const urls = [...yachtCssSource.matchAll(/url\(([^)]*)\)/g)].map((m) => m[1]);
    expect(urls.every((u) => u.startsWith('"data:image/svg+xml,'))).toBe(true);
    expect(yachtCssSource.replace(/http:\/\/www\.w3\.org\/2000\/svg/g, '')).not.toMatch(/https?:\/\//);
    // 판 배경이 무늬 한 겹을 쓴다.
    const felt = /\n\.yt-theme-salon \.yt-felt \{([^}]*)\}/.exec(yachtCssSource)?.[1] ?? '';
    expect(felt).toContain('var(--yt-board-pattern),');
  });

  it('꾸미기 창 — 라디오 두 묶음, 고르면 바로 적용·기억, Esc·닫기·바깥 누르기로 닫힌다', () => {
    const onClose = vi.fn();
    const { container } = render(<YachtSkinPicker onClose={onClose} />);
    const dialog = screen.getByRole('dialog', { name: '주사위·판 꾸미기' });
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    const diceGroup = within(dialog).getByRole('group', { name: '주사위' });
    const boardGroup = within(dialog).getByRole('group', { name: '경기판' });
    expect(within(diceGroup).getAllByRole('radio')).toHaveLength(DICE_SKINS.length);
    expect(within(boardGroup).getAllByRole('radio')).toHaveLength(BOARD_SKINS.length);
    // 열리면 고른 것(기본)에 초점.
    expect(within(diceGroup).getByRole('radio', { name: /상아/ })).toBeChecked();
    expect(within(diceGroup).getByRole('radio', { name: /상아/ })).toHaveFocus();

    fireEvent.click(within(diceGroup).getByRole('radio', { name: '호박' }));
    expect(useYachtSkinStore.getState().dice).toBe('amber');
    expect(localStorage.getItem(DICE_SKIN_KEY)).toBe('amber');
    // 고른 것은 "✓" 글자로도 알린다(색만으로 가르지 않는다).
    expect(within(diceGroup).getByRole('radio', { name: '✓ 호박' })).toBeChecked();

    fireEvent.click(within(boardGroup).getByRole('radio', { name: '바다' }));
    expect(useYachtSkinStore.getState().board).toBe('ocean');
    expect(container.querySelector('.yt-skin-option-on .yt-skin-board')).toHaveClass('yt-board-ocean');

    fireEvent.keyDown(dialog, { key: 'Escape' });
    fireEvent.click(within(dialog).getByRole('button', { name: '닫기' }));
    fireEvent.click(container.querySelector('.yt-skin-layer')!);
    expect(onClose).toHaveBeenCalledTimes(3);
    // 창 안을 누른 것은 닫지 않는다.
    fireEvent.click(dialog);
    expect(onClose).toHaveBeenCalledTimes(3);
  });
});

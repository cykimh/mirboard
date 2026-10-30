import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { TierBadge } from './TierBadge';
import { themeCssSource } from '@/styles/cssSources';

/** D-164 — 색은 계급마다가 아니라 색 계열마다 하나. */
const TIERS = ['skull', 'iron', 'bronze', 'silver', 'gold', 'royal'] as const;

/** 블록(`:root { … }`·`.dark { … }`) 안의 `--tier-*` 값. */
function tierVars(selector: string): Record<string, string> {
  const start = themeCssSource.indexOf(`${selector} {`);
  const block = themeCssSource.slice(start, themeCssSource.indexOf('}', start));
  const out: Record<string, string> = {};
  for (const m of block.matchAll(/--tier-([a-z]+):\s*(#[0-9a-f]{6})/gi)) out[m[1]] = m[2];
  return out;
}

/** WCAG 상대 휘도 대비. */
function contrast(hex: string, bg: string): number {
  const lum = (h: string) => {
    const [r, g, b] = [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16) / 255).map((c) =>
      c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4,
    );
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
  };
  const [a, b] = [lum(hex), lum(bg)].sort((x, y) => y - x);
  return (a + 0.05) / (b + 0.05);
}

describe('TierBadge — 테마별 색 (D-147)', () => {
  it('색은 테마 변수로 칠한다 — 라이트·다크가 각자 값을 갖는다', () => {
    render(<TierBadge tier="SILVER_MEDAL" rating={1260} />);
    const badge = screen.getByText('은메달').parentElement!;
    expect(badge.style.color).toBe('var(--tier-silver)');
    expect(badge.style.borderColor).toBe('var(--tier-silver)');
  });

  it('라이트·다크 모두 여섯 색 계열을 정의한다', () => {
    expect(Object.keys(tierVars(':root')).sort()).toEqual([...TIERS].sort());
    expect(Object.keys(tierVars('.dark')).sort()).toEqual([...TIERS].sort());
  });

  it('라이트 색은 흰 배경에서 글자 대비 4.5:1 이상이다', () => {
    const light = tierVars(':root');
    for (const t of TIERS) expect(contrast(light[t], '#ffffff'), t).toBeGreaterThanOrEqual(4.5);
  });

  it('다크 색은 다크 배경(#020817)에서 글자 대비 4.5:1 이상이다', () => {
    const dark = tierVars('.dark');
    for (const t of TIERS) expect(contrast(dark[t], '#020817'), t).toBeGreaterThanOrEqual(4.5);
  });
});

/** D-164 — 포트리스2식 21단계. 이름·아이콘(우리가 그린 SVG)·색 계열. */
describe('TierBadge — 포트리스2식 계급 (D-164)', () => {
  it('계급 이름과 아이콘을 보이고, 점수는 옆에', () => {
    const { container } = render(<TierBadge tier="MISSILE_1" rating={1000} />);
    expect(screen.getByText('미사일 I')).toBeInTheDocument();
    expect(screen.getByText('1000')).toBeInTheDocument();
    expect(container.querySelector('svg')).not.toBeNull();
    expect((screen.getByText('미사일 I').parentElement as HTMLElement).style.color).toBe('var(--tier-iron)');
  });

  it('왕관 넷과 해골도 이름이 있다 — 별왕관은 따로 빛나는 색', () => {
    render(<TierBadge tier="STAR_CROWN" />);
    expect((screen.getByText('별왕관').parentElement as HTMLElement).style.color).toBe('var(--tier-royal)');
    render(<TierBadge tier="SKULL" />);
    expect(screen.getByText('해골')).toBeInTheDocument();
  });
});

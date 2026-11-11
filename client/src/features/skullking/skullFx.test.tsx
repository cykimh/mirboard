import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { PlayedCardView, SkullCard, SpecialKind, SkullSuit, TigressMode } from '@/types/skullking';
import { captureBonus } from './skullFx';
import { SkullCardChip } from './SkullCardChip';
import { SkullTrickRow } from './SkullTrickRow';
import { cabinTheme } from './skullKingTheme';

const sp = (special: SpecialKind): SkullCard => ({ suit: null, rank: 0, special });
const su = (suit: SkullSuit, rank: number): SkullCard => ({ suit, rank, special: null });
const pc = (seat: number, card: SkullCard, declaredAs: TigressMode | null = null): PlayedCardView => ({
  seat,
  card,
  declaredAs,
});

/** D-231 — 포획 보너스 표시(룰 §11). 점수 권위는 서버다 — 이 값은 트릭 위에 띄우는 숫자일 뿐. */
describe('captureBonus', () => {
  it('스컬킹이 잡은 해적은 1장당 30 — 해적 선언 티그리스도 센다', () => {
    const cards = [pc(0, sp('PIRATE')), pc(1, sp('SKULL_KING')), pc(2, sp('TIGRESS'), 'PIRATE'), pc(3, su('BLACK', 14))];
    expect(captureBonus(cards, 1)).toBe(60);
  });

  it('탈출 선언 티그리스는 해적이 아니다', () => {
    expect(captureBonus([pc(0, sp('SKULL_KING')), pc(1, sp('TIGRESS'), 'ESCAPE')], 0)).toBe(0);
  });

  it('인어가 스컬킹을 잡으면 40 — 같이 있던 해적은 세지 않는다(§13-⑨)', () => {
    const cards = [pc(0, sp('PIRATE')), pc(1, sp('SKULL_KING')), pc(2, sp('MERMAID'))];
    expect(captureBonus(cards, 2)).toBe(40);
  });

  it('해적이 잡은 인어는 1장당 20', () => {
    const cards = [pc(0, sp('MERMAID')), pc(1, sp('TIGRESS'), 'PIRATE'), pc(2, sp('MERMAID'))];
    expect(captureBonus(cards, 1)).toBe(40);
  });

  it('색상 카드로 이긴 트릭·승자가 트릭에 없는 경우는 0', () => {
    expect(captureBonus([pc(0, su('GREEN', 3)), pc(1, su('GREEN', 9))], 1)).toBe(0);
    expect(captureBonus([pc(0, sp('SKULL_KING')), pc(1, sp('PIRATE'))], 5)).toBe(0);
  });
});

describe('촛불 선장실 — 카드·트릭 연출 조각 (D-231)', () => {
  it('테마가 켜져 있다 — 되돌리기는 skullKingTheme.ts 한 줄', () => {
    expect(cabinTheme).toBe(true);
  });

  it('특수 카드는 직접 그린 SVG 그림과 종류 클래스·봉랍 글자를 갖는다(이름 글자는 그대로)', () => {
    render(<SkullCardChip card={sp('SKULL_KING')} />);
    const chip = screen.getByLabelText('스컬킹');
    expect(chip).toHaveClass('sk-card-special', 'sk-sp-skull-king');
    expect(chip).toHaveAttribute('data-mark', '왕');
    expect(chip.querySelector('.sk-card-art svg')).not.toBeNull();
    expect(chip.querySelector('.sk-card-art')).toHaveAttribute('aria-hidden');
    expect(chip).toHaveTextContent('스컬킹');
  });

  it('무늬 카드는 숫자 + 상징 그림 — 상징은 꾸밈이라 읽지 않는다', () => {
    render(<SkullCardChip card={su('BLACK', 7)} />);
    const chip = screen.getByLabelText('검정 7');
    expect(chip).toHaveClass('sk-suit-black');
    expect(chip).toHaveTextContent('7');
    expect(chip.querySelector('.sk-card-emblem svg')).not.toBeNull();
    expect(chip).not.toHaveAttribute('data-mark');
  });

  const row = (settledWinner: number | null, cards: PlayedCardView[]) =>
    render(
      <SkullTrickRow
        trick={settledWinner === null ? cards : []}
        settled={settledWinner === null ? null : { cards, winnerSeat: settledWinner, winningCard: cards[0].card }}
        currentTurnSeat={0}
        handSize={3}
        completedTricks={0}
        nameOf={(seat) => `p${seat}`}
        fromOf={(seat) => (seat === 0 ? 'bottom' : 'left')}
      />,
    );

  it('낸 카드 칸은 낸 사람이 앉은 쪽(data-from)을 안다 — 그쪽에서 날아온다', () => {
    const { container } = row(null, [pc(1, su('GREEN', 3)), pc(0, su('GREEN', 5))]);
    const slots = container.querySelectorAll('.sk-trick-slot:not(.waiting)');
    expect([...slots].map((el) => el.getAttribute('data-from'))).toEqual(['left', 'bottom']);
    expect(container.querySelector('.sk-trick-settled')).toBeNull();
  });

  it('끝난 트릭 — 다른 카드는 승자 칸 쪽으로 끌리고(--sk-pull), 스컬킹이 해적을 잡으면 보너스가 뜬다', () => {
    const { container } = row(2, [pc(0, sp('PIRATE')), pc(1, su('BLACK', 2)), pc(2, sp('SKULL_KING'))]);
    expect(container.querySelector('.sk-trick-settled')).not.toBeNull();
    const slots = [...container.querySelectorAll<HTMLElement>('.sk-trick-slot')];
    expect(slots.map((el) => el.style.getPropertyValue('--sk-pull'))).toEqual(['2', '1', '0']);
    expect(slots[2]).toHaveClass('won');
    expect(slots[2].querySelector('.sk-fx-bonus')).toHaveTextContent('+30');
  });

  it('포획이 없는 트릭에는 보너스 조각이 없다', () => {
    const { container } = row(1, [pc(0, su('GREEN', 3)), pc(1, su('GREEN', 9))]);
    expect(container.querySelector('.sk-fx-bonus')).toBeNull();
  });
});

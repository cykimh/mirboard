import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { TierTableButton } from './TierTableButton';

/** D-266 — [계급표] 버튼 → 16단계 표 창(높은 것부터), 내 계급 강조. */
describe('TierTableButton (D-266)', () => {
  it('누르면 16단계를 높은 것부터 보이고 필요 점수·순위를 싣는다', () => {
    const onOpen = vi.fn();
    render(<TierTableButton myTier="BRONZE_STAR" basis="티츄 기준" onOpen={onOpen} />);
    expect(screen.queryByRole('dialog')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: '계급표' }));

    expect(onOpen).toHaveBeenCalledTimes(1);
    const dialog = screen.getByRole('dialog', { name: '계급표' });
    const rows = within(dialog).getAllByRole('listitem');
    expect(rows).toHaveLength(16);
    expect(rows[0].getAttribute('data-tier')).toBe('STAR_CROWN');
    expect(rows[15].getAttribute('data-tier')).toBe('SKULL');
    expect(rows[0]).toHaveTextContent('별왕관');
    expect(rows[0]).toHaveTextContent('금훈장 + 게임 순위 1위');
    expect(rows[3]).toHaveTextContent('금훈장 + 게임 순위 11~30위');
    expect(rows[4]).toHaveTextContent('금훈장');
    expect(rows[4]).toHaveTextContent('1400점 이상');
    expect(rows[13]).toHaveTextContent('미사일');
    expect(rows[13]).toHaveTextContent('1000점 이상');
    expect(rows[15]).toHaveTextContent('940점 미만');
    expect(dialog).toHaveTextContent('처음 1000점(미사일)에서 시작해 이기면 오르고 지면 내려가요');
    expect(dialog).toHaveTextContent('봇이나 게스트가 낀 판은 점수가 바뀌지 않아요');
    expect(dialog).toHaveTextContent('티츄 기준');
  });

  it('내 계급 줄만 강조한다', () => {
    render(<TierTableButton myTier="BRONZE_STAR" />);
    fireEvent.click(screen.getByRole('button', { name: '계급표' }));

    const dialog = screen.getByRole('dialog');
    const current = within(dialog)
      .getAllByRole('listitem')
      .filter((li) => li.getAttribute('aria-current') === 'true');
    expect(current).toHaveLength(1);
    expect(current[0].getAttribute('data-tier')).toBe('BRONZE_STAR');
    expect(current[0]).toHaveTextContent('내 계급');
    expect(current[0]).toHaveTextContent('1040점 이상');
  });

  it('내 계급을 모르면(게스트) 강조하지 않는다', () => {
    render(<TierTableButton myTier={null} />);
    fireEvent.click(screen.getByRole('button', { name: '계급표' }));

    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getAllByRole('listitem').some((li) => li.hasAttribute('aria-current'))).toBe(false);
    expect(within(dialog).queryByText('내 계급')).toBeNull();
  });
});

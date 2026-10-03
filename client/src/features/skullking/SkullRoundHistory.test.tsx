import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { SkullRoundHistoryTable } from './SkullRoundHistoryTable';
import { SkullRoundHistoryModal } from './SkullRoundHistoryModal';
import type { CompletedRoundView, RoundScoreView } from '@/types/skullking';

/**
 * D-120 — 스컬킹 라운드×좌석 점수표. 모달(헤더 '점수표')과 매치 종료 패널이 같은 표를 쓴다.
 *
 * <p>합계 행은 표가 더하지 않고 **호출자가 넘긴 권위값**(누적 점수/최종 점수)을 쓴다. 배포
 * 시점에 진행 중이던 매치는 앞선 라운드 기록이 비므로 행의 합과 다를 수 있고, 그때는 숫자를
 * 틀리게 보이는 대신 "일부 라운드 기록 없음"을 알린다.
 */

const score = (bid: number, won: number, base: number, bonus = 0): RoundScoreView => ({
  bid,
  won,
  base,
  bonus,
  total: base + bonus,
});

const ROUNDS: CompletedRoundView[] = [
  { roundNumber: 1, scores: { 0: score(1, 1, 20, 10), 1: score(0, 1, -10) } },
  { roundNumber: 2, scores: { 0: score(2, 1, -10), 1: score(0, 0, 20) } },
];

const nameOf = (seat: number) => `p${seat}`;

function renderTable(
  over: Partial<Parameters<typeof SkullRoundHistoryTable>[0]> = {},
) {
  return render(
    <SkullRoundHistoryTable
      rounds={ROUNDS}
      seats={[0, 1]}
      totals={{ 0: 20, 1: 10 }}
      mySeat={0}
      nameOf={nameOf}
      {...over}
    />,
  );
}

const bodyRows = (root: HTMLElement) => [
  ...root.querySelectorAll<HTMLElement>('tbody tr'),
];

describe('SkullRoundHistoryTable', () => {
  it.each([2, 8])('%i인이면 좌석 열이 %i개 (+ 라운드 열)', (n) => {
    const seats = Array.from({ length: n }, (_, i) => i);
    const { container } = renderTable({ seats, totals: {} });

    expect(container.querySelectorAll('thead th')).toHaveLength(n + 1);
  });

  it('끝난 라운드마다 한 행 + 합계 행', () => {
    const { container } = renderTable();

    const rows = bodyRows(container);
    expect(rows).toHaveLength(ROUNDS.length + 1);
    expect(rows[0].textContent).toContain('R1');
    expect(rows[1].textContent).toContain('R2');
  });

  it('셀은 라운드 점수(부호)·적중 기호·예측/획득을 보인다', () => {
    const { container } = renderTable();

    const [r1] = bodyRows(container);
    const cells = within(r1).getAllByRole('cell');
    expect(cells[0].textContent).toContain('+30');
    expect(cells[0].textContent).toContain('✓');
    expect(cells[0].textContent).toContain('1/1');
    expect(cells[0].className).toContain('sk-history-hit');
    expect(cells[1].textContent).toContain('-10');
    expect(cells[1].textContent).toContain('✗');
    expect(cells[1].textContent).toContain('0/1');
    expect(cells[1].className).toContain('sk-history-miss');
  });

  it('합계 행은 넘긴 권위 합계를 그대로 쓴다', () => {
    const { container } = renderTable({ totals: { 0: 20, 1: 10 } });

    const total = bodyRows(container).at(-1)!;
    expect(total.textContent).toContain('합계');
    const cells = within(total).getAllByRole('cell').map((c) => c.textContent);
    expect(cells).toEqual(['20', '10']);
    expect(screen.queryByText(/일부 라운드 기록/)).toBeNull();
  });

  it('행의 합과 합계가 다르면 기록 누락을 알린다 (배포 중 진행 매치)', () => {
    renderTable({ totals: { 0: 120, 1: 10 } });

    expect(screen.getByText(/일부 라운드 기록이 없/)).toBeInTheDocument();
  });

  it('내 열과 탈주 좌석을 표시한다', () => {
    const { container } = renderTable({ desertedSeats: [1] });

    const heads = [...container.querySelectorAll('thead th')];
    expect(heads[1].textContent).toContain('p0');
    expect(heads[1].textContent).toContain('(나)');
    expect(heads[2].textContent).toContain('탈주');
  });
});

describe('SkullRoundHistoryModal', () => {
  it('열리면 dialog 안에 표를 보인다', () => {
    render(
      <SkullRoundHistoryModal
        open
        onOpenChange={() => {}}
        rounds={ROUNDS}
        seats={[0, 1]}
        totals={{ 0: 20, 1: 10 }}
        mySeat={0}
        nameOf={nameOf}
      />,
    );

    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByRole('table')).toBeInTheDocument();
    expect(dialog.className).toContain('sk-history-modal');
  });

  it('끝난 라운드가 없으면 표 대신 안내', () => {
    render(
      <SkullRoundHistoryModal
        open
        onOpenChange={() => {}}
        rounds={[]}
        seats={[0, 1]}
        totals={{}}
        mySeat={-1}
        nameOf={nameOf}
      />,
    );

    expect(document.querySelector('table')).toBeNull();
    expect(document.body.textContent).toContain('아직 끝난 라운드가 없습니다');
  });
});

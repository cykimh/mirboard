import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';
import { RoundHistoryTable, type RoundHistoryRow } from './RoundHistoryTable';

/**
 * D-108 — 공용 표 추출의 안전망.
 *
 * 첫 테스트가 핵심이다: `MatchEndedPanel` 이 쓰던 **옛 마크업을 그대로 재현**해 두고
 * 새 컴포넌트의 렌더 결과와 바이트 비교한다. 추출이 시각 변경을 끌고 오지 않았다는 걸
 * 말이 아니라 DOM 으로 고정한다(D-87 에서 쓴 방식).
 */

const history = [
  { teamAScore: 300, teamBScore: 0, firstFinisherSeat: 0, doubleVictory: true },
  { teamAScore: 40, teamBScore: 60, firstFinisherSeat: 2, doubleVictory: false },
];

/** 추출 이전 `MatchEndedPanel` 의 표 JSX 를 한 글자도 바꾸지 않고 옮겨 온 것. */
function LegacyTable() {
  return (
    <table className="score-history">
      <thead>
        <tr>
          <th>R</th>
          <th>Team A</th>
          <th>Team B</th>
          <th />
        </tr>
      </thead>
      <tbody>
        {history.map((r, i) => (
          <tr key={i}>
            <td>{i + 1}</td>
            <td>{r.teamAScore}</td>
            <td>{r.teamBScore}</td>
            <td>{r.doubleVictory ? '더블 승' : ''}</td>
          </tr>
        ))}
        <tr className="score-history-total">
          <td>합계</td>
          <td>{340}</td>
          <td>{60}</td>
          <td />
        </tr>
      </tbody>
    </table>
  );
}

const panelRows: RoundHistoryRow[] = history.map((r, i) => ({
  round: i + 1,
  left: r.teamAScore,
  right: r.teamBScore,
  doubleVictory: r.doubleVictory,
}));

describe('RoundHistoryTable — MatchEndedPanel 무변경', () => {
  it('첫 완주자 배지가 없으면 옛 마크업과 DOM 이 완전히 같다', () => {
    const legacy = render(<LegacyTable />).container.innerHTML;
    const extracted = render(
      <RoundHistoryTable
        rows={panelRows}
        labels={{ left: 'Team A', right: 'Team B' }}
        totals={{ left: 340, right: 60 }}
      />,
    ).container.innerHTML;

    expect(extracted).toBe(legacy);
  });
});

describe('RoundHistoryTable — 관점 주입', () => {
  it('열 라벨과 좌/우 값을 호출자가 정한다 (우리/상대 관점)', () => {
    const { getByText, container } = render(
      <RoundHistoryTable
        rows={[{ round: 1, left: 60, right: 40, doubleVictory: false }]}
        labels={{ left: '우리', right: '상대' }}
        totals={{ left: 60, right: 40 }}
      />,
    );

    const headers = [...container.querySelectorAll('th')].map((th) => th.textContent);
    expect(headers).toEqual(['R', '우리', '상대', '']);
    expect(getByText('합계')).toBeTruthy();
  });

  it('첫 완주자 이름이 있으면 배지를 붙인다', () => {
    const { container } = render(
      <RoundHistoryTable
        rows={[{ round: 1, left: 100, right: 0, doubleVictory: false, finisherName: 'cykimh' }]}
        labels={{ left: '우리', right: '상대' }}
        totals={{ left: 100, right: 0 }}
      />,
    );

    const badge = container.querySelector('.score-history-finisher');
    expect(badge?.textContent).toContain('cykimh');
    expect(badge?.getAttribute('title')).toBe('첫 완주: cykimh');
  });

  it('더블 승과 첫 완주자가 함께면 구분자로 이어 붙인다', () => {
    const { container } = render(
      <RoundHistoryTable
        rows={[{ round: 1, left: 200, right: 0, doubleVictory: true, finisherName: 'cykimh' }]}
        labels={{ left: '우리', right: '상대' }}
        totals={{ left: 200, right: 0 }}
      />,
    );

    const cell = container.querySelectorAll('tbody tr td')[3];
    expect(cell.textContent).toBe('더블 승 · 🏁 cykimh');
  });
});

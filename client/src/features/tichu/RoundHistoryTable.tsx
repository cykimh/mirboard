/**
 * 라운드별 점수표 (D-108). `MatchEndedPanel`(매치 종료 패널)과
 * `RoundHistoryModal`(게임판 점수 칩)이 공유한다.
 *
 * **표기를 모른다.** 두 호출자의 관점이 다르기 때문이다 — 패널은 `Team A`/`Team B`,
 * 모달은 내 팀 기준 `우리`/`상대`. 그래서 열 라벨과 좌/우 값을 호출자가 정해 넘긴다.
 * 여기서 팀을 해석하기 시작하면 두 관점이 이 파일 안에서 다시 갈라진다.
 */

export interface RoundHistoryRow {
  /** 1부터. 표시용 라운드 번호. */
  round: number;
  left: number;
  right: number;
  doubleVictory: boolean;
  /** 첫 완주자 표시명. 없으면 배지를 렌더하지 않는다. */
  finisherName?: string;
}

interface RoundHistoryTableProps {
  rows: RoundHistoryRow[];
  labels: { left: string; right: string };
  totals: { left: number; right: number };
}

export function RoundHistoryTable({ rows, labels, totals }: RoundHistoryTableProps) {
  return (
    <table className="score-history">
      <thead>
        <tr>
          <th>R</th>
          <th>{labels.left}</th>
          <th>{labels.right}</th>
          <th />
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.round}>
            <td>{r.round}</td>
            <td>{r.left}</td>
            <td>{r.right}</td>
            <td>
              {r.doubleVictory ? '더블 승' : ''}
              {r.finisherName ? (
                <span className="score-history-finisher" title={`첫 완주: ${r.finisherName}`}>
                  {r.doubleVictory ? ' · ' : ''}🏁 {r.finisherName}
                </span>
              ) : null}
            </td>
          </tr>
        ))}
        <tr className="score-history-total">
          <td>합계</td>
          <td>{totals.left}</td>
          <td>{totals.right}</td>
          <td />
        </tr>
      </tbody>
    </table>
  );
}

import type { CompletedRoundView } from '@/types/skullking';

interface Props {
  /** 끝난 라운드, 라운드 번호 순. 행 하나 = 라운드 하나. */
  rounds: CompletedRoundView[];
  /** 열로 그릴 좌석들 (보통 0..seatCount-1). */
  seats: number[];
  /**
   * 합계 행에 쓸 **권위 합계** — 진행 중이면 누적 점수, 매치 종료면 최종 점수. 표가 행을
   * 더해 만들지 않는 것은, 배포 시점에 진행 중이던 매치처럼 앞선 기록이 빈 경우에도 숫자가
   * 틀리지 않게 하려는 것이다(그때는 아래 안내 문구가 대신 어긋남을 알린다).
   */
  totals: Record<number, number>;
  mySeat: number;
  nameOf: (seat: number) => string;
  desertedSeats?: number[];
}

const signed = (n: number) => (n > 0 ? `+${n}` : `${n}`);

/**
 * 스컬킹 라운드×좌석 점수표 (D-120). 헤더 '점수표' 모달과 매치 종료 패널이 공유한다.
 *
 * 셀은 그 라운드의 점수(부호)와 적중 기호(✓/✗), 아래 작은 글씨로 `예측/획득`. 적중·실패
 * 색은 이 표가 정하지 않고 **컨테이너가 변수(`--sk-hit`/`--sk-miss`)로 주입**한다 — 같은 표가
 * 다크 게임판과 라이트/다크 모달에 모두 놓이기 때문이다.
 */
export function SkullRoundHistoryTable({
  rounds,
  seats,
  totals,
  mySeat,
  nameOf,
  desertedSeats = [],
}: Props) {
  const rowSum = (seat: number) =>
    rounds.reduce((sum, r) => sum + (r.scores[seat]?.total ?? 0), 0);
  const incomplete = seats.some((seat) => rowSum(seat) !== (totals[seat] ?? 0));

  return (
    <>
      <div className="sk-history-scroll">
        <table className="sk-history-table">
          <thead>
            <tr>
              <th scope="col">R</th>
              {seats.map((seat) => (
                <th
                  key={seat}
                  scope="col"
                  className={seat === mySeat ? 'sk-history-me' : undefined}
                >
                  <span className="sk-history-name" title={nameOf(seat)}>
                    {nameOf(seat)}
                  </span>
                  {seat === mySeat && <span className="sk-history-tag">(나)</span>}
                  {desertedSeats.includes(seat) && (
                    <span className="sk-history-tag sk-history-deserted">탈주</span>
                  )}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rounds.map((r) => (
              <tr key={r.roundNumber}>
                <th scope="row">R{r.roundNumber}</th>
                {seats.map((seat) => {
                  const s = r.scores[seat];
                  if (!s) {
                    return (
                      <td key={seat} className="sk-history-cell">
                        —
                      </td>
                    );
                  }
                  const hit = s.bid === s.won;
                  return (
                    <td
                      key={seat}
                      className={`sk-history-cell ${hit ? 'sk-history-hit' : 'sk-history-miss'}${
                        seat === mySeat ? ' sk-history-me' : ''
                      }`}
                      title={hit ? '적중' : '실패'}
                    >
                      <span className="sk-history-score">
                        {signed(s.total)} {hit ? '✓' : '✗'}
                      </span>
                      <span className="sk-history-bw" title="예측/획득">
                        {s.bid}/{s.won}
                      </span>
                    </td>
                  );
                })}
              </tr>
            ))}
            <tr className="sk-history-total">
              <th scope="row">합계</th>
              {seats.map((seat) => (
                <td
                  key={seat}
                  className={seat === mySeat ? 'sk-history-me' : undefined}
                >
                  {totals[seat] ?? 0}
                </td>
              ))}
            </tr>
          </tbody>
        </table>
      </div>
      {incomplete && (
        <p className="sk-history-note">
          일부 라운드 기록이 없어 라운드 합과 합계가 다를 수 있습니다. 합계는 서버 누적
          점수입니다.
        </p>
      )}
    </>
  );
}

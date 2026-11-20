import type { DambadukMatchResult } from '@/types/dambaduk';
import { COLOR_KEY, COLOR_NAME } from './dambadukTheme';

export const END_REASON_TEXT: Record<DambadukMatchResult['reason'], string> = {
  SEPARATED: '모두 담으로 갈라졌어요',
  FROZEN: '섞인 땅이 더는 바뀔 수 없어요',
  LAST_STANDING: '한 사람만 남았어요',
  TIMEOUT: '시간 초과로 끝났어요',
  DESERTED: '나가서 끝났어요',
};

const EXIT_TEXT = { TIMEOUT: '시간 초과로 빠짐', DESERTED: '나감' } as const;

interface Props {
  result: DambadukMatchResult;
  seatColors: number[];
  mySeat: number;
  nameOf: (seat: number) => string;
  onExit?: () => void;
  onBackToRoom?: () => void;
}

/**
 * 판 결과 (룰 §6·§7) — 순위표(집·가장 큰 집·빠짐). 판은 그대로 보이고(집 번짐) 이 현판이 아래에 붙는다. 순위·승패는 서버가 정한 값이다.
 */
export function DambadukMatchEnd({ result, seatColors, mySeat, nameOf, onExit, onBackToRoom }: Props) {
  const iWon = mySeat >= 0 && result.winners.includes(mySeat);
  const iLost = mySeat >= 0 && !result.draw && !iWon;
  const headline = result.draw
    ? '무승부'
    : iWon
      ? result.winners.length > 1
        ? '공동 1위!'
        : '이겼어요!'
      : iLost
        ? '졌어요'
        : `${result.winners.map(nameOf).join(' · ')} 승리`;
  const rows = [...result.standings].sort((a, b) => a.rank - b.rank || a.seat - b.seat);

  return (
    <section
      className={`db-end${iWon ? ' db-end-won' : ''}${iLost ? ' db-end-lost' : ''}${result.draw ? ' db-end-draw' : ''}`}
      aria-label="판 결과"
      role="status"
    >
      <p className="db-end-title">{headline}</p>
      <p className="db-end-line db-end-muted">{END_REASON_TEXT[result.reason]}</p>
      <table className="db-end-table">
        <thead>
          <tr>
            <th scope="col">순위</th>
            <th scope="col">이름</th>
            <th scope="col">집</th>
            <th scope="col">가장 큰 집</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((st) => (
            <tr
              key={st.seat}
              className={`${st.seat === mySeat ? 'db-end-me' : ''}${
                result.winners.includes(st.seat) ? ' db-end-winner' : ''
              }${st.exit ? ' db-end-out' : ''}`.trim() || undefined}
            >
              <td>{st.rank}</td>
              <td>
                <span className={`db-dot db-c-${COLOR_KEY[seatColors[st.seat] ?? 0]}`} aria-hidden="true" />
                <span className="db-sr">{COLOR_NAME[seatColors[st.seat] ?? 0]} </span>
                {nameOf(st.seat)}
                {st.seat === mySeat ? ' (나)' : ''}
                {st.exit && <span className="db-end-exit"> · {EXIT_TEXT[st.exit]}</span>}
              </td>
              <td>{st.score}칸</td>
              <td>{st.largest}칸</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="db-end-actions">
        {onBackToRoom && (
          <button type="button" className="db-primary" onClick={onBackToRoom}>
            대기실로
          </button>
        )}
        {!onBackToRoom && onExit && (
          <button type="button" className="db-primary" onClick={onExit}>
            나가기
          </button>
        )}
      </div>
    </section>
  );
}

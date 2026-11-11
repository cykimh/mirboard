import { BONUS_THRESHOLD, CATEGORIES, UPPER_CATEGORIES, type Category, type YachtSeatView } from '@/types/yacht';
import { CATEGORY_HINT, CATEGORY_LABEL, UPPER_FACE } from './yachtLabels';
import { YachtDie } from './YachtDie';
import type { LastScore } from './yachtStore';

interface Props {
  seats: YachtSeatView[];
  turnSeat: number;
  mySeat: number;
  /** 차례인 좌석의 빈 칸 → 지금 주사위 점수(서버 계산). */
  candidates: Partial<Record<Category, number>>;
  nameOf: (seat: number) => string;
  /** 내 차례이고 1번 이상 굴렸으면 빈 칸을 눌러 기록한다. */
  onScore?: (category: Category) => void;
  lastScore: LastScore | null;
  /** D-204 — 방금 상단 보너스가 붙은 좌석(잠깐). 보너스 칸에 도장이 찍힌다. */
  bonusSeat?: number | null;
}

/**
 * 점수표 — 칸 × 좌석 열(최대 4열 + 칸 이름, 375px 에서 가로 스크롤 없음). 차례인 좌석 열의 빈 칸에 후보 점수가 흐리게 뜬다
 * (구경꾼도 "어디 넣을까"를 같이 본다). 내 차례면 그 칸이 버튼이다. D-203 — 내 열은 `yt-cell-me`(옅은 황동 물),
 * 적힌 칸은 `data-yt-cell`(황동 칩이 날아와 꽂힐 자리).
 */
export function YachtScorecard({ seats, turnSeat, mySeat, candidates, nameOf, onScore, lastScore, bonusSeat = null }: Props) {
  const cell = (seat: YachtSeatView, c: Category) => {
    const written = seat.scores[c];
    const isTurn = seat.seat === turnSeat;
    const flash = lastScore && lastScore.seat === seat.seat && lastScore.category === c;
    const me = seat.seat === mySeat ? ' yt-cell-me' : '';
    if (written !== undefined) {
      return (
        <td
          key={seat.seat}
          className={`yt-cell yt-cell-written${me}${flash ? ' yt-cell-flash' : ''}`}
          data-yt-cell={`${seat.seat}:${c}`}
        >
          <span className="yt-num">{written}</span>
        </td>
      );
    }
    const candidate = isTurn ? candidates[c] : undefined;
    if (candidate !== undefined && onScore && seat.seat === mySeat) {
      return (
        <td key={seat.seat} className={`yt-cell yt-cell-pick${me}`}>
          <button
            type="button"
            className={`yt-pick${candidate === 0 ? ' yt-pick-zero' : ''}`}
            onClick={() => onScore(c)}
            aria-label={`${CATEGORY_LABEL[c]}에 ${candidate}점 기록`}
          >
            {candidate}
          </button>
        </td>
      );
    }
    return (
      <td key={seat.seat} className={`yt-cell${me}${candidate !== undefined ? ' yt-cell-candidate' : ''}`}>
        {candidate !== undefined ? candidate : ''}
      </td>
    );
  };

  const row = (c: Category) => (
    <tr key={c} className={c === 'SIXES' ? 'yt-row-upper-end' : undefined}>
      <th scope="row" className="yt-cat" title={CATEGORY_HINT[c]}>
        {UPPER_FACE[c] !== undefined && (
          <span className="yt-cat-die" aria-hidden="true">
            <YachtDie face={UPPER_FACE[c] ?? null} mini />
          </span>
        )}
        {CATEGORY_LABEL[c]}
      </th>
      {seats.map((s) => cell(s, c))}
    </tr>
  );

  return (
    <div className="yt-card-wrap">
      <table className="yt-card" aria-label="점수표">
        <thead>
          <tr>
            <th scope="col" className="yt-cat">
              칸
            </th>
            {seats.map((s) => (
              <th
                scope="col"
                key={s.seat}
                className={`yt-col${s.seat === mySeat ? ' yt-cell-me' : ''}${s.seat === turnSeat ? ' yt-col-turn' : ''}${s.deserted ? ' yt-col-out' : ''}`}
              >
                {s.seat === mySeat ? '나' : nameOf(s.seat)}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {UPPER_CATEGORIES.map(row)}
          <tr className="yt-row-sum">
            <th scope="row" className="yt-cat">
              상단 소계
            </th>
            {seats.map((s) => (
              <td key={s.seat} className={`yt-cell${s.seat === mySeat ? ' yt-cell-me' : ''}`}>
                {s.upperSubtotal}/{BONUS_THRESHOLD}
              </td>
            ))}
          </tr>
          <tr className="yt-row-sum">
            <th scope="row" className="yt-cat" title="상단 소계 63 이상이면 +35">
              보너스
            </th>
            {seats.map((s) => (
              <td
                key={s.seat}
                className={`yt-cell${s.seat === mySeat ? ' yt-cell-me' : ''}${s.bonus > 0 ? ' yt-cell-bonus' : ''}${bonusSeat === s.seat && s.bonus > 0 ? ' yt-cell-bonus-new' : ''}`}
                data-yt-bonus={s.seat}
              >
                {s.bonus > 0 ? <span className="yt-num">+{s.bonus}</span> : '-'}
              </td>
            ))}
          </tr>
          {CATEGORIES.filter((c) => !UPPER_CATEGORIES.includes(c)).map(row)}
          <tr className="yt-row-total">
            <th scope="row" className="yt-cat">
              총점
            </th>
            {seats.map((s) => (
              <td key={s.seat} className={`yt-cell${s.seat === mySeat ? ' yt-cell-me' : ''}`}>
                {s.total}
              </td>
            ))}
          </tr>
        </tbody>
      </table>
    </div>
  );
}

import { SkullCardChip } from './SkullCardChip';
import { leadSuitOf, seatAccent } from './seatLayout';
import { SUIT_LABEL, type PlayedCardView } from '@/types/skullking';
import type { SettledTrick } from './skullkingStore';
import { captureBonus } from './skullFx';
import { cabinTheme } from './skullKingTheme';

interface Props {
  trick: PlayedCardView[];
  settled: SettledTrick | null;
  currentTurnSeat: number;
  handSize: number;
  /** 이 라운드에 지금까지 끝난 트릭 수 (메타 줄 표시용). */
  completedTricks: number;
  nameOf: (seat: number) => string;
  /** D-231 — 그 좌석이 화면 어느 쪽에 앉았는지(left·top·right·bottom). 낸 카드가 그쪽에서 날아온다(연출 전용). */
  fromOf?: (seat: number) => string | undefined;
}

/**
 * 트릭을 **재생순 레일**로 늘어놓는다 (D-103 Row-Flow).
 *
 * <p>원형 배치가 아니라 이 형태를 쓰는 이유: 스컬킹은 개인전이라 보존할 방위축이 없고,
 * 보존해야 하는 정보는 **진행 순서**다 — 리드 수트가 첫 색상 카드로 지연 확정되고(§6.1)
 * 비추이적 순환(§7)이 있어 "누가 먼저 냈나"가 곧 판정 근거이기 때문이다. 8인 45° 링에서는
 * 카드가 두 좌석 사이로 보여 "카드 위치 ↔ 낸 사람" 대응 자체가 모호해진다.
 *
 * <p>"누가 냈는지"는 좌표가 아니라 **이름칩 + 좌석 accent + 순번 배지** 3중 단서로 잇는다.
 */
export function SkullTrickRow({
  trick,
  settled,
  currentTurnSeat,
  handSize,
  completedTricks,
  nameOf,
  fromOf,
}: Props) {
  // 정산 직후에는 방금 끝난 트릭을 그대로 보여준다 (승자 왕관 포함).
  const shown = trick.length > 0 ? trick : (settled?.cards ?? []);
  const winnerSeat = trick.length > 0 ? null : (settled?.winnerSeat ?? null);
  const leadSuit = leadSuitOf(shown);
  // D-231 — 끝난 트릭: 다른 카드들이 승자 카드 쪽으로 끌려가고(--sk-pull = 승자 칸까지의 칸 수), 포획 보너스가 튀어 오른다.
  const winnerIndex = winnerSeat === null ? -1 : shown.findIndex((pc) => pc.seat === winnerSeat);
  const bonus = cabinTheme && winnerSeat !== null ? captureBonus(shown, winnerSeat) : 0;

  return (
    <section className="sk-trick-wrap" aria-label="이번 트릭">
      <div className="sk-trick-meta">
        <span className="sk-trick-count">
          트릭 {Math.min(completedTricks + (trick.length > 0 ? 1 : 0), handSize)} / {handSize}
        </span>
        <span className="sk-trick-lead">
          리드 수트{' '}
          <strong>{leadSuit ? SUIT_LABEL[leadSuit] : '없음'}</strong>
        </span>
      </div>

      <ol className={`sk-trick${winnerIndex >= 0 ? ' sk-trick-settled' : ''}`}>
        {shown.map((pc, i) => {
          const isWinner = winnerSeat !== null && pc.seat === winnerSeat;
          return (
            <li
              key={`${pc.seat}-${i}`}
              className={`sk-trick-slot${isWinner ? ' won' : ''}`}
              style={{
                ['--sk-accent' as string]: seatAccent(pc.seat),
                ...(winnerIndex >= 0 ? { ['--sk-pull' as string]: winnerIndex - i } : {}),
              }}
              data-from={fromOf?.(pc.seat)}
            >
              <span className="sk-trick-order" aria-hidden>
                {i + 1}
              </span>
              <SkullCardChip card={pc.card} declaredAs={pc.declaredAs} compact />
              {isWinner && bonus > 0 && (
                <span className="sk-fx-bonus" title="포획 보너스 — 예측을 맞혀야 받는다">
                  +{bonus}
                </span>
              )}
              <span className="sk-trick-who">
                {isWinner && <span aria-label="트릭 획득">👑</span>} {nameOf(pc.seat)}
                {i === 0 && <span className="sk-trick-tag">리드</span>}
              </span>
            </li>
          );
        })}

        {currentTurnSeat >= 0 && trick.length > 0 && (
          <li
            className="sk-trick-slot waiting"
            style={{ ['--sk-accent' as string]: seatAccent(currentTurnSeat) }}
          >
            <span className="sk-trick-order" aria-hidden>
              {shown.length + 1}
            </span>
            <span className="sk-trick-placeholder" aria-hidden />
            <span className="sk-trick-who">{nameOf(currentTurnSeat)} 대기</span>
          </li>
        )}

        {shown.length === 0 && currentTurnSeat >= 0 && (
          <li className="sk-trick-empty">
            {nameOf(currentTurnSeat)} 이(가) 리드합니다
          </li>
        )}
      </ol>
    </section>
  );
}

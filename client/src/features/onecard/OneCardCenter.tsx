import { SUIT_LABEL, SUIT_SYMBOL, type OneCardCard, type OneCardSuit } from '@/types/onecard';
import type { LastRace } from './onecardStore';
import { OneCardCardChip } from './OneCardCardChip';

/** 방금 닫힌 경쟁의 한 줄 안내 — 화면과 스크린리더 알림이 같은 문장을 쓴다. */
export function raceResultText(race: LastRace, nameOf: (seat: number) => string): string {
  switch (race.outcome) {
    case 'CALLED':
      return `원카드! ${nameOf(race.bySeat)} 안전`;
    case 'CAUGHT':
      return race.ownerSeat >= 0
        ? `잡기 성공: ${nameOf(race.bySeat)} → ${nameOf(race.ownerSeat)} 벌칙 1장`
        : `잡기 성공: ${nameOf(race.bySeat)}`;
    case 'EXPIRED':
      return '시간 초과 — 벌칙 없음';
    case 'CANCELLED':
      return '탈주로 경쟁이 취소됐습니다';
  }
}

interface Props {
  topCard: OneCardCard | null;
  declaredSuit: OneCardSuit | null;
  attackStack: number;
  direction: number;
  drawPileCount: number;
  /** 지금 차례인 사람 이름, 없으면 null. */
  turnName: string | null;
  raceOpen: boolean;
  lastRace: LastRace | null;
  late: boolean;
  nameOf: (seat: number) => string;
}

/** 가운데 — 뽑을 더미, 맨 위 카드와 지정 무늬, 공격 누적, 진행 방향, 차례, 경쟁 결과. 모두 공개 정보다. */
export function OneCardCenter({
  topCard,
  declaredSuit,
  attackStack,
  direction,
  drawPileCount,
  turnName,
  raceOpen,
  lastRace,
  late,
  nameOf,
}: Props) {
  return (
    <section className="oc-center" aria-label="테이블">
      <div className="oc-piles">
        <div className="oc-pile" title="뽑을 더미">
          <span className="oc-pile-back" aria-hidden />
          <span className="oc-pile-count">뽑을 더미 {drawPileCount}장</span>
        </div>
        <div className="oc-top">
          {topCard ? (
            <OneCardCardChip card={topCard} />
          ) : (
            <span className="oc-top-empty">—</span>
          )}
          {declaredSuit && (
            <span className="oc-badge oc-declared">
              지정 {SUIT_SYMBOL[declaredSuit]} {SUIT_LABEL[declaredSuit]}
            </span>
          )}
        </div>
      </div>

      <div className="oc-center-info">
        {attackStack > 0 && <span className="oc-badge oc-attack">공격 +{attackStack}</span>}
        <span className="oc-badge">{direction >= 0 ? '진행 → 정방향' : '진행 ← 역방향'}</span>
        {raceOpen ? (
          <span className="oc-badge oc-race-status">원카드 경쟁 중</span>
        ) : (
          turnName && <span className="oc-badge">{turnName} 차례</span>
        )}
      </div>

      {lastRace && !raceOpen && <p className="oc-race-result">{raceResultText(lastRace, nameOf)}</p>}
      {late && <p className="oc-race-late">늦었어요 — 이미 끝난 경쟁입니다</p>}
    </section>
  );
}

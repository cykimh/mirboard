import { SUIT_LABEL, SUIT_SYMBOL, cardKey, type OneCardCard, type OneCardSuit } from '@/types/onecard';
import type { LastRace } from './onecardStore';
import { OneCardCardChip } from './OneCardCardChip';

/** 방금 닫힌 경쟁의 한 줄 안내 — 화면과 스크린리더 알림이 같은 문장을 쓴다. */
export function raceResultText(race: LastRace, nameOf: (seat: number) => string): string {
  switch (race.outcome) {
    case 'CALLED':
      return `원카드! ${nameOf(race.bySeat)} 안전`;
    case 'CAUGHT':
      // D-144 — 잡은 사람만. 벌칙은 상대 손패 장수로 보인다.
      return `잡기 성공: ${nameOf(race.bySeat)}`;
    case 'EXPIRED':
      return '시간 초과';
    case 'CANCELLED':
      return '탈주로 경쟁이 취소됐습니다';
  }
}

interface Props {
  topCard: OneCardCard | null;
  declaredSuit: OneCardSuit | null;
  attackStack: number;
  drawPileCount: number;
  /** 지금 차례인 사람 이름, 없으면 null. */
  turnName: string | null;
  raceOpen: boolean;
  lastRace: LastRace | null;
  late: boolean;
  nameOf: (seat: number) => string;
  /**
   * D-139 후속 — 방금 낸 카드가 날아오는 중이면 내기 전 맨 위 카드(와 지정 무늬). 날아온 카드가 다 덮을 때까지 가운데는 이전 카드를
   * 그대로 보이고, 내려앉으면(null) 새 맨 위 카드로 바뀐다.
   */
  landingUnder?: { card: OneCardCard | null; declaredSuit: OneCardSuit | null } | null;
  /** D-144 — 맨 위 아래에 깔린 이전 카드(최근 것이 앞). */
  discardTrail?: OneCardCard[];
}

/**
 * 가운데 — 뽑을 더미, 맨 위 카드와 지정 무늬, 공격 누적, 차례, 경쟁 결과. 모두 공개 정보다. D-139 — 진행 방향과 남은 턴 시간은 경기장
 * 맨 위 상태 줄(`OneCardTable`)로 옮겼다.
 */
export function OneCardCenter({
  topCard,
  declaredSuit,
  attackStack,
  drawPileCount,
  turnName,
  raceOpen,
  lastRace,
  late,
  nameOf,
  landingUnder = null,
  discardTrail = [],
}: Props) {
  const shownTop = landingUnder ? landingUnder.card : topCard;
  const shownSuit = landingUnder ? landingUnder.declaredSuit : declaredSuit;
  // 날아오는 동안 맨 위 자리는 이전 카드(= 아래 카드의 첫 장)다 — 같은 카드를 두 번 깔지 않는다.
  const beneath = landingUnder ? discardTrail.slice(1) : discardTrail;
  return (
    <section className="oc-center" aria-label="테이블">
      <div className="oc-piles">
        <div className="oc-pile" title="뽑을 더미">
          <span className="oc-pile-back" aria-hidden />
          <span className="oc-pile-count">뽑을 더미 {drawPileCount}장</span>
        </div>
        <div className="oc-top">
          <div className="oc-top-stack">
            {beneath.length > 0 && (
              // 장식 — 보조기기에는 맨 위 카드만 읽힌다. 뒤에 깔리도록 먼 카드부터 그린다.
              <span className="oc-top-trail" aria-hidden="true">
                {beneath
                  .map((card, i) => (
                    <span key={cardKey(card)} className="oc-top-trail-card" data-depth={i + 1}>
                      <OneCardCardChip card={card} />
                    </span>
                  ))
                  .reverse()}
              </span>
            )}
            {shownTop ? (
              <OneCardCardChip card={shownTop} />
            ) : (
              <span className="oc-top-empty">—</span>
            )}
          </div>
          {shownSuit && (
            <span className="oc-badge oc-declared">
              지정 {SUIT_SYMBOL[shownSuit]} {SUIT_LABEL[shownSuit]}
            </span>
          )}
        </div>
      </div>

      <div className="oc-center-info">
        {attackStack > 0 && <span className="oc-badge oc-attack">공격 +{attackStack}</span>}
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

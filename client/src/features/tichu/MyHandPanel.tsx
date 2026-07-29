import { t } from '@/i18n/messages';
import { Button } from '@/components/ui/button';
import type { Card, PrivateHand, TichuDeclaration } from '@/types/tichu';
import { CardChip } from './CardChip';
import { SortableHand } from './SortableHand';
import { getSelectedKeys } from './gameTableSelection';
import type { PassSlot } from './tichuStore';

const PASS_SLOT_LABEL: Record<PassSlot, string> = {
  left: t('pass.slot.left'),
  partner: t('pass.slot.partner'),
  right: t('pass.slot.right'),
};

interface MyHandPanelProps {
  privateHand: PrivateHand | null;
  /** 실제로 그릴 손패 (패스 단계에서 배정된 카드는 제외된 상태). */
  handCards: Card[];
  selectedCardKeys: Set<string>;
  passSelection: Record<PassSlot, string | null>;
  pendingPassCardKey: string | null;
  passCardsBySlot: Record<PassSlot, Card | null>;
  selectedCards: Card[];
  /** 선택 조합명 hint (표시용 — 서버가 실제 검증). */
  selectedCombo: string;
  selectedPlayable: boolean;
  isInDealing: boolean;
  isInPassing: boolean;
  isInPlaying: boolean;
  iAmReady: boolean;
  iAmPassSubmitted: boolean;
  dealingCardCount: number;
  myDeclaration: TichuDeclaration;
  onCardClick: (c: Card) => void;
  onReorder: (fromKey: string, toKey: string) => void;
  onAssignPassSlot: (slot: PassSlot) => void;
  onClearPassSelection: () => void;
  onDeclareTichu: () => void;
  onDeclareGrandTichu: () => void;
  onReady: () => void;
}

/**
 * 내 손패(.my-hand) + 단계별 액션 바(.action-bar).
 *
 * 액션 바는 딜링(선언/준비)·패스(슬롯 배정)·플레이(조합 hint + 티츄 선언)에서만
 * 렌더한다. 플레이 단계의 내기/패스 버튼은 여기가 아니라 경기장 좌석 좌우에
 * 있다(A7) — 이 컴포넌트에 없는 게 정상이다.
 *
 * 관전자에게는 호출부가 아예 렌더하지 않는다.
 *
 * D-87 에서 GameTable 에서 분리. 마크업·클래스명·조건 모두 이동 전과 동일하다.
 */
export function MyHandPanel({
  privateHand,
  handCards,
  selectedCardKeys,
  passSelection,
  pendingPassCardKey,
  passCardsBySlot,
  selectedCards,
  selectedCombo,
  selectedPlayable,
  isInDealing,
  isInPassing,
  isInPlaying,
  iAmReady,
  iAmPassSubmitted,
  dealingCardCount,
  myDeclaration,
  onCardClick,
  onReorder,
  onAssignPassSlot,
  onClearPassSelection,
  onDeclareTichu,
  onDeclareGrandTichu,
  onReady,
}: MyHandPanelProps) {
  const showActionBar =
    isInDealing ||
    isInPassing ||
    // 플레이 단계의 내기/패스는 경기장 좌석 좌우로 옮겼다(#6). 액션 바는
    // 선택 조합 힌트·티츄 선언이 있을 때만 렌더(빈 바 방지).
    (isInPlaying &&
      (selectedCards.length > 0 ||
        (myDeclaration === 'NONE' && (privateHand?.cards.length ?? 0) === 14)));

  return (
    <>
      <div
        className={`my-hand${
          isInPassing && !iAmPassSubmitted && !pendingPassCardKey ? ' pick-emphasis' : ''
        }`}
      >
        {privateHand ? (
          <SortableHand
            cards={handCards}
            selectedKeys={getSelectedKeys(
              selectedCardKeys,
              passSelection,
              isInPassing,
              pendingPassCardKey,
            )}
            onCardClick={onCardClick}
            onReorder={onReorder}
            // 플레이 단계만 겹침 허용(공간 남으면 안 겹침, #1). 패스/딜링은 펼침(#2).
            overlap={isInPlaying}
          />
        ) : (
          <p>{t('hand.loading')}</p>
        )}
      </div>

      {showActionBar && (
        <div className={`action-bar${isInPassing ? ' passing' : ''}`}>
          {isInDealing && !iAmReady && (
            <>
              {dealingCardCount === 8 && myDeclaration === 'NONE' && (
                <Button type="button" size="sm" variant="secondary" onClick={onDeclareGrandTichu}>
                  {t('dealing.declareGrand')}
                </Button>
              )}
              {dealingCardCount === 14 && myDeclaration === 'NONE' && (
                <Button type="button" size="sm" variant="secondary" onClick={onDeclareTichu}>
                  {t('dealing.declareTichu')}
                </Button>
              )}
              <Button type="button" size="sm" onClick={onReady}>
                {myDeclaration === 'NONE'
                  ? t('dealing.skip.noDeclare')
                  : t('dealing.skip.declared')}
              </Button>
            </>
          )}
          {isInDealing && iAmReady && <p className="hint">{t('dealing.waiting')}</p>}

          {isInPassing && !iAmPassSubmitted && privateHand && (
            <div className="arena-pass">
              <p className="pass-hint">
                {pendingPassCardKey
                  ? '카드 선택됨 — 줄 사람(좌/파트너/우)을 누르세요'
                  : '먼저 손패에서 카드를 고른 뒤 줄 사람을 누르세요'}
              </p>
              <div className="pass-slots">
                {(['left', 'partner', 'right'] as PassSlot[]).map((slot) => {
                  const c = passCardsBySlot[slot];
                  // Phase 15(#2) — 배정된 줄 사람은 선택지(버튼) 없애고 정적
                  // 칩으로 고정. 잘못 골랐으면 "초기화" 로 다시.
                  if (c) {
                    return (
                      <div key={slot} className="pass-slot filled">
                        <div className="slot-label">{PASS_SLOT_LABEL[slot]}</div>
                        <CardChip card={c} />
                        <span className="slot-done">✓</span>
                      </div>
                    );
                  }
                  return (
                    <button
                      type="button"
                      key={slot}
                      className={`pass-slot ${pendingPassCardKey ? 'droppable' : ''}`}
                      onClick={() => onAssignPassSlot(slot)}
                      disabled={!pendingPassCardKey}
                    >
                      <div className="slot-label">{PASS_SLOT_LABEL[slot]}</div>
                      <span className="slot-empty">{t('pass.slot.empty')}</span>
                    </button>
                  );
                })}
              </div>
              <Button type="button" size="sm" variant="outline" onClick={onClearPassSelection}>
                {t('pass.clear')}
              </Button>
            </div>
          )}
          {isInPassing && iAmPassSubmitted && <p className="hint">{t('pass.waiting')}</p>}

          {isInPlaying && (
            <>
              {selectedCards.length > 0 && (
                <span className="combo-hint" aria-live="polite">
                  선택: {selectedCombo}
                  {' '}({selectedCards.length}{t('seat.handCardsSuffix')})
                  {selectedCombo !== '?' && !selectedPlayable && (
                    <span className="combo-illegal"> · 지금 낼 수 없음</span>
                  )}
                </span>
              )}
              {/* 내기/패스는 경기장 좌석 좌우 버튼으로 이동(#6). 여기엔 티츄 선언만. */}
              {myDeclaration === 'NONE' &&
                (privateHand?.cards.length ?? 0) === 14 && (
                  <Button type="button" size="sm" variant="outline" onClick={onDeclareTichu}>
                    {t('play.action.declareTichu')}
                  </Button>
                )}
            </>
          )}
        </div>
      )}
    </>
  );
}

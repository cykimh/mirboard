import type { OmokMatchResult, OmokMode } from '@/types/omok';

const REASON_TEXT: Record<OmokMatchResult['reason'], string> = {
  FIVE: '5목',
  RESIGN: '기권',
  TIMEOUT: '시간 초과 두 번',
  DESERTED: '나감',
  BOARD_FULL: '판이 가득 참',
  NO_LEGAL_MOVE: '흑이 둘 곳이 금수뿐',
};

interface Props {
  result: OmokMatchResult;
  mode: OmokMode | null;
  mySeat: number;
  nameOf: (seat: number) => string;
  showNumbers: boolean;
  onToggleNumbers: () => void;
  onExit?: () => void;
  onBackToRoom?: () => void;
}

/** 판 결과 (룰 §10). 판은 그대로 보이고(승리 줄·수 번호) 이 카드가 아래에 붙는다. */
export function OmokMatchEnd({ result, mode, mySeat, nameOf, showNumbers, onToggleNumbers, onExit, onBackToRoom }: Props) {
  const draw = result.winnerColor === null;
  const iWon = !draw && mySeat >= 0 && result.winnerSeats.includes(mySeat);
  const iLost = !draw && mySeat >= 0 && !iWon;
  const colorName = result.winnerColor === 'BLACK' ? '흑' : '백';
  const headline = draw ? '무승부' : iWon ? '이겼어요!' : iLost ? '졌어요' : `${colorName} 승리`;
  const winners = result.winnerSeats.map(nameOf).join(' · ');
  const cause =
    result.causeSeat >= 0 && (result.reason === 'RESIGN' || result.reason === 'TIMEOUT' || result.reason === 'DESERTED')
      ? `${nameOf(result.causeSeat)} — ${REASON_TEXT[result.reason]}`
      : REASON_TEXT[result.reason];

  return (
    <section className="om-end" aria-label="판 결과" role="status">
      <p className="om-end-title">{headline}</p>
      {!draw && (
        <p className="om-end-line">
          {colorName}
          {mode === 'TEAMS_2V2' ? ' 팀' : ''} 승 — {winners}
        </p>
      )}
      <p className="om-end-line om-end-muted">{cause}</p>
      <div className="om-end-actions">
        <button type="button" className="om-badge" onClick={onToggleNumbers} aria-pressed={showNumbers}>
          {showNumbers ? '수 번호 숨기기' : '수 번호 보기'}
        </button>
        {onBackToRoom && (
          <button type="button" className="om-primary" onClick={onBackToRoom}>
            대기실로
          </button>
        )}
        {!onBackToRoom && onExit && (
          <button type="button" className="om-primary" onClick={onExit}>
            나가기
          </button>
        )}
      </div>
    </section>
  );
}

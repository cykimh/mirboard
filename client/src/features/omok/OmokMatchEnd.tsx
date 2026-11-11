import { useId } from 'react';
import type { OmokMatchResult, OmokMode } from '@/types/omok';
import { OmokDefs, StoneBody, artId } from './OmokArt';

const REASON_TEXT: Record<OmokMatchResult['reason'], string> = {
  FIVE: '5목',
  RESIGN: '기권',
  TIMEOUT: '시간 초과 두 번',
  DESERTED: '나감',
  BOARD_FULL: '판이 가득 참',
  NO_LEGAL_MOVE: '흑이 둘 곳이 금수뿐',
};

const FIVE = [0, 1, 2, 3, 4];

interface Props {
  result: OmokMatchResult;
  /** 판에 5목 줄이 그어지는 중 — 줄이 다 그어진 뒤에 카드가 뜬다(D-192, 연출 줄이기면 바로). */
  afterWinLine?: boolean;
  mode: OmokMode | null;
  mySeat: number;
  nameOf: (seat: number) => string;
  showNumbers: boolean;
  onToggleNumbers: () => void;
  onExit?: () => void;
  onBackToRoom?: () => void;
}

/** 판 결과 (룰 §10). 판은 그대로 보이고(승리 줄·수 번호) 이 카드가 아래에 붙는다. D-192 — 놋쇠 테를 두른 나무 현판. */
export function OmokMatchEnd({
  result,
  afterWinLine = false,
  mode,
  mySeat,
  nameOf,
  showNumbers,
  onToggleNumbers,
  onExit,
  onBackToRoom,
}: Props) {
  const id = artId(useId());
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
    <section
      className={`om-end${afterWinLine ? ' om-end-after-line' : ''}${iLost ? ' om-end-lost' : ''}`}
      aria-label="판 결과"
      role="status"
    >
      {/* 현판 장식 — 이긴 색 돌 다섯 개와 금 상감 줄(5목으로 끝났을 때), 아니면 흑백 한 쌍. 그림 조각이라 기본 테마에서는 안 보인다. */}
      {result.winnerColor && result.reason === 'FIVE' ? (
        <svg className="om-art om-end-art" viewBox="-0.6 -0.6 5.2 1.2" aria-hidden="true">
          <OmokDefs id={id} />
          <line className="om-end-inlay" x1={0} y1={0} x2={4} y2={0} />
          {FIVE.map((i) => (
            <g
              key={i}
              className={`om-stone om-stone-${result.winnerColor === 'BLACK' ? 'black' : 'white'}`}
              transform={`translate(${i} 0)`}
            >
              <StoneBody id={id} color={result.winnerColor!} seed={i + 1} />
            </g>
          ))}
        </svg>
      ) : (
        <svg className="om-art om-end-art om-end-art-pair" viewBox="-0.6 -0.6 2.1 1.2" aria-hidden="true">
          <OmokDefs id={id} />
          <g className="om-stone om-stone-black" transform="translate(0 0)">
            <StoneBody id={id} color="BLACK" />
          </g>
          <g className="om-stone om-stone-white" transform="translate(0.9 0)">
            <StoneBody id={id} color="WHITE" seed={2} />
          </g>
        </svg>
      )}
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

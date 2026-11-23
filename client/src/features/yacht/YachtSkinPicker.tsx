import { useEffect, useId, useRef, type KeyboardEvent } from 'react';
import { YachtDie } from './YachtDie';
import {
  BOARD_SKINS,
  BOARD_SKIN_LABEL,
  DICE_SKINS,
  DICE_SKIN_LABEL,
  boardSkinClass,
  useYachtSkinStore,
} from './yachtSkins';

interface Props {
  onClose: () => void;
}

/**
 * D-296 — 주사위·경기판 꾸미기 창(⋮ 메뉴 "주사위·판 꾸미기"). 라디오 두 묶음이고, 고르는 즉시 뒤의 판에 적용되고 이 기기에 기억된다
 * (내 화면만 — 서버로 가지 않는다). 게임판 루트 안에 그려 살롱 토큰을 그대로 쓴다. Esc·바깥 누르기·[닫기]로 닫고, 닫으면 열기 전의
 * 초점으로 돌아간다. 고른 것은 테두리와 "✓" 글자 둘 다로 알린다.
 */
export function YachtSkinPicker({ onClose }: Props) {
  const dice = useYachtSkinStore((st) => st.dice);
  const board = useYachtSkinStore((st) => st.board);
  const setDice = useYachtSkinStore((st) => st.setDice);
  const setBoard = useYachtSkinStore((st) => st.setBoard);
  const titleId = useId();
  const groupName = useId();
  const panelRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const before = document.activeElement as HTMLElement | null;
    panelRef.current?.querySelector<HTMLInputElement>('input:checked')?.focus();
    return () => before?.focus?.();
  }, []);

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.key === 'Escape') {
      e.preventDefault();
      e.stopPropagation();
      onClose();
      return;
    }
    if (e.key !== 'Tab') return;
    // 창 안에서만 돈다 — 라디오 묶음은 고른 것 하나씩이 탭 자리다.
    const stops = Array.from(panelRef.current?.querySelectorAll<HTMLElement>('button, input:checked') ?? []);
    if (stops.length === 0) return;
    const at = stops.indexOf(document.activeElement as HTMLElement);
    const next = e.shiftKey ? (at <= 0 ? stops.length - 1 : at - 1) : at === stops.length - 1 ? 0 : at + 1;
    e.preventDefault();
    stops[next]?.focus();
  };

  return (
    <div
      className="yt-skin-layer"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div
        ref={panelRef}
        className="yt-skin-panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onKeyDown={onKeyDown}
      >
        <div className="yt-skin-head">
          <h2 id={titleId} className="yt-skin-title">
            주사위·판 꾸미기
          </h2>
          <button type="button" className="yt-skin-close" onClick={onClose}>
            닫기
          </button>
        </div>
        <p className="yt-skin-note">내 화면에만 보여요 · 고르면 바로 바뀌고 이 기기에 기억해요</p>

        <fieldset className="yt-skin-group">
          <legend className="yt-skin-legend">주사위</legend>
          <div className="yt-skin-options">
            {DICE_SKINS.map((skin) => (
              <label key={skin} className={`yt-skin-option${skin === dice ? ' yt-skin-option-on' : ''}`}>
                <input
                  type="radio"
                  name={`${groupName}-dice`}
                  value={skin}
                  checked={skin === dice}
                  onChange={() => setDice(skin)}
                />
                <span className="yt-skin-die" aria-hidden="true">
                  <YachtDie face={5} skin={skin} />
                </span>
                <span>
                  {skin === dice ? '✓ ' : ''}
                  {DICE_SKIN_LABEL[skin]}
                </span>
              </label>
            ))}
          </div>
        </fieldset>

        <fieldset className="yt-skin-group">
          <legend className="yt-skin-legend">경기판</legend>
          <div className="yt-skin-options">
            {BOARD_SKINS.map((skin) => (
              <label key={skin} className={`yt-skin-option${skin === board ? ' yt-skin-option-on' : ''}`}>
                <input
                  type="radio"
                  name={`${groupName}-board`}
                  value={skin}
                  checked={skin === board}
                  onChange={() => setBoard(skin)}
                />
                <span className={`yt-skin-board ${boardSkinClass(skin)}`} aria-hidden="true" />
                <span>
                  {skin === board ? '✓ ' : ''}
                  {BOARD_SKIN_LABEL[skin]}
                </span>
              </label>
            ))}
          </div>
        </fieldset>
      </div>
    </div>
  );
}

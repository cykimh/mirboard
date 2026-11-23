import { useId, useRef } from 'react';
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog';
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

/** 다이얼로그는 body 포털이라 게임판 스코프 밖이다 — 살롱 토큰을 이 클래스로 다시 푼다(튜토리얼 D-121 의 `bodyClassName` 과 같은 문제). */
export const SKIN_PICKER_TOKEN_CLASS = 'yt-theme-salon';

/**
 * D-296 — 주사위·경기판 꾸미기 창(⋮ 메뉴 "주사위·판 꾸미기"). 라디오 두 묶음이고, 고르는 즉시 뒤의 판에 적용되고 이 기기에 기억된다
 * (내 화면만 — 서버로 가지 않는다). 공용 Radix 다이얼로그(`components/ui/dialog`)를 쓴다 — 초점 가두기·문서 단위 Esc·바깥 누르기·
 * 닫은 뒤 초점 되돌리기를 거기에 맡긴다(손으로 만든 창은 안의 글자·여백을 누르면 초점이 body 로 빠져 Esc·Tab 이 새었다, 리뷰).
 * 고른 것은 테두리와 "✓" 글자 둘 다로 알린다.
 */
export function YachtSkinPicker({ onClose }: Props) {
  const dice = useYachtSkinStore((st) => st.dice);
  const board = useYachtSkinStore((st) => st.board);
  const setDice = useYachtSkinStore((st) => st.setDice);
  const setBoard = useYachtSkinStore((st) => st.setBoard);
  const groupName = useId();
  const panelRef = useRef<HTMLDivElement>(null);

  return (
    <Dialog open onOpenChange={(o) => !o && onClose()}>
      <DialogContent
        ref={panelRef}
        className={`${SKIN_PICKER_TOKEN_CLASS} yt-skin-panel`}
        // 열리면 고른 주사위 라디오에 초점(기본은 첫 초점 자리).
        onOpenAutoFocus={(e) => {
          const checked = panelRef.current?.querySelector<HTMLInputElement>('input:checked');
          if (!checked) return;
          e.preventDefault();
          checked.focus();
        }}
      >
        <div className="yt-skin-head">
          <DialogTitle className="yt-skin-title">주사위·판 꾸미기</DialogTitle>
          <DialogClose className="yt-skin-close">닫기</DialogClose>
        </div>
        <DialogDescription className="yt-skin-note">내 화면에만 보여요 · 고르면 바로 바뀌고 이 기기에 기억해요</DialogDescription>

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
      </DialogContent>
    </Dialog>
  );
}

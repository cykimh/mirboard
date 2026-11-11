import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { createPortal, flushSync } from 'react-dom';
import { REACTIONS } from '@/features/chat/reactionStore';
import { useThemeStore } from '@/features/theme/themeStore';
import type { useVoiceChat } from '@/features/voice/useVoiceChat';
import { VOICE_ENABLED } from '@/features/voice/voiceSwitch';

type Voice = ReturnType<typeof useVoiceChat>;

/** 메뉴의 켜고 끄는 설정 한 줄(티츄 카드 애니메이션 등). */
export interface GameMenuToggle {
  key: string;
  label: string;
  checked: boolean;
  onToggle: () => void;
}

/** 메뉴의 보기 한 줄(스컬킹 점수표 등). */
export interface GameMenuView {
  key: string;
  label: string;
  onSelect: () => void;
}

export interface GameMenuProps {
  /** 머리 줄에서 내린 방 정보(연습·턴 제한·관전 수·연결 …). 거짓 값은 건너뛴다. 메뉴 맨 위 글 줄. */
  info?: ReadonlyArray<string | false | null | undefined>;
  /** 효과음. 없으면 줄이 없다. */
  sound?: { muted: boolean; onToggle: () => void };
  /** 게임만의 켜고 끄는 설정. */
  toggles?: GameMenuToggle[];
  /** 게임 방법 다시 보기. */
  onRules?: () => void;
  /** 게임만의 보기(점수표 등). */
  views?: GameMenuView[];
  /** 이모지 반응. 관전자에게는 넘기지 않는다. */
  onReaction?: (emoji: string) => void;
  /** 음성 대화(D-155). 스위치(D-159)가 꺼져 있으면 줄이 없다. */
  voice?: Voice;
  /** 기권 — 그 게임·그 상황에서만(확인은 부르는 쪽). */
  onResign?: () => void;
  /** 방장 강제 종료(D-135, 확인은 부르는 쪽). */
  onAbort?: () => void;
  /** 나가기(탈주 확인은 부르는 쪽). */
  onExit?: () => void;
  /** ⋮ 버튼 모양 — 게임판 머리 줄 알약과 같게(`oc-badge` 등). */
  triggerClassName?: string;
}

const ITEM_SELECTOR = '[role="menuitem"]:not([aria-disabled="true"]), [role="menuitemcheckbox"]:not([aria-disabled="true"])';

/**
 * D-186 — 게임판 머리 줄 오른쪽 끝 ⋮ 메뉴(게임 중립). 머리 줄에는 게임 이름·핵심 배지·채팅만 남기고 방 정보·설정(효과음·라이트/다크·
 * 게임 설정)·규칙·보기·반응·기권·게임 종료·나가기는 여기로 모은다(휴대폰에서 버튼 줄이 세 줄로 접혀 경기장을 밀었다).
 *
 * 메뉴 판은 body 포털이다 — 게임판 스코프(토큰·overflow)와 무관하게 앱 테마(`<html>.dark`) 색으로 읽힌다. 바깥 누르기·Esc·Tab 으로
 * 닫히고, 열면 첫 항목에 초점, 위·아래·Home·End 로 옮긴다. 항목을 고르면 먼저 닫고(확인 창이 메뉴 위에 뜨지 않게) 그다음 부른다.
 */
export function GameMenu({
  info,
  sound,
  toggles,
  onRules,
  views,
  onReaction,
  voice,
  onResign,
  onAbort,
  onExit,
  triggerClassName,
}: GameMenuProps) {
  const [open, setOpen] = useState(false);
  const [pos, setPos] = useState<{ top: number; right: number } | null>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const menuId = useId();
  const theme = useThemeStore((s) => s.theme);
  const toggleTheme = useThemeStore((s) => s.toggle);

  const place = useCallback(() => {
    const t = triggerRef.current;
    if (!t) return;
    const r = t.getBoundingClientRect();
    setPos({ top: Math.round(r.bottom + 6), right: Math.max(8, Math.round(window.innerWidth - r.right)) });
  }, []);

  const close = useCallback((refocus: boolean) => {
    setOpen(false);
    if (refocus) triggerRef.current?.focus();
  }, []);

  useLayoutEffect(() => {
    if (open) place();
  }, [open, place]);

  // 열리면 첫 항목에 초점.
  useEffect(() => {
    if (!open) return;
    menuRef.current?.querySelector<HTMLElement>(ITEM_SELECTOR)?.focus();
  }, [open]);

  // 바깥 누르기·창 크기 바뀜.
  useEffect(() => {
    if (!open) return;
    const onDown = (e: PointerEvent | MouseEvent) => {
      const target = e.target as Node | null;
      if (!target) return;
      if (menuRef.current?.contains(target) || triggerRef.current?.contains(target)) return;
      setOpen(false);
    };
    const onResize = () => place();
    document.addEventListener('pointerdown', onDown);
    document.addEventListener('mousedown', onDown);
    window.addEventListener('resize', onResize);
    window.addEventListener('scroll', onResize, true);
    return () => {
      document.removeEventListener('pointerdown', onDown);
      document.removeEventListener('mousedown', onDown);
      window.removeEventListener('resize', onResize);
      window.removeEventListener('scroll', onResize, true);
    };
  }, [open, place]);

  /** 고르면 닫고(화면에 먼저 반영) 초점을 ⋮ 로 돌린 뒤 부른다 — 확인 창·이동이 열린 메뉴 위에서 일어나지 않게. */
  const pick = (action: () => void, keepOpen = false) => () => {
    if (!keepOpen) {
      flushSync(() => setOpen(false));
      triggerRef.current?.focus();
    }
    action();
  };

  const onTriggerKey = (e: KeyboardEvent<HTMLButtonElement>) => {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      setOpen(true);
    }
  };

  const onMenuKey = (e: KeyboardEvent<HTMLDivElement>) => {
    const items = Array.from(menuRef.current?.querySelectorAll<HTMLElement>(ITEM_SELECTOR) ?? []);
    const at = items.indexOf(document.activeElement as HTMLElement);
    const go = (i: number) => {
      e.preventDefault();
      items[(i + items.length) % items.length]?.focus();
    };
    switch (e.key) {
      case 'ArrowDown':
        go(at + 1);
        break;
      case 'ArrowUp':
        go(at < 0 ? items.length - 1 : at - 1);
        break;
      case 'Home':
        go(0);
        break;
      case 'End':
        go(items.length - 1);
        break;
      case 'Escape':
        e.preventDefault();
        close(true);
        break;
      case 'Tab':
        close(false);
        break;
      default:
    }
  };

  const infoLines = (info ?? []).filter((x): x is string => typeof x === 'string' && x.length > 0);
  const showVoice = VOICE_ENABLED && !!voice;
  const danger = !!(onResign || onAbort || onExit);

  const check = (key: string, label: string, checked: boolean, onToggle: () => void, disabled = false): ReactNode => (
    <button
      key={key}
      type="button"
      role="menuitemcheckbox"
      aria-checked={checked}
      className="gm-item"
      onClick={pick(onToggle, true)}
      disabled={disabled}
      aria-disabled={disabled || undefined}
    >
      <span className="gm-item-label">{label}</span>
      <span className={`gm-switch${checked ? ' gm-switch-on' : ''}`} aria-hidden="true">
        {checked ? '켬' : '끔'}
      </span>
    </button>
  );

  const item = (key: string, label: string, onSelect: () => void, tone?: 'danger'): ReactNode => (
    <button
      key={key}
      type="button"
      role="menuitem"
      className={`gm-item${tone === 'danger' ? ' gm-item-danger' : ''}`}
      onClick={pick(onSelect)}
    >
      <span className="gm-item-label">{label}</span>
    </button>
  );

  return (
    <>
      <button
        ref={triggerRef}
        type="button"
        className={`gm-trigger${triggerClassName ? ` ${triggerClassName}` : ''}`}
        aria-label="게임 메뉴"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        title="메뉴 — 설정·규칙·나가기"
        onClick={() => setOpen((v) => !v)}
        onKeyDown={onTriggerKey}
      >
        <span aria-hidden="true">⋮</span>
      </button>
      {open &&
        createPortal(
          <div
            ref={menuRef}
            id={menuId}
            role="menu"
            aria-label="게임 메뉴"
            className="gm-menu"
            style={pos ? { top: pos.top, right: pos.right } : { visibility: 'hidden' }}
            onKeyDown={onMenuKey}
            // 포털이어도 React 이벤트는 게임판 트리로 거슬러 올라간다 — 게임판의 바탕 누르기(티츄 선택 해제 등)가 메뉴 누르기를 받지 않게.
            onClick={(e) => e.stopPropagation()}
            onPointerDown={(e) => e.stopPropagation()}
          >
            {infoLines.length > 0 && (
              <div role="none" className="gm-info">
                {infoLines.join(' · ')}
              </div>
            )}
            {onReaction && (
              <div role="group" aria-label="이모지 반응" className="gm-reactions">
                {REACTIONS.map((emoji) => (
                  <button
                    key={emoji}
                    type="button"
                    role="menuitem"
                    className="gm-emoji"
                    aria-label={`반응 ${emoji}`}
                    onClick={pick(() => onReaction(emoji))}
                  >
                    {emoji}
                  </button>
                ))}
              </div>
            )}
            <div role="group" aria-label="설정" className="gm-group">
              {sound && check('sound', '효과음', !sound.muted, sound.onToggle)}
              {check('theme', '다크 모드', theme === 'dark', toggleTheme)}
              {toggles?.map((t) => check(t.key, t.label, t.checked, t.onToggle))}
              {showVoice && voice && (
                <>
                  {check('voice', '음성 대화', voice.joined, voice.joined ? voice.leave : voice.join)}
                  {check('mic', '마이크', voice.micOn, () => void voice.toggleMic(), !voice.joined)}
                </>
              )}
            </div>
            {(onRules || (views && views.length > 0)) && (
              <div role="group" aria-label="보기" className="gm-group">
                {onRules && item('rules', '규칙 보기', onRules)}
                {views?.map((v) => item(v.key, v.label, v.onSelect))}
              </div>
            )}
            {danger && (
              <div role="group" aria-label="게임" className="gm-group">
                {onResign && item('resign', '기권', onResign, 'danger')}
                {onAbort && item('abort', '게임 종료', onAbort, 'danger')}
                {onExit && item('exit', '나가기', onExit)}
              </div>
            )}
          </div>,
          document.body,
        )}
    </>
  );
}

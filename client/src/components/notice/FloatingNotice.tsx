import { useEffect, useRef, type MouseEvent, type PointerEvent, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { noticeLayer } from './noticeLayer';

/** 오류 안내가 스스로 내려가는 시간 — 한두 줄을 읽기에 넉넉하게. */
export const NOTICE_AUTO_CLOSE_MS = 6000;

/**
 * 층 안의 버튼([닫기])에 펼쳐 넣는 손잡이. 층은 Radix 모달(대화상자) 바깥이라, 그대로 두면 버튼을 누르는 것이 "바깥 누름"으로
 * 잡혀 대화상자가 닫힌다(티츄 소원 창이면 마작 플레이가 취소된다). pointerdown 을 문서까지 올리지 않고(바깥 판정은 문서에서 듣는다),
 * mousedown 의 기본 동작을 막아 초점도 대화상자 밖으로 옮기지 않는다.
 */
export const noticeButtonGuards = {
  onPointerDown: (e: PointerEvent) => e.stopPropagation(),
  onMouseDown: (e: MouseEvent) => e.preventDefault(),
};

interface FloatingNoticeProps {
  /** `warning` = 재연결 같은 상태 안내(`role="status"`), `error` = 거절·실패 안내(`role="alert"`). */
  tone: 'warning' | 'error';
  children: ReactNode;
  /** 주면 [닫기] 버튼이 생기고 `autoCloseMs` 뒤 스스로 닫힌다. */
  onClose?: () => void;
  /** 자동 닫힘 시간(ms). 0 이면 스스로 닫히지 않는다. */
  autoCloseMs?: number;
  /** 바뀌면 자동 닫힘 시간을 다시 잰다 — 같은 문구가 다시 온 것을 알리는 발생 순번. */
  restartKey?: unknown;
}

const TONE_CLASS: Record<FloatingNoticeProps['tone'], string> = {
  // 테마와 무관한 불투명 색 — 게임판마다 바탕(펠트·나무·하늘·옻칠)이 달라 반투명·테마 토큰은 읽기 어려워진다.
  warning: 'border-[#8a6a00] bg-[#3b3000] text-[#ffe69c]',
  error: 'border-[#ff9aa8] bg-[#a3182f] text-white',
};

/**
 * D-269 — 화면 위 가운데에 떠서 보이는 경고·오류 안내. 게임판 흐름 안에 그리면 뜰 때마다 판이 밀려 내려가서, 어디에 써도
 * body 의 한 층({@link noticeLayer})으로 포털해 그린다. 안내 본문은 눌림을 통과시킨다 — 머리 줄 버튼이나 원카드 경쟁 버튼 위에
 * 떠도 아래 것이 그대로 눌린다. 눌리는 것은 [닫기] 버튼뿐이다.
 */
export function FloatingNotice({
  tone,
  children,
  onClose,
  autoCloseMs = NOTICE_AUTO_CLOSE_MS,
  restartKey,
}: FloatingNoticeProps) {
  // 부모가 매 렌더 새 함수를 넘겨도 타이머를 다시 재지 않게 ref 로 잡는다.
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;
  const closable = onClose !== undefined;
  // 문구가 바뀌어도(다른 오류) 시간을 다시 잰다.
  const textKey = typeof children === 'string' ? children : null;

  useEffect(() => {
    if (!closable || autoCloseMs <= 0) return;
    const id = window.setTimeout(() => onCloseRef.current?.(), autoCloseMs);
    return () => window.clearTimeout(id);
  }, [closable, autoCloseMs, textKey, restartKey]);

  return createPortal(
    <div
      role={tone === 'error' ? 'alert' : 'status'}
      // 역할의 기본값과 같지만 속성으로도 적는다 — 층이 생긴 뒤 뜬 안내도 보조 기술이 놓치지 않게.
      aria-live={tone === 'error' ? 'assertive' : 'polite'}
      data-floating-notice={tone}
      className={
        'pointer-events-none mb-2 box-border flex w-fit items-center gap-2 rounded-lg border border-solid px-3 py-2 text-left font-sans text-sm leading-snug shadow-lg ' +
        'motion-safe:animate-in motion-safe:fade-in motion-safe:slide-in-from-top-2 motion-safe:duration-200 ' +
        TONE_CLASS[tone]
      }
      style={{ maxWidth: 'min(28rem, 100%)' }}
    >
      <div className="min-w-0 flex-1 break-words">{children}</div>
      {closable && (
        <button
          type="button"
          aria-label="닫기"
          {...noticeButtonGuards}
          onClick={() => onCloseRef.current?.()}
          // `!` — 01-base.css 의 `button:hover:not(:disabled)`(회색 바탕)가 유틸리티보다 세서 덮어쓴다.
          className="pointer-events-auto -my-1 -mr-1 box-border flex h-8 min-h-0 w-8 flex-none cursor-pointer items-center justify-center rounded-md border-0 !bg-transparent p-0 text-lg leading-none text-inherit hover:!bg-white/20"
        >
          ×
        </button>
      )}
    </div>,
    noticeLayer(),
  );
}

interface ErrorNoticeProps {
  /** 게임 스토어의 `errorMessage` — null 이면 그리지 않는다. */
  message: string | null;
  /** 스토어의 `errorSeq` — 같은 문구가 다시 와도 시간을 다시 잰다. */
  seq: number;
  /** 스토어의 `errorSticky` — 지속 상태(resync 실패)는 스스로 닫지 않는다. */
  sticky: boolean;
  onClose: () => void;
}

/** 게임판의 거절·실패 안내 — 일곱 게임판이 같은 모양으로 쓴다(`components/notice/errorNotice` 의 스토어 값). */
export function ErrorNotice({ message, seq, sticky, onClose }: ErrorNoticeProps) {
  if (!message) return null;
  return (
    <FloatingNotice tone="error" onClose={onClose} restartKey={seq} autoCloseMs={sticky ? 0 : undefined}>
      {message}
    </FloatingNotice>
  );
}

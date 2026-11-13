import { useEffect, useRef, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { noticeLayer } from './noticeLayer';

/** 오류 안내가 스스로 내려가는 시간 — 한두 줄을 읽기에 넉넉하게. */
export const NOTICE_AUTO_CLOSE_MS = 6000;

interface FloatingNoticeProps {
  /** `warning` = 재연결 같은 상태 안내(`role="status"`), `error` = 거절·실패 안내(`role="alert"`). */
  tone: 'warning' | 'error';
  children: ReactNode;
  /** 주면 [닫기] 버튼이 생기고, 안내를 눌러도 닫히며, `autoCloseMs` 뒤 스스로 닫힌다. */
  onClose?: () => void;
  /** 자동 닫힘 시간(ms). 0 이면 스스로 닫히지 않는다. */
  autoCloseMs?: number;
}

const TONE_CLASS: Record<FloatingNoticeProps['tone'], string> = {
  // 테마와 무관한 불투명 색 — 게임판마다 바탕(펠트·나무·하늘·옻칠)이 달라 반투명·테마 토큰은 읽기 어려워진다.
  warning: 'border-[#8a6a00] bg-[#3b3000] text-[#ffe69c]',
  error: 'border-[#ff9aa8] bg-[#a3182f] text-white',
};

/**
 * D-269 — 화면 위 가운데에 떠서 보이는 경고·오류 안내. 게임판 흐름 안에 그리면 뜰 때마다 판이 밀려 내려가서, 어디에 써도
 * body 의 한 층({@link noticeLayer})으로 포털해 그린다. 닫을 수 없는 안내(재연결)는 눌림을 통과시켜 아래 버튼을 막지 않는다.
 */
export function FloatingNotice({ tone, children, onClose, autoCloseMs = NOTICE_AUTO_CLOSE_MS }: FloatingNoticeProps) {
  // 부모가 매 렌더 새 함수를 넘겨도 타이머를 다시 재지 않게 ref 로 잡는다.
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;
  const closable = onClose !== undefined;
  // 문구가 바뀌면(다른 오류) 시간을 다시 잰다.
  const textKey = typeof children === 'string' ? children : null;

  useEffect(() => {
    if (!closable || autoCloseMs <= 0) return;
    const id = window.setTimeout(() => onCloseRef.current?.(), autoCloseMs);
    return () => window.clearTimeout(id);
  }, [closable, autoCloseMs, textKey]);

  return createPortal(
    <div
      role={tone === 'error' ? 'alert' : 'status'}
      data-floating-notice={tone}
      onClick={closable ? () => onCloseRef.current?.() : undefined}
      className={
        'mb-2 box-border flex w-fit max-w-md items-center gap-2 rounded-lg border border-solid px-3 py-2 text-left font-sans text-sm leading-snug shadow-lg ' +
        'motion-safe:animate-in motion-safe:fade-in motion-safe:slide-in-from-top-2 motion-safe:duration-200 ' +
        (closable ? 'pointer-events-auto cursor-pointer ' : '') +
        TONE_CLASS[tone]
      }
      style={{ maxWidth: 'min(28rem, 100%)' }}
    >
      <div className="min-w-0 flex-1 break-words">{children}</div>
      {closable && (
        <button
          type="button"
          aria-label="닫기"
          onClick={(e) => {
            e.stopPropagation();
            onCloseRef.current?.();
          }}
          className="-my-1 -mr-1 box-border flex h-8 min-h-0 w-8 flex-none cursor-pointer items-center justify-center rounded-md border-0 bg-transparent p-0 text-lg leading-none text-inherit hover:bg-white/15"
        >
          ×
        </button>
      )}
    </div>,
    noticeLayer(),
  );
}

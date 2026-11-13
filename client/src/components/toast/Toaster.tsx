import { createPortal } from 'react-dom';
import { noticeLayer } from '@/components/notice/noticeLayer';
import { useToastStore } from './toastStore';

/**
 * D-157 — 토스트 자리. 앱에 하나만 둔다(`App`). 화면 위 가운데에 겹쳐 뜨고 아래 버튼을 가리지 않게 눌림은 통과시킨다.
 * 라이브 영역은 늘 두고 내용만 바꾼다 — 영역과 내용을 함께 삽입하면 일부 스크린 리더가 읽지 않는다.
 * D-269 — 자리는 경고·오류 안내와 같은 층(`noticeLayer`)이다. 둘 다 화면 위 가운데라 따로 고정하면 서로 겹친다.
 * 게임판은 `.app-shell` 밖이라 박스 크기·글꼴을 여기서 정한다.
 */
export function Toaster() {
  const toasts = useToastStore((s) => s.toasts);
  return createPortal(
    <div
      role="status"
      aria-live="polite"
      data-testid="toaster"
      className="flex w-full flex-col items-center"
    >
      {toasts.map((t) => (
        <p
          key={t.id}
          className={
            'm-0 mb-2 box-border max-w-sm rounded-md px-4 py-2 text-center text-sm shadow-lg ' +
            (t.tone === 'error' ? 'bg-destructive text-destructive-foreground' : 'bg-foreground text-background')
          }
        >
          {t.message}
        </p>
      ))}
    </div>,
    noticeLayer(),
  );
}

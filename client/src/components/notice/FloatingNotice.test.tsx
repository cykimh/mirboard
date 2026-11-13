import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ReconnectBanner } from '@/components/ReconnectBanner';
import { Toaster } from '@/components/toast/Toaster';
import { showToast, useToastStore } from '@/components/toast/toastStore';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog';
import { SpectateNotice } from '@/features/lobby/SpectateNotice';
import { useArtilleryStore } from '@/features/artillery/artilleryStore';
import { artilleryRoomSink } from '@/features/artillery/artilleryRoomSink';
import { useOneCardStore } from '@/features/onecard/onecardStore';
import { ErrorNotice, FloatingNotice, NOTICE_AUTO_CLOSE_MS } from './FloatingNotice';
import { NOTICE_LAYER_ID } from './noticeLayer';

/** D-269 — 경고·오류 안내는 게임판 흐름에 끼지 않고 화면 위의 한 층에 떠서 보인다. */
describe('FloatingNotice', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    useToastStore.getState().clear();
    vi.useRealTimers();
  });

  it('쓴 자리(게임판 흐름)가 아니라 body 의 고정 층에 그린다', () => {
    render(
      <div data-testid="table">
        <FloatingNotice tone="error" onClose={() => {}}>
          지금 낼 수 없는 카드입니다.
        </FloatingNotice>
      </div>,
    );

    const alert = screen.getByRole('alert');
    const layer = document.getElementById(NOTICE_LAYER_ID)!;
    expect(alert).toHaveTextContent('지금 낼 수 없는 카드입니다.');
    expect(screen.getByTestId('table')).not.toContainElement(alert);
    expect(screen.getByTestId('table')).toBeEmptyDOMElement();
    expect(layer).toContainElement(alert);
    expect(layer.parentElement).toBe(document.body);
    expect(layer).toHaveClass('fixed', 'pointer-events-none');
  });

  it('오류는 [닫기]·시간 경과로 닫히고, 본문은 눌림을 통과시킨다(아래 버튼을 삼키지 않는다)', () => {
    const onClose = vi.fn();
    render(
      <FloatingNotice tone="error" onClose={onClose}>
        돈이 모자랍니다.
      </FloatingNotice>,
    );

    const alert = screen.getByRole('alert');
    const close = screen.getByRole('button', { name: '닫기' });
    expect(alert).toHaveClass('pointer-events-none');
    expect(close).toHaveClass('pointer-events-auto');
    // 01-base.css 의 button:hover 회색 바탕을 이기도록 !important 로 적는다.
    expect(close.className).toContain('hover:!bg-white/20');

    fireEvent.click(alert); // 본문을 눌러도 닫히지 않는다
    expect(onClose).not.toHaveBeenCalled();
    fireEvent.click(close);
    expect(onClose).toHaveBeenCalledTimes(1);

    act(() => vi.advanceTimersByTime(NOTICE_AUTO_CLOSE_MS));
    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it('닫을 수 없는 안내(재연결)는 상태 안내이고 스스로 내려가지 않는다', () => {
    render(
      <div data-testid="table">
        <ReconnectBanner connected={false} />
      </div>,
    );
    act(() => vi.advanceTimersByTime(2000));

    const status = screen.getByRole('status');
    expect(status).toHaveTextContent('재연결 중');
    expect(screen.getByTestId('table')).not.toContainElement(status);
    expect(status).toHaveClass('pointer-events-none');
    expect(screen.queryByRole('button', { name: '닫기' })).toBeNull();

    act(() => vi.advanceTimersByTime(NOTICE_AUTO_CLOSE_MS * 2));
    expect(screen.getByRole('status')).toHaveTextContent('재연결 중');
  });

  it('토스트·관전 안내도 같은 층에 쌓인다(서로 겹치지 않게)', () => {
    render(
      <>
        <Toaster />
        <FloatingNotice tone="error" onClose={() => {}}>
          거절 안내
        </FloatingNotice>
        <SpectateNotice kind="full" onClose={() => {}} />
      </>,
    );
    act(() => showToast('링크를 복사했어요'));

    const layer = document.getElementById(NOTICE_LAYER_ID)!;
    expect(layer).toContainElement(screen.getByText('링크를 복사했어요'));
    expect(layer).toContainElement(screen.getByText('거절 안내'));
    expect(layer).toContainElement(screen.getByText('자리가 없어 관전으로 들어왔어요'));
    expect(layer.querySelector('.fixed')).toBeNull(); // 안의 것은 따로 고정하지 않는다
  });
});

describe('ErrorNotice — 게임 스토어의 오류 안내 (D-269)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    useOneCardStore.getState().reset('r1');
    useArtilleryStore.getState().reset('r1');
  });
  afterEach(() => vi.useRealTimers());

  function OneCardError() {
    const s = useOneCardStore();
    return <ErrorNotice message={s.errorMessage} seq={s.errorSeq} sticky={s.errorSticky} onClose={() => s.setError(null)} />;
  }

  it('같은 문구가 다시 오면 자동 닫힘 시간을 다시 잰다', () => {
    render(<OneCardError />);
    act(() => useOneCardStore.getState().setError('지금 낼 수 없는 카드입니다.'));
    act(() => vi.advanceTimersByTime(NOTICE_AUTO_CLOSE_MS - 500));
    act(() => useOneCardStore.getState().setError('지금 낼 수 없는 카드입니다.')); // 두 번째 거절 — 같은 문자열

    act(() => vi.advanceTimersByTime(1000)); // 첫 번째의 남은 시간이 지나도
    expect(screen.getByRole('alert')).toHaveTextContent('지금 낼 수 없는 카드입니다.');

    act(() => vi.advanceTimersByTime(NOTICE_AUTO_CLOSE_MS - 1000));
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('지속 오류(resync 실패 — sink.setError)는 스스로 닫히지 않고 [닫기]로 닫는다', () => {
    function ArtilleryError() {
      const s = useArtilleryStore();
      return <ErrorNotice message={s.errorMessage} seq={s.errorSeq} sticky={s.errorSticky} onClose={s.dismissError} />;
    }
    render(<ArtilleryError />);
    act(() => artilleryRoomSink.setError('network down'));
    expect(useArtilleryStore.getState().errorSticky).toBe(true);

    act(() => vi.advanceTimersByTime(NOTICE_AUTO_CLOSE_MS * 3));
    expect(screen.getByRole('alert')).toHaveTextContent('network down');

    fireEvent.click(screen.getByRole('button', { name: '닫기' }));
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('씨앗포 — 안내가 닫혀도(자동·[닫기]) 진행 중 액션의 pending 잠금은 풀리지 않는다', () => {
    function ArtilleryError() {
      const s = useArtilleryStore();
      return <ErrorNotice message={s.errorMessage} seq={s.errorSeq} sticky={s.errorSticky} onClose={s.dismissError} />;
    }
    render(<ArtilleryError />);
    act(() => {
      useArtilleryStore.getState().setError('더 걸어갈 수 없어요.'); // 거절 — 이때는 잠금을 푼다
      useArtilleryStore.getState().setPending(true); // 이어서 FIRE 를 보냈다
    });

    act(() => vi.advanceTimersByTime(NOTICE_AUTO_CLOSE_MS));
    expect(screen.queryByRole('alert')).toBeNull();
    expect(useArtilleryStore.getState().pending).toBe(true);
  });
});

describe('FloatingNotice — Radix 모달과 함께 (D-269)', () => {
  it('[닫기]를 눌러도 대화상자의 바깥 누름으로 잡히지 않고, 층은 aria-hidden 이 되지 않는다', async () => {
    const onOpenChange = vi.fn();
    const onClose = vi.fn();
    render(
      <>
        <FloatingNotice tone="error" onClose={onClose} autoCloseMs={0}>
          거절 안내
        </FloatingNotice>
        <Dialog open onOpenChange={onOpenChange}>
          <DialogContent>
            <DialogTitle>소원</DialogTitle>
            <DialogDescription>숫자를 고르세요</DialogDescription>
          </DialogContent>
        </Dialog>
      </>,
    );

    // Radix 는 바깥 누름 듣기를 한 틱 뒤에 건다.
    await act(() => new Promise((r) => setTimeout(r, 0)));

    const layer = document.getElementById(NOTICE_LAYER_ID)!;
    expect(layer).not.toHaveAttribute('aria-hidden');
    expect(layer).toHaveAttribute('aria-live', 'off');

    const close = screen.getByRole('button', { name: '닫기', hidden: true });
    expect(close.closest('[role="alert"]')).not.toBeNull();
    fireEvent.pointerDown(close);
    const mouseDown = fireEvent.mouseDown(close); // false = preventDefault 됨(초점이 대화상자 밖으로 옮겨 가지 않는다)
    fireEvent.click(close);

    expect(mouseDown).toBe(false);
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onOpenChange).not.toHaveBeenCalled();

    // 대조 — 다른 바깥 누름은 그대로 대화상자를 닫는다(위 검사가 헛돌지 않는다는 확인).
    fireEvent.pointerDown(document.body);
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });
});

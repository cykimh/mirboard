import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ReconnectBanner } from '@/components/ReconnectBanner';
import { Toaster } from '@/components/toast/Toaster';
import { showToast, useToastStore } from '@/components/toast/toastStore';
import { FloatingNotice, NOTICE_AUTO_CLOSE_MS } from './FloatingNotice';
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

  it('오류는 [닫기]·안내 누름·시간 경과로 닫힌다', () => {
    const onClose = vi.fn();
    render(
      <FloatingNotice tone="error" onClose={onClose}>
        돈이 모자랍니다.
      </FloatingNotice>,
    );

    fireEvent.click(screen.getByRole('button', { name: '닫기' }));
    expect(onClose).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('alert'));
    expect(onClose).toHaveBeenCalledTimes(2);

    act(() => vi.advanceTimersByTime(NOTICE_AUTO_CLOSE_MS));
    expect(onClose).toHaveBeenCalledTimes(3);
    expect(screen.getByRole('alert')).toHaveClass('pointer-events-auto');
  });

  it('닫을 수 없는 안내(재연결)는 상태 안내이고 아래 버튼을 막지 않는다', () => {
    render(
      <div data-testid="table">
        <ReconnectBanner connected={false} />
      </div>,
    );
    act(() => vi.advanceTimersByTime(2000));

    const status = screen.getByRole('status');
    expect(status).toHaveTextContent('재연결 중');
    expect(screen.getByTestId('table')).not.toContainElement(status);
    expect(status).not.toHaveClass('pointer-events-auto');
    expect(screen.queryByRole('button', { name: '닫기' })).toBeNull();

    act(() => vi.advanceTimersByTime(NOTICE_AUTO_CLOSE_MS * 2));
    expect(screen.getByRole('status')).toHaveTextContent('재연결 중'); // 스스로 내려가지 않는다
  });

  it('토스트도 같은 층에 쌓인다(서로 겹치지 않게)', () => {
    render(
      <>
        <Toaster />
        <FloatingNotice tone="error" onClose={() => {}}>
          거절 안내
        </FloatingNotice>
      </>,
    );
    act(() => showToast('링크를 복사했어요'));

    const layer = document.getElementById(NOTICE_LAYER_ID)!;
    expect(layer).toContainElement(screen.getByText('링크를 복사했어요'));
    expect(layer).toContainElement(screen.getByText('거절 안내'));
  });
});

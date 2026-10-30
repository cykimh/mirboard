import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { InviteLinkButton } from './InviteLinkButton';
import { Toaster } from '@/components/toast/Toaster';
import { useToastStore } from '@/components/toast/toastStore';
import type { ShareEnv } from './shareRoomLink';

/**
 * D-133 — 대기실 "초대 링크" 버튼. 공유·복사는 `shareRoomLink` 가 하고 버튼은 결과만 그린다:
 * 공유됨·복사됨은 토스트(D-157), 둘 다 안 되면(카톡 같은 앱 내 브라우저) 링크가 **선택된** 입력란, 취소는 아무것도 안 보인다.
 */

const ORIGIN = 'https://mirboard.test';
const LINK = `${ORIGIN}/rooms/r1`;

function renderButton(env: ShareEnv) {
  render(
    <>
      <InviteLinkButton roomId="r1" roomName="금요일 한 판" gameName="원카드" env={env} origin={ORIGIN} />
      <Toaster />
    </>,
  );
  return screen.getByRole('button', { name: '초대 링크' });
}

afterEach(() => useToastStore.getState().clear());

describe('InviteLinkButton — 결과별 표시 (D-133)', () => {
  it('공유창이 있으면 방 주소 그대로를 게임·방 이름과 함께 공유하고 "보냈어요"를 알린다', async () => {
    const share = vi.fn().mockResolvedValue(undefined);
    fireEvent.click(renderButton({ share }));

    expect(screen.getByTestId('toaster')).toContainElement(await screen.findByText('초대 링크를 보냈어요'));
    expect(share).toHaveBeenCalledWith({
      title: '원카드 같이 해요',
      text: '금요일 한 판',
      url: LINK,
    });
  });

  it('공유창이 없으면 복사하고 "복사했어요"를 알린다', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    fireEvent.click(renderButton({ clipboard: { writeText } }));

    expect(screen.getByTestId('toaster')).toContainElement(await screen.findByText('링크를 복사했어요'));
    expect(writeText).toHaveBeenCalledWith(LINK);
    expect(screen.queryByRole('textbox')).toBeNull();
  });

  it('공유창·클립보드가 없는 앱 내 브라우저면 링크가 선택된 입력란을 보인다', async () => {
    fireEvent.click(renderButton({}));

    const input = (await screen.findByLabelText('직접 복사해 주세요')) as HTMLInputElement;
    expect(input.value).toBe(LINK);
    expect(input.readOnly).toBe(true);
    expect(document.activeElement).toBe(input);
    expect(input.selectionStart).toBe(0);
    expect(input.selectionEnd).toBe(LINK.length);
  });

  // FR-6 — iOS WebKit(카톡 iOS 앱 내 브라우저 포함)은 읽기 전용 입력란에 select() 만으로는 선택이 잡히지 않는 경우가 있다.
  // select() 뒤 setSelectionRange(0, 길이)도 부른다 — 열릴 때(effect)와 다시 포커스될 때(onFocus) 둘 다. 실기기는 QA 4번.
  it('수동 입력란은 select() 뒤 setSelectionRange(0, 길이)로도 링크 전체를 고른다 — 열릴 때와 다시 포커스될 때', async () => {
    const select = vi.spyOn(HTMLInputElement.prototype, 'select');
    const setSelectionRange = vi.spyOn(HTMLInputElement.prototype, 'setSelectionRange');
    try {
      fireEvent.click(renderButton({}));
      const input = (await screen.findByLabelText('직접 복사해 주세요')) as HTMLInputElement;

      expect(setSelectionRange).toHaveBeenCalledWith(0, LINK.length);
      expect(Math.max(...setSelectionRange.mock.invocationCallOrder)).toBeGreaterThan(
        Math.min(...select.mock.invocationCallOrder),
      );

      setSelectionRange.mockClear();
      fireEvent.blur(input);
      fireEvent.focus(input);
      expect(setSelectionRange).toHaveBeenCalledWith(0, LINK.length);
    } finally {
      select.mockRestore();
      setSelectionRange.mockRestore();
    }
  });

  it('복사가 거절돼도 수동 입력란으로 넘어간다', async () => {
    const writeText = vi.fn().mockRejectedValue(new DOMException('denied', 'NotAllowedError'));
    fireEvent.click(renderButton({ clipboard: { writeText } }));

    expect(((await screen.findByLabelText('직접 복사해 주세요')) as HTMLInputElement).value).toBe(LINK);
  });

  it('공유창에서 취소하면 아무것도 알리지 않는다', async () => {
    const share = vi.fn().mockRejectedValue(new DOMException('canceled', 'AbortError'));
    const button = renderButton({ share });
    fireEvent.click(button);

    await waitFor(() => expect(share).toHaveBeenCalled());
    await waitFor(() => expect(button).not.toBeDisabled());
    expect(screen.getByRole('status').textContent).toBe('');
    expect(screen.queryByRole('textbox')).toBeNull();
  });

  // 라이브 영역은 내용이 바뀌기 전에 DOM 에 있어야 스크린 리더가 읽는다 — 내용과 함께 삽입하면 일부가 무시한다(IP-4).
  it('클릭 전에도 알림 영역(status)이 비어 있는 채로 있다', () => {
    renderButton({});

    expect(screen.getByRole('status').textContent).toBe('');
  });

  it('방 ID 는 경로 조각으로 인코딩한다', () => {
    render(
      <InviteLinkButton roomId="a/b?c" roomName="방" gameName="티츄" env={{}} origin={ORIGIN} />,
    );
    fireEvent.click(screen.getByRole('button', { name: '초대 링크' }));

    return screen.findByLabelText('직접 복사해 주세요').then((input) => {
      expect((input as HTMLInputElement).value).toBe(`${ORIGIN}/rooms/a%2Fb%3Fc`);
    });
  });
});

/**
 * D-133 — 자리가 다 찬 대기실(봇 자리 포함)의 초대는 관전 초대다. 버튼은 그대로 두고 설명만 사실대로 바꾼다(IP-3).
 */
describe('InviteLinkButton — 자리가 다 찬 방 (D-133)', () => {
  // D-146 — 설명 문단을 없애고 버튼 하나만 둔다. 설명은 버튼 title(마우스 오버)로.
  it('full 이면 버튼 설명이 "링크를 받은 친구는 관전으로 들어와요"', () => {
    render(<InviteLinkButton roomId="r1" roomName="방" gameName="원카드" full env={{}} origin={ORIGIN} />);

    expect(screen.getByRole('button', { name: '초대 링크' })).toHaveAttribute(
      'title',
      '자리가 다 찼어요 — 링크를 받은 친구는 관전으로 들어와요',
    );
  });

  it('자리가 있으면 버튼 설명이 "친구가 바로 이 방에 들어와요" — 화면에 설명 문단은 없다', () => {
    render(<InviteLinkButton roomId="r1" roomName="방" gameName="원카드" env={{}} origin={ORIGIN} />);

    expect(screen.getByRole('button', { name: '초대 링크' })).toHaveAttribute(
      'title',
      '링크를 보내면 친구가 바로 이 방에 들어와요',
    );
    expect(screen.queryByText(/링크를 보내면/)).toBeNull();
  });
});

/**
 * D-133 — 주입 없이(실제 브라우저 경로) 공유창은 휴대폰·태블릿(굵은 포인터)에서만 연다. Web Share API 는 데스크톱 Chrome·Edge(Windows)·
 * Safari(macOS)에도 있어 그대로 두면 PC 에서 "링크 복사" 대신 OS 공유창이 뜬다(IP-2). jsdom 에는 `navigator.share`·`matchMedia` 가 없어 넣어 준다.
 */
describe('InviteLinkButton — 기본 경로는 휴대폰만 공유창 (D-133)', () => {
  const share = vi.fn().mockResolvedValue(undefined);
  const writeText = vi.fn().mockResolvedValue(undefined);

  function installBrowser(coarse: boolean) {
    Object.defineProperty(window, 'matchMedia', {
      value: vi.fn((query: string) => ({ matches: coarse && query === '(pointer: coarse)', media: query })),
      configurable: true,
    });
    Object.defineProperty(navigator, 'share', { value: share, configurable: true });
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
  }

  afterEach(() => {
    share.mockClear();
    writeText.mockClear();
    delete (window as { matchMedia?: unknown }).matchMedia;
    delete (navigator as { share?: unknown }).share;
    delete (navigator as { clipboard?: unknown }).clipboard;
  });

  it('데스크톱(마우스)이면 공유창이 있어도 링크를 복사한다', async () => {
    installBrowser(false);
    render(
      <>
        <InviteLinkButton roomId="r1" roomName="금요일 한 판" gameName="원카드" origin={ORIGIN} />
        <Toaster />
      </>,
    );

    fireEvent.click(screen.getByRole('button', { name: '초대 링크' }));

    expect(await screen.findByText('링크를 복사했어요')).toBeInTheDocument();
    expect(writeText).toHaveBeenCalledWith(LINK);
    expect(share).not.toHaveBeenCalled();
  });

  it('휴대폰(굵은 포인터)이면 공유창을 연다', async () => {
    installBrowser(true);
    render(
      <>
        <InviteLinkButton roomId="r1" roomName="금요일 한 판" gameName="원카드" origin={ORIGIN} />
        <Toaster />
      </>,
    );

    fireEvent.click(screen.getByRole('button', { name: '초대 링크' }));

    expect(await screen.findByText('초대 링크를 보냈어요')).toBeInTheDocument();
    expect(share).toHaveBeenCalledWith({ title: '원카드 같이 해요', text: '금요일 한 판', url: LINK });
    expect(writeText).not.toHaveBeenCalled();
  });
});

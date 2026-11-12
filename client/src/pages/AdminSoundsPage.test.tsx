import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AdminSoundsPage } from './AdminSoundsPage';
import { useAuthStore } from '@/features/auth/authStore';

/** D-265 — 관리 화면: 키마다 상태(올린 파일 / 기본 음성)·듣기·올리기·지우기. 관리자가 아니면 목록을 부르지 않는다. */

const { adminList, upload, remove, isAdmin, playVoice, loadVoiceClips } = vi.hoisted(() => ({
  adminList: vi.fn(),
  upload: vi.fn(),
  remove: vi.fn(),
  isAdmin: vi.fn<() => boolean | null>(),
  playVoice: vi.fn(),
  loadVoiceClips: vi.fn(() => Promise.resolve({})),
}));

vi.mock('@/api/sounds', () => ({ soundsApi: { adminList, upload, remove } }));
vi.mock('@/features/auth/useIsAdmin', () => ({ useIsAdmin: () => isAdmin() }));
vi.mock('@/features/sound/voiceClips', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/features/sound/voiceClips')>();
  return { ...actual, playVoice, loadVoiceClips };
});

const KEYS = [
  'yacht.made.SMALL_STRAIGHT',
  'yacht.made.FULL_HOUSE',
  'yacht.made.LARGE_STRAIGHT',
  'yacht.made.FOUR_OF_A_KIND',
  'yacht.made.YACHT',
  'onecard.call',
  'onecard.catch',
];

function renderPage() {
  return render(
    <MemoryRouter>
      <AdminSoundsPage />
    </MemoryRouter>,
  );
}

describe('AdminSoundsPage (D-265)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    isAdmin.mockReturnValue(true);
    adminList.mockResolvedValue({
      keys: KEYS,
      clips: [{ key: 'onecard.call', contentType: 'audio/mpeg', byteSize: 20480, updatedAt: 1731456000000 }],
      maxBytes: 512000,
    });
    upload.mockResolvedValue({ key: 'yacht.made.FULL_HOUSE', contentType: 'audio/mpeg', byteSize: 10, updatedAt: 1 });
    remove.mockResolvedValue(undefined);
    useAuthStore.setState({ token: 'tok', user: { userId: 1, username: '운영자' } as never });
    vi.spyOn(window, 'confirm').mockReturnValue(true);
  });

  it('키 7개를 한국어 이름으로 보이고, 올린 것과 기본 음성을 구분한다', async () => {
    renderPage();

    const uploaded = await screen.findByTestId('sound-onecard.call');
    await waitFor(() => expect(within(uploaded).getByText('올린 파일')).toBeInTheDocument());
    expect(uploaded).toHaveTextContent('원카드 · 원카드!');
    expect(uploaded).toHaveTextContent('mp3 · 20KB');
    expect(within(uploaded).getByRole('button', { name: '원카드 · 원카드! 지우기' })).toBeInTheDocument();

    const fallback = screen.getByTestId('sound-yacht.made.FULL_HOUSE');
    expect(fallback).toHaveTextContent('얏추 · 풀 하우스');
    expect(within(fallback).getByText('기본 음성')).toBeInTheDocument();
    expect(within(fallback).queryByRole('button', { name: /지우기/ })).toBeNull();

    expect(screen.getAllByRole('listitem')).toHaveLength(7);
    expect(adminList).toHaveBeenCalledWith('tok');
  });

  it('듣기는 음소거와 무관하게 그 키를 재생한다', async () => {
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: '얏추 · 요트 듣기' }));
    expect(playVoice).toHaveBeenCalledWith('yacht.made.YACHT', { force: true });
  });

  it('파일을 고르면 그 키로 올리고 목록·재생 목록을 새로 받는다', async () => {
    renderPage();
    const input = await screen.findByLabelText('얏추 · 풀 하우스 파일');
    const file = new File([new Uint8Array(100)], 'fullhouse.mp3', { type: 'audio/mpeg' });

    fireEvent.change(input, { target: { files: [file] } });

    await waitFor(() => expect(upload).toHaveBeenCalledWith('tok', 'yacht.made.FULL_HOUSE', file));
    await waitFor(() => expect(adminList).toHaveBeenCalledTimes(2));
    expect(loadVoiceClips).toHaveBeenCalledWith(true);
  });

  it('한도보다 큰 파일은 서버에 보내지 않는다', async () => {
    renderPage();
    const input = await screen.findByLabelText('얏추 · 풀 하우스 파일');
    const big = new File([new Uint8Array(512001)], 'big.mp3', { type: 'audio/mpeg' });

    fireEvent.change(input, { target: { files: [big] } });

    await Promise.resolve();
    expect(upload).not.toHaveBeenCalled();
  });

  it('지우기는 확인을 받고 그 키를 지운다', async () => {
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: '원카드 · 원카드! 지우기' }));

    await waitFor(() => expect(remove).toHaveBeenCalledWith('tok', 'onecard.call'));
    expect(window.confirm).toHaveBeenCalled();
    await waitFor(() => expect(loadVoiceClips).toHaveBeenCalledWith(true));
  });

  it('관리자가 아니면 안내만 보이고 목록을 부르지 않는다', () => {
    isAdmin.mockReturnValue(false);
    renderPage();

    expect(screen.getByText('관리자만 볼 수 있어요.')).toBeInTheDocument();
    expect(screen.queryByRole('list')).toBeNull();
    expect(adminList).not.toHaveBeenCalled();
  });
});

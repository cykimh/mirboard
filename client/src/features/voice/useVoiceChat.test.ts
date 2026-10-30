import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MAX_VOICE_MEMBERS, VOICE_FULL_CHECK_MS, useVoiceChat, type VoiceMessage, type VoiceSignal } from './useVoiceChat';
import { useToastStore } from '@/components/toast/toastStore';

/** jsdom 에는 WebRTC 가 없다 — 신호 흐름만 본다. */
class FakePC {
  static all: FakePC[] = [];
  localDescription: RTCSessionDescriptionInit | null = null;
  remoteDescription: RTCSessionDescriptionInit | null = null;
  transceivers: { direction: string; sender: { track: MediaStreamTrack | null; replaceTrack: ReturnType<typeof vi.fn> } }[] = [];
  added: RTCIceCandidateInit[] = [];
  closed = false;
  connectionState = 'new';
  onicecandidate: ((e: { candidate: { toJSON: () => RTCIceCandidateInit } | null }) => void) | null = null;
  ontrack: ((e: { streams: MediaStream[] }) => void) | null = null;
  onconnectionstatechange: (() => void) | null = null;
  constructor() {
    FakePC.all.push(this);
  }
  addTransceiver(_kind: string, init: { direction: string }) {
    const t = {
      direction: init.direction,
      sender: {
        track: null as MediaStreamTrack | null,
        replaceTrack: vi.fn(async (tr: MediaStreamTrack | null) => {
          t.sender.track = tr;
        }),
      },
    };
    this.transceivers.push(t);
    return t;
  }
  getTransceivers() {
    return this.transceivers;
  }
  async createOffer() {
    return { type: 'offer' as const, sdp: 'o' };
  }
  async createAnswer() {
    return { type: 'answer' as const, sdp: 'a' };
  }
  async setLocalDescription(d: RTCSessionDescriptionInit) {
    this.localDescription = d;
  }
  async setRemoteDescription(d: RTCSessionDescriptionInit) {
    this.remoteDescription = d;
    if (d.type === 'offer' && this.transceivers.length === 0) this.addTransceiver('audio', { direction: 'recvonly' });
  }
  async addIceCandidate(c: RTCIceCandidateInit) {
    this.added.push(c);
  }
  close() {
    this.closed = true;
  }
}

const track = { enabled: true, stop: vi.fn() } as unknown as MediaStreamTrack;
const getUserMedia = vi.fn(async () => ({ getAudioTracks: () => [track], getTracks: () => [track] }) as unknown as MediaStream);

function setup(myUserId = 5) {
  const sent: VoiceSignal[] = [];
  let handler: ((m: VoiceMessage) => void) | null = null;
  const hook = renderHook(() =>
    useVoiceChat({
      myUserId,
      connected: true,
      send: (s) => sent.push(s),
      subscribe: (h) => {
        handler = h;
        return () => {
          handler = null;
        };
      },
    }),
  );
  const receive = async (m: VoiceMessage) => {
    await act(async () => {
      handler!(m);
      await Promise.resolve();
      await Promise.resolve();
    });
  };
  return { hook, sent, receive };
}

beforeEach(() => {
  FakePC.all = [];
  vi.stubGlobal('RTCPeerConnection', FakePC);
  Object.defineProperty(navigator, 'mediaDevices', { value: { getUserMedia }, configurable: true });
  getUserMedia.mockClear();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('useVoiceChat — 신호 흐름 (D-155)', () => {
  it('참여하면 방에 JOIN 을 알리고, 마이크는 묻지 않는다', () => {
    const { hook, sent } = setup();
    act(() => hook.result.current.join());

    expect(hook.result.current.joined).toBe(true);
    expect(sent).toEqual([{ type: 'JOIN' }]);
    expect(getUserMedia).not.toHaveBeenCalled();
  });

  it('나보다 큰 id 가 들어오면 내가 OFFER 를 보낸다(작은 쪽이 보낸다)', async () => {
    const { hook, sent, receive } = setup(5);
    act(() => hook.result.current.join());

    await receive({ type: 'JOIN', from: 9, data: null });

    expect(sent).toContainEqual({ type: 'JOIN' }); // 새로 온 사람이 나를 알게 다시 알린다.
    const offer = sent.find((s) => s.type === 'OFFER');
    expect(offer).toMatchObject({ to: 9 });
    expect(JSON.parse(offer!.data!)).toMatchObject({ type: 'offer' });
    expect(FakePC.all).toHaveLength(1);
    expect(FakePC.all[0].transceivers[0].direction).toBe('sendrecv');
  });

  it('나보다 작은 id 가 들어오면 OFFER 를 기다린다 — 오면 ANSWER', async () => {
    const { hook, sent, receive } = setup(5);
    act(() => hook.result.current.join());

    await receive({ type: 'JOIN', from: 2, data: null });
    expect(sent.find((s) => s.type === 'OFFER')).toBeUndefined();

    await receive({ type: 'OFFER', from: 2, data: JSON.stringify({ type: 'offer', sdp: 'o' }) });
    const answer = sent.find((s) => s.type === 'ANSWER');
    expect(answer).toMatchObject({ to: 2 });
    expect(JSON.parse(answer!.data!)).toMatchObject({ type: 'answer' });
    expect(FakePC.all[0].transceivers[0].direction).toBe('sendrecv');
  });

  it('원격 설명 전에 온 ICE 후보는 모아 두었다가 붙인다', async () => {
    const { hook, receive } = setup(5);
    act(() => hook.result.current.join());

    await receive({ type: 'ICE', from: 2, data: JSON.stringify({ candidate: 'c1' }) });
    await receive({ type: 'OFFER', from: 2, data: JSON.stringify({ type: 'offer', sdp: 'o' }) });

    expect(FakePC.all[0].added).toEqual([{ candidate: 'c1' }]);
  });

  it('참여하지 않았으면 신호를 무시한다', async () => {
    const { sent, receive } = setup(5);
    await receive({ type: 'JOIN', from: 9, data: null });
    expect(sent).toEqual([]);
    expect(FakePC.all).toHaveLength(0);
  });

  it('상대가 LEAVE 하면 연결을 닫는다', async () => {
    const { hook, receive } = setup(5);
    act(() => hook.result.current.join());
    await receive({ type: 'JOIN', from: 9, data: null });

    await receive({ type: 'LEAVE', from: 9, data: null });

    expect(FakePC.all[0].closed).toBe(true);
    expect(hook.result.current.members).toEqual([]);
  });

  it('마이크를 켜면 그때 권한을 묻고 연결마다 내 소리를 싣는다 — 다시 누르면 음소거', async () => {
    const { hook, receive } = setup(5);
    act(() => hook.result.current.join());
    await receive({ type: 'JOIN', from: 9, data: null });

    await act(async () => {
      await hook.result.current.toggleMic();
    });
    expect(getUserMedia).toHaveBeenCalledWith({ audio: true });
    expect(FakePC.all[0].transceivers[0].sender.replaceTrack).toHaveBeenCalledWith(track);
    expect(hook.result.current.micOn).toBe(true);

    await act(async () => {
      await hook.result.current.toggleMic();
    });
    expect(track.enabled).toBe(false);
    expect(hook.result.current.micOn).toBe(false);
  });

  it('나가면 LEAVE 를 알리고 연결·마이크를 정리한다', async () => {
    const { hook, sent, receive } = setup(5);
    act(() => hook.result.current.join());
    await receive({ type: 'JOIN', from: 9, data: null });
    await act(async () => {
      await hook.result.current.toggleMic();
    });

    act(() => hook.result.current.leave());

    expect(sent.at(-1)).toEqual({ type: 'LEAVE' });
    expect(FakePC.all[0].closed).toBe(true);
    expect(track.stop).toHaveBeenCalled();
    expect(hook.result.current.joined).toBe(false);
  });

  it('연결이 실패한 상대는 failed 로 보인다', async () => {
    const { hook, receive } = setup(5);
    act(() => hook.result.current.join());
    await receive({ type: 'JOIN', from: 9, data: null });

    act(() => {
      FakePC.all[0].connectionState = 'failed';
      FakePC.all[0].onconnectionstatechange?.();
    });

    expect(hook.result.current.peerState[9]).toBe('failed');
  });

  // D-157 — 마이크를 못 쓰면 머리 줄에 글을 끼우지 않고 토스트(오류)로 알린다.
  it('마이크 권한이 거절되면 오류 토스트를 띄우고 마이크는 꺼진 채다', async () => {
    useToastStore.getState().clear();
    getUserMedia.mockRejectedValueOnce(new DOMException('denied', 'NotAllowedError'));
    const { hook } = setup(5);
    act(() => hook.result.current.join());

    await act(async () => {
      await hook.result.current.toggleMic();
    });

    expect(hook.result.current.micOn).toBe(false);
    expect(useToastStore.getState().toasts).toEqual([
      expect.objectContaining({ message: '마이크를 쓸 수 없어요 — 브라우저에서 마이크를 허용해 주세요', tone: 'error' }),
    ]);
    useToastStore.getState().clear();
  });

  // D-158 — 메시라 인원이 늘면 폰마다 연결이 늘어 부담이 크다. 동시 참여는 4명까지 — 들어가 보니 이미 4명이면 스스로 나간다.
  describe('인원 제한 (D-158)', () => {
    beforeEach(() => {
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
      useToastStore.getState().clear();
    });
    afterEach(() => {
      useToastStore.getState().clear();
      vi.useRealTimers();
    });

    it('참여 직후 이미 4명이 있으면 나가고 꽉 찼다고 알린다', async () => {
      expect(MAX_VOICE_MEMBERS).toBe(4);
      const { hook, sent, receive } = setup(50);
      act(() => hook.result.current.join());
      for (const from of [9, 10, 11, 12]) await receive({ type: 'JOIN', from, data: null });

      act(() => vi.advanceTimersByTime(VOICE_FULL_CHECK_MS));

      expect(hook.result.current.joined).toBe(false);
      expect(sent.at(-1)).toEqual({ type: 'LEAVE' });
      expect(useToastStore.getState().toasts.map((t) => t.message)).toEqual(['음성 대화는 4명까지예요 — 지금 꽉 찼어요']);
    });

    it('나까지 4명이면 그대로 남는다', async () => {
      const { hook, receive } = setup(50);
      act(() => hook.result.current.join());
      for (const from of [9, 10, 11]) await receive({ type: 'JOIN', from, data: null });

      act(() => vi.advanceTimersByTime(VOICE_FULL_CHECK_MS));

      expect(hook.result.current.joined).toBe(true);
      expect(useToastStore.getState().toasts).toEqual([]);
    });

    it('이미 들어와 있던 사람은 뒤에 사람이 늘어도 나가지 않는다 — 판단은 참여 직후 한 번', async () => {
      const { hook, receive } = setup(50);
      act(() => hook.result.current.join());
      act(() => vi.advanceTimersByTime(VOICE_FULL_CHECK_MS));
      for (const from of [9, 10, 11, 12]) await receive({ type: 'JOIN', from, data: null });

      expect(hook.result.current.joined).toBe(true);
    });
  });
});

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { VOICE_CLIPS, loadVoiceClips, playVoice, resetVoiceClipsForTest } from './voiceClips';

/** D-265 — 올린 파일이 있으면 그것을, 없으면 브라우저 읽어 주기, 그것도 없으면 조용히. 음소거를 따른다. */

const versions = vi.hoisted(() => vi.fn());
vi.mock('@/api/sounds', () => ({
  soundsApi: { versions },
  soundSrc: (key: string, v: number) => `/sounds/${key}?v=${v}`,
}));

class FakeAudio {
  static made: FakeAudio[] = [];
  static playError: { name: string } | null = null;
  preload = '';
  volume = 1;
  currentTime = 5;
  play = vi.fn(() => (FakeAudio.playError ? Promise.reject(FakeAudio.playError) : Promise.resolve()));
  constructor(public src: string) {
    FakeAudio.made.push(this);
  }
}

class FakeUtterance {
  lang = '';
  rate = 1;
  pitch = 1;
  volume = 1;
  voice: unknown = null;
  constructor(public text: string) {}
}

const synth = { speak: vi.fn(), cancel: vi.fn(), getVoices: vi.fn(() => []) };

/** 비동기(목록 받기 → 재생)를 끝까지 흘린다. */
async function settle() {
  for (let i = 0; i < 5; i++) await Promise.resolve();
}

function played(key: string) {
  return FakeAudio.made.filter((a) => a.src.startsWith(`/sounds/${key}?`) && a.play.mock.calls.length > 0);
}

describe('playVoice (D-265)', () => {
  beforeEach(() => {
    resetVoiceClipsForTest();
    localStorage.clear();
    FakeAudio.made = [];
    FakeAudio.playError = null;
    versions.mockReset();
    synth.speak.mockClear();
    synth.cancel.mockClear();
    vi.stubGlobal('Audio', FakeAudio);
    vi.stubGlobal('SpeechSynthesisUtterance', FakeUtterance);
    vi.stubGlobal('speechSynthesis', synth);
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it('올린 파일이 있으면 버전을 붙인 주소로 재생하고 읽어 주기는 쓰지 않는다', async () => {
    versions.mockResolvedValue({ 'onecard.call': 1700 });

    playVoice('onecard.call');
    await settle();

    const [audio] = played('onecard.call');
    expect(audio.src).toBe('/sounds/onecard.call?v=1700');
    expect(audio.currentTime).toBe(0);
    expect(synth.speak).not.toHaveBeenCalled();
  });

  it('올린 파일이 없으면 한국어로 문구를 읽는다', async () => {
    versions.mockResolvedValue({ 'onecard.call': 1700 });

    playVoice('yacht.made.FULL_HOUSE');
    await settle();

    expect(FakeAudio.made.every((a) => a.play.mock.calls.length === 0)).toBe(true);
    expect(synth.speak).toHaveBeenCalledTimes(1);
    const u = synth.speak.mock.calls[0][0] as FakeUtterance;
    expect(u.text).toBe('풀하우스!');
    expect(u.lang).toBe('ko-KR');
  });

  it('목록을 못 받아도 읽어 주기로 넘어가고, 다음에 다시 받는다', async () => {
    versions.mockRejectedValueOnce(new Error('offline'));
    playVoice('onecard.catch');
    await settle();
    expect((synth.speak.mock.calls[0][0] as FakeUtterance).text).toBe('잡기!');

    versions.mockResolvedValue({ 'onecard.catch': 3 });
    playVoice('onecard.catch');
    await settle();
    expect(played('onecard.catch')).toHaveLength(1);
  });

  it('목록은 한 번만 받는다 — 강제로 다시 받으면 바뀐 버전을 쓴다', async () => {
    versions.mockResolvedValue({ 'onecard.call': 1 });
    playVoice('onecard.call');
    await settle();
    playVoice('onecard.call');
    await settle();
    expect(versions).toHaveBeenCalledTimes(1);

    versions.mockResolvedValue({ 'onecard.call': 2 });
    await loadVoiceClips(true);
    playVoice('onecard.call');
    await settle();
    expect(FakeAudio.made.some((a) => a.src === '/sounds/onecard.call?v=2' && a.play.mock.calls.length === 1)).toBe(true);
  });

  it('효과음을 껐으면 아무 소리도 내지 않는다 — 미리 듣기(force)는 낸다', async () => {
    localStorage.setItem('mirboard.sfxMuted', '1');
    versions.mockResolvedValue({});

    playVoice('onecard.call');
    await settle();
    expect(versions).not.toHaveBeenCalled();
    expect(synth.speak).not.toHaveBeenCalled();

    playVoice('onecard.call', { force: true });
    await settle();
    expect(synth.speak).toHaveBeenCalledTimes(1);
  });

  it('읽어 주기를 못 쓰는 브라우저에서는 조용히 건너뛴다', async () => {
    vi.unstubAllGlobals();
    vi.stubGlobal('Audio', FakeAudio);
    versions.mockResolvedValue({});

    expect(() => playVoice('yacht.made.YACHT')).not.toThrow();
    await settle();
    expect(FakeAudio.made).toHaveLength(0);
  });

  it('브라우저가 재생을 막으면(사용자 동작 전) 조용히 넘어간다 — 파일을 못 읽으면 읽어 주기로', async () => {
    versions.mockResolvedValue({ 'onecard.call': 1 });
    FakeAudio.playError = { name: 'NotAllowedError' };
    playVoice('onecard.call');
    await settle();
    expect(synth.speak).not.toHaveBeenCalled();

    FakeAudio.playError = { name: 'NotSupportedError' };
    playVoice('onecard.call');
    await settle();
    expect((synth.speak.mock.calls[0][0] as FakeUtterance).text).toBe('원카드!');
  });

  it('delayMs 만큼 늦게 낸다(합성 알림음 뒤에 붙인다)', async () => {
    vi.useFakeTimers();
    versions.mockResolvedValue({});

    playVoice('yacht.made.YACHT', { delayMs: 300 });
    await vi.advanceTimersByTimeAsync(299);
    expect(synth.speak).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    expect((synth.speak.mock.calls[0][0] as FakeUtterance).text).toBe('요트!');
  });

  it('키 목록은 서버 허용 목록과 같은 7개다', () => {
    expect(VOICE_CLIPS.map((c) => c.key).sort()).toEqual([
      'onecard.call',
      'onecard.catch',
      'yacht.made.FOUR_OF_A_KIND',
      'yacht.made.FULL_HOUSE',
      'yacht.made.LARGE_STRAIGHT',
      'yacht.made.SMALL_STRAIGHT',
      'yacht.made.YACHT',
    ]);
  });
});

import { soundSrc, soundsApi } from '@/api/sounds';

/**
 * D-265 — 게임 음성("풀하우스!"·"원카드!"). 운영자가 올린 파일이 있으면 그것을, 없으면 브라우저 읽어 주기(`speechSynthesis`, ko-KR)로
 * 문구를 읽고, 그것도 없으면 조용히 건너뛴다. 음성 파일은 저장소에 싣지 않는다 — 운영자가 `/admin/sounds` 에서 올린다.
 *
 * 키 목록은 서버 허용 목록(`SoundClipKey`)과 같아야 한다. 얏추 키의 끝은 족보 칸 이름(`Category`)이다.
 */
export const VOICE_CLIPS = [
  { key: 'yacht.made.SMALL_STRAIGHT', label: '얏추 · 스몰 스트레이트', phrase: '스몰 스트레이트!' },
  { key: 'yacht.made.FULL_HOUSE', label: '얏추 · 풀 하우스', phrase: '풀하우스!' },
  { key: 'yacht.made.LARGE_STRAIGHT', label: '얏추 · 라지 스트레이트', phrase: '라지 스트레이트!' },
  { key: 'yacht.made.FOUR_OF_A_KIND', label: '얏추 · 포 카드', phrase: '포카드!' },
  { key: 'yacht.made.YACHT', label: '얏추 · 요트', phrase: '요트!' },
  { key: 'onecard.call', label: '원카드 · 원카드!', phrase: '원카드!' },
  { key: 'onecard.catch', label: '원카드 · 잡기!', phrase: '잡기!' },
] as const;

export type VoiceKey = (typeof VOICE_CLIPS)[number]['key'];

/** 효과음 끄기(`useSfx` 와 같은 키) — 음성도 따른다. */
const MUTE_KEY = 'mirboard.sfxMuted';
/** 오래 열어 둔 화면도 바뀐 파일을 따라가게, 이 시간이 지나면 다음 재생 때 목록을 다시 받는다. */
const REFRESH_MS = 5 * 60 * 1000;
const VOLUME = 0.85;

let versions: Record<string, number> | null = null;
let loadedAt = 0;
let loading: Promise<Record<string, number>> | null = null;
const audios = new Map<string, HTMLAudioElement>();

function isMuted(): boolean {
  try {
    return window.localStorage.getItem(MUTE_KEY) === '1';
  } catch {
    return false;
  }
}

/**
 * 올라와 있는 클립 목록(키 → 버전)을 받는다. 한 번 받으면 기억하고, `force` 면 다시 받는다(관리 화면에서 올리거나 지운 뒤).
 * 실패하면 빈 목록 — 기본 음성으로 넘어가고 다음에 다시 시도한다.
 */
export function loadVoiceClips(force = false): Promise<Record<string, number>> {
  if (!force && versions) return Promise.resolve(versions);
  if (!force && loading) return loading;
  const request: Promise<Record<string, number>> = soundsApi
    .versions()
    .then((v) => {
      versions = v;
      loadedAt = Date.now();
      return v;
    })
    .catch(() => versions ?? {})
    .finally(() => {
      if (loading === request) loading = null;
    });
  loading = request;
  return request;
}

function audioFor(key: string, version: number): HTMLAudioElement | null {
  const src = soundSrc(key, version);
  const cached = audios.get(src);
  if (cached) return cached;
  try {
    const audio = new Audio(src);
    audio.preload = 'auto';
    audio.volume = VOLUME;
    audios.set(src, audio);
    return audio;
  } catch {
    return null;
  }
}

/** 브라우저 읽어 주기. 못 쓰는 환경이면 아무것도 하지 않는다. */
function speak(phrase: string): void {
  try {
    if (typeof window === 'undefined' || !('speechSynthesis' in window)) return;
    if (typeof SpeechSynthesisUtterance === 'undefined') return;
    const synth = window.speechSynthesis;
    // 외침은 밀리면 뜻이 없다 — 앞에 밀린 것을 지우고 지금 것만 읽는다.
    synth.cancel();
    const u = new SpeechSynthesisUtterance(phrase);
    u.lang = 'ko-KR';
    u.rate = 1.15;
    u.pitch = 1.1;
    u.volume = VOLUME;
    const korean = synth.getVoices?.().find((v) => v.lang?.toLowerCase().startsWith('ko'));
    if (korean) u.voice = korean;
    synth.speak(u);
  } catch {
    // 읽어 주기 실패 — silent.
  }
}

async function playNow(key: VoiceKey, force: boolean): Promise<void> {
  if (!force && isMuted()) return;
  const clip = VOICE_CLIPS.find((c) => c.key === key);
  if (!clip) return;
  const stale = versions !== null && Date.now() - loadedAt > REFRESH_MS;
  // 목록이 있으면 기다리지 않는다(오래됐으면 뒤에서 다시 받아 다음 재생부터 쓴다).
  const known = versions ?? (await loadVoiceClips());
  if (stale) void loadVoiceClips(true);
  const version = known[key];
  if (version === undefined) {
    speak(clip.phrase);
    return;
  }
  const audio = audioFor(key, version);
  if (!audio) {
    speak(clip.phrase);
    return;
  }
  // 같은 게임의 다른 클립을 미리 받아 둔다(첫 재생이 늦지 않게).
  const prefix = key.slice(0, key.indexOf('.') + 1);
  for (const [k, v] of Object.entries(known)) {
    if (k !== key && k.startsWith(prefix)) audioFor(k, v);
  }
  try {
    audio.currentTime = 0;
    await audio.play();
  } catch (e) {
    // 사용자 동작 전이라 브라우저가 막았으면 조용히 넘어간다. 그 밖(파일이 지워졌거나 못 읽음)은 기본 음성으로 읽고 목록을 다시 받는다.
    if ((e as { name?: string } | null)?.name === 'NotAllowedError') return;
    // 한 번 실패한 요소는 같은 src 로 다시 재생해도 바로 실패한다(미디어 오류 상태가 남는다) — 버려서 다음엔 새로 받게 한다.
    audios.delete(soundSrc(key, version));
    speak(clip.phrase);
    void loadVoiceClips(true);
  }
}

/**
 * 음성 한마디. 효과음 끄기를 따른다(`force` 는 관리 화면의 미리 듣기). `delayMs` 는 합성 효과음 뒤에 붙일 때의 틈이다.
 * 어떤 실패도 밖으로 던지지 않는다.
 */
export function playVoice(key: VoiceKey, options: { delayMs?: number; force?: boolean } = {}): void {
  const { delayMs = 0, force = false } = options;
  const run = () => {
    playNow(key, force).catch(() => {});
  };
  if (delayMs > 0) window.setTimeout(run, delayMs);
  else run();
}

/** 테스트 전용 — 기억한 목록·오디오를 지운다. */
export function resetVoiceClipsForTest(): void {
  versions = null;
  loadedAt = 0;
  loading = null;
  audios.clear();
}

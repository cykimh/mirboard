import type { useVoiceChat } from './useVoiceChat';

type Voice = ReturnType<typeof useVoiceChat>;

interface Props {
  voice: Voice;
  /** 게임판 머리 줄 버튼 모양(원카드 `.oc-badge`, 스컬킹 `.sk-badge`, 티츄 shadcn outline). */
  buttonClassName: string;
}

/**
 * D-155·D-156 — 음성 대화 버튼 둘(세 게임판 공통). 🎧 참여(듣기 — 마이크를 묻지 않는다)·🎙 마이크(참여 중에만, 처음 켤 때 권한).
 * 마이크를 못 쓰면 이유는 토스트로 뜬다(D-157 — 머리 줄에 글을 끼우면 버튼 줄이 일그러졌다).
 */
export function VoiceControls({ voice, buttonClassName }: Props) {
  return (
    <>
      <button
        type="button"
        className={buttonClassName}
        onClick={voice.joined ? voice.leave : voice.join}
        aria-label="음성 대화"
        aria-pressed={voice.joined}
        title={voice.joined ? '음성 대화 나가기' : '음성 대화 참여(듣기) — 마이크는 🎙 로 따로 켭니다'}
      >
        🎧
      </button>
      <button
        type="button"
        className={buttonClassName}
        onClick={() => void voice.toggleMic()}
        disabled={!voice.joined}
        aria-label="마이크"
        aria-pressed={voice.micOn}
        title={voice.micOn ? '마이크 끄기' : '마이크 켜기'}
      >
        {voice.micOn ? '🎙' : '🎙✕'}
      </button>
    </>
  );
}

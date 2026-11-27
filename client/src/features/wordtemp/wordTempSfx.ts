import type { TableCueCall } from '@/features/sound/tableSfx';
import type { GuessesRevealedPayload, HintRevealedPayload, HintScoredPayload, TurnEndedPayload } from '@/types/wordtemp';
import type { WordTempRoomState } from './wordTempStore';

/** 남이 맞힌 정답은 내 것보다 작게. */
export const OTHERS_CORRECT_GAIN = 0.6;
/** 남이 낸 힌트의 채점음은 내 것보다 작게. */
export const OTHERS_HINT_GAIN = 0.6;

/**
 * 낱말 온도 공개 이벤트 → 효과음(D-267 방식 — 실제로 적용한 이벤트에서만, 스냅샷·첫 렌더는 울리지 않는다). 순수 함수.
 * 소리 톤은 공용 `tableSfx`(D-271 — 둥근 사인·마림바풍, 흰 잡음·sawtooth·square 없음, `sfxOut` 출구)의 `temp.*` 를 쓴다.
 *
 * <ul>
 *   <li>`HINT_SCORED` — 힌트가 공개될 때마다 온도만큼 높은 한 음(`temp.scored` — D-320 뒤로 온도는 비밀이 아니다).</li>
 *   <li>`HINT_REVEALED` — 힌트 단계 끝: 기준을 넘겼으면 수은주가 차오르는 소리(`temp.reveal`), 못 넘겼으면 식는 소리(`temp.wrong`).</li>
 *   <li>`GUESSES_REVEALED` — 누가 맞혔으면 `temp.correct`(내가 맞혔으면 또렷하게), 모두 틀렸으면 `temp.wrong`.</li>
 *   <li>`TURN_ENDED` — 정답이 드러나는 세 음(`temp.answer`). 누가 맞혀서 끝났으면 조용히(이미 정답 소리가 났다).</li>
 *   <li>`SEAT_OUT`·`MATCH_ENDED` — 공용 소리.</li>
 * </ul>
 */
export function wordTempCues(
  envelope: { type: string; payload: unknown },
  before: WordTempRoomState,
  after: WordTempRoomState,
): TableCueCall[] {
  const me = after.mySeat;
  switch (envelope.type) {
    case 'HINT_SCORED': {
      // D-320 — 힌트와 온도는 모두가 본다: 온도만큼 높은 한 음(남이 낸 것은 조금 작게).
      const p = envelope.payload as HintScoredPayload;
      if (after.hints === before.hints) return [];
      return [{ cue: 'temp.scored', level: p.temp, gain: p.seat === me ? 1 : OTHERS_HINT_GAIN }];
    }
    case 'HINT_REVEALED': {
      // 힌트 단계 끝 — 기준을 넘겼으면 수은주가 차오르는 소리, 못 넘겼으면 식는 소리.
      const p = envelope.payload as HintRevealedPayload;
      if (after.revealed.length === before.revealed.length) return [];
      return p.floorMet ? [{ cue: 'temp.reveal', level: p.temp }] : [{ cue: 'temp.wrong', gain: 0.6 }];
    }
    case 'GUESSES_REVEALED': {
      const p = envelope.payload as GuessesRevealedPayload;
      if (after.attempts.length === before.attempts.length) return [];
      const correct = p.guesses.filter((g) => g.correct);
      if (correct.length === 0) return [{ cue: 'temp.wrong', gain: 0.8 }];
      return [{ cue: 'temp.correct', gain: correct.some((g) => g.seat === me) ? 1 : OTHERS_CORRECT_GAIN }];
    }
    case 'TURN_ENDED': {
      const p = envelope.payload as TurnEndedPayload;
      if (after.history.length === before.history.length || p.reason === 'SOLVED') return [];
      return [{ cue: 'temp.answer' }];
    }
    case 'SEAT_OUT':
      return [{ cue: 'seat.out', gain: 0.8 }];
    case 'MATCH_ENDED': {
      if (me < 0 || !after.result || before.result) return [];
      const mine = after.result.standings.find((s) => s.seat === me);
      if (!mine) return [];
      if (after.result.draw) return [{ cue: 'match.draw' }];
      return [{ cue: mine.rank === 1 && !mine.out ? 'match.win' : 'match.lose' }];
    }
    default:
      return [];
  }
}

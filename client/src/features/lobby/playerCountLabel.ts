import type { GameSummary } from '../../types/api';

/**
 * D-196 — 메인 게임 카드의 인원 표기. 서버 카탈로그 `playerCounts`(할 수 있는 인원, 오름차순)만 보고 정한다 — 게임 이름을 모른다.
 * 한 가지면 "4인", 이어지면 "2~4인", 끊기면 "2·4인"(오목: 모드가 정원을 2 또는 4로 정한다). 필드가 없으면(구 서버·픽스처)
 * minPlayers..maxPlayers 로 본다.
 */
export function playerCountLabel(game: Pick<GameSummary, 'minPlayers' | 'maxPlayers' | 'playerCounts'>): string {
  const counts = game.playerCounts && game.playerCounts.length > 0 ? game.playerCounts : null;
  if (!counts) {
    return game.minPlayers === game.maxPlayers
      ? `${game.maxPlayers}인`
      : `${game.minPlayers}~${game.maxPlayers}인`;
  }
  const first = counts[0];
  const last = counts[counts.length - 1];
  if (counts.length === 1) return `${first}인`;
  const contiguous = counts.every((n, i) => n === first + i);
  return contiguous ? `${first}~${last}인` : `${counts.join('·')}인`;
}

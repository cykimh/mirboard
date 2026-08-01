/**
 * Phase 18(#3, D-74) — 게임별 외부 규칙 위키 링크. 게임 카탈로그 API 는
 * 변경하지 않고(계약 불변) 클라 측에서만 게임 id → 위키 URL 을 매핑한다.
 * 맵에 없는 게임은 "자세히" 링크를 노출하지 않는다.
 */
const GAME_WIKI_URL: Record<string, string> = {
  tichu: 'https://en.wikipedia.org/wiki/Tichu',
  skull_king: 'https://en.wikipedia.org/wiki/Skull_King',
};

export function gameWikiUrl(gameId: string): string | undefined {
  return GAME_WIKI_URL[gameId.toLowerCase()];
}

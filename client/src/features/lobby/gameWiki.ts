/**
 * Phase 18(#3, D-74) — 게임별 외부 규칙 위키 링크. 게임 카탈로그 API 는
 * 변경하지 않고(계약 불변) 클라 측에서만 게임 id → 위키 URL 을 매핑한다.
 * 맵에 없는 게임은 "자세히" 링크를 노출하지 않는다.
 */
const GAME_WIKI_URL: Record<string, string> = {
  tichu: 'https://en.wikipedia.org/wiki/Tichu',
  // 스컬킹은 영문 위키백과에 문서가 없다(2026-08 확인, `Skull_King` → 404). BGG 를 쓴다.
  skull_king: 'https://boardgamegeek.com/boardgame/150145/skull-king',
  // 원카드는 한국식 하우스 룰 게임이라 영문 문서가 없다. 나무위키 '원카드' 문서(퍼센트 인코딩).
  one_card: 'https://namu.wiki/w/%EC%9B%90%EC%B9%B4%EB%93%9C',
};

export function gameWikiUrl(gameId: string): string | undefined {
  return GAME_WIKI_URL[gameId.toLowerCase()];
}

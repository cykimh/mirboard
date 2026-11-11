// D-188 — 게임 아이콘 비교 그림(docs/design/icons/index.html)을 GameIcon 컴포넌트에서 그대로 뽑는다.
// 아이콘을 고치면 다시 돌린다: (client 폴더에서) npx vite-node scripts/render-game-icons.tsx
import { writeFileSync } from 'node:fs';
import { renderToStaticMarkup } from 'react-dom/server';
import { GAME_ICON_IDS, GameIcon } from '../src/components/GameIcon';

const NAMES: Record<string, string> = {
  TICHU: '티츄',
  SKULL_KING: '스컬킹',
  ONE_CARD: '원카드',
  YACHT: '요트',
  OMOK: '오목',
  CLOUD_ROUTE: '구름항로',
  ARTILLERY: '씨앗포',
  UNKNOWN: '기본(모르는 게임)',
};
const SIZES = [16, 24, 44];
const ids = [...GAME_ICON_IDS, 'UNKNOWN'];
const icon = (id: string, size: number) => renderToStaticMarkup(<GameIcon gameId={id} size={size} />);

const panel = (theme: 'light' | 'dark') => `
<section class="panel ${theme}">
  <h2>${theme === 'light' ? '라이트' : '다크'}</h2>
  <div class="hero">${ids.map((id) => `<figure>${icon(id, 96)}<figcaption>${NAMES[id]}</figcaption></figure>`).join('')}</div>
  <table>
    <thead><tr><th>크기</th>${ids.map((id) => `<th>${NAMES[id]}</th>`).join('')}</tr></thead>
    <tbody>${SIZES.map((s) => `<tr><th>${s}px</th>${ids.map((id) => `<td>${icon(id, s)}</td>`).join('')}</tr>`).join('')}</tbody>
  </table>
  <div class="tabs">${GAME_ICON_IDS.map((id) => `<span class="tab">${icon(id, 16)}${NAMES[id]}</span>`).join('')}</div>
</section>`;

const html = `<!doctype html>
<html lang="ko">
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>Mirboard 게임 아이콘 세트 (D-188)</title>
<style>
  body { margin: 0; font-family: system-ui, 'Apple SD Gothic Neo', sans-serif; background: #fff; }
  .panel { padding: 24px 28px 32px; min-width: max-content; }
  .light { background: #ffffff; color: #0f172a; }
  .dark { background: #0b1220; color: #e2e8f0; }
  h1 { margin: 0; padding: 20px 28px 0; font-size: 18px; color: #0f172a; }
  p.note { margin: 6px 28px 14px; font-size: 13px; color: #64748b; }
  h2 { margin: 0 0 14px; font-size: 14px; opacity: 0.7; }
  .hero { display: flex; flex-wrap: wrap; gap: 18px; margin-bottom: 20px; }
  figure { margin: 0; text-align: center; font-size: 12px; }
  figcaption { margin-top: 6px; }
  table { border-collapse: collapse; font-size: 12px; }
  th, td { padding: 6px 12px; text-align: center; font-weight: 500; }
  td svg { margin: 0 auto; }
  .tabs { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 16px; }
  .tab { display: inline-flex; align-items: center; gap: 6px; padding: 4px 12px; border: 1px solid; border-radius: 6px; font-size: 14px; }
  .dark .tab { border-color: #334155; }
  .light .tab { border-color: #cbd5e1; }
</style>
<h1>Mirboard 게임 아이콘 세트 (D-188)</h1>
<p class="note">48 그리드 · 둥근 타일 · 왼쪽 위 광원 · 외곽선 2 · 크림색 그림 + 강조색 하나. 28px 아래는 잔 장식을 뺀 단순형.
이 파일은 <code>client/scripts/render-game-icons.tsx</code> 가 컴포넌트에서 뽑는다(손으로 고치지 않는다).</p>
${panel('light')}
${panel('dark')}
</html>
`;

writeFileSync(new URL('../../docs/design/icons/index.html', import.meta.url), html);
console.log(`docs/design/icons/index.html ${html.length}B`);

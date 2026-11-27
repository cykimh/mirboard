// 5단계(D-322) — 일관성: 4단계의 A·B 두 번(같은 쌍을 다르게 섞은 묶음으로 채점)을 견준다. Claude 를 부르지 않는다.
// 표 v1 때는 정답 5% 만 다시 채점했지만 표 v2 는 **모든 쌍을 두 번** 매겼으므로 전체가 표본이다.
// 기준(설계서 §5.2): 차이 10 이하가 90% 이상. 산출물 data/consistency.txt
import fs from 'node:fs';
import path from 'node:path';
import { DATA } from './lib.mjs';
import { readScoreRows } from './04-score.mjs';
import { bandOf } from './06-build.mjs';

let n = 0, within10 = 0, within5 = 0, sameBand = 0, flip = 0, sumAbs = 0, withC = 0, cMovedFar = 0;
const worst = [];
for (const [answer, hint, final, a, b, c] of readScoreRows()) {
  const [x, y] = [Number(a), Number(b)];
  if (c !== '' && c !== undefined) withC++;
  if ((x < 0) !== (y < 0)) { flip++; continue; }
  if (x < 0) continue;
  n++;
  const d = Math.abs(x - y);
  sumAbs += d;
  if (d <= 10) within10++;
  if (d <= 5) within5++;
  if (bandOf(x) === bandOf(y)) sameBand++;
  if (c !== '' && c !== undefined && Math.abs(Number(final) - (x + y) / 2) > 5) cMovedFar++;
  if (d > 25) worst.push({ answer, hint, x, y, c, final, d });
}
worst.sort((p, q) => q.d - p.d);
const pct = (v) => ((100 * v) / Math.max(1, n)).toFixed(1) + '%';
const report = [
  '낱말 온도 표 v2 일관성 — 모든 쌍을 서로 다른 묶음으로 두 번(A·B) 채점한 차이',
  `견준 쌍: ${n}`,
  `차이 10 이하: ${pct(within10)} (기준 90% 이상 → ${within10 / Math.max(1, n) >= 0.9 ? '통과' : '미달'})`,
  `차이 5 이하: ${pct(within5)}`,
  `평균 차이: ${(sumAbs / Math.max(1, n)).toFixed(2)}`,
  `같은 띠: ${pct(sameBand)}`,
  `쓸 수 있는 힌트인지 판정이 갈린 쌍: ${flip}`,
  `세 번째 채점(C, 10개 묶음)으로 가른 쌍: ${withC} — 차이 10 초과·판정 갈림·평균 70 이상. 확정값이 A·B 평균에서 5 넘게 움직인 쌍 ${cMovedFar}`,
  '',
  'A·B 차이가 25 넘는 쌍(정답 · 힌트 · A · B · C → 확정):',
  ...worst.slice(0, 40).map((w) => `  ${w.answer} · ${w.hint} · ${w.x} · ${w.y} · ${w.c} → ${w.final}`),
];
fs.writeFileSync(path.join(DATA, 'consistency.txt'), report.join('\n') + '\n');
console.log(report.slice(0, 8).join('\n'));

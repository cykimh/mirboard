// 5단계 — 일관성: 정답의 약 5% 를 골라 그 힌트를 **다르게 섞은 묶음**으로 한 번 더 채점해 처음 값과 견준다.
// 기준(설계서 §5.2): 차이 10 이하가 90% 이상. 산출물 data/consistency.txt
import fs from 'node:fs';
import path from 'node:path';
import { DATA, runRequests, sha, shuffled } from './lib.mjs';
import { hintsPerAnswer, chunksOf, scoreRequest, acceptScores, readScores } from './04-score.mjs';
import { bandOf } from './06-build.mjs';

const SHARE = 0.05;
const answers = hintsPerAnswer();
const picked = shuffled(answers, 'consistency').slice(0, Math.max(1, Math.round(answers.length * SHARE)));

// 처음 값 — 4단계가 적어 둔 정본
const scores = readScores();
const firstScore = new Map();
for (const a of picked) for (const [hint, s] of scores.get(a.word) ?? []) firstScore.set(`${a.word}\t${hint}`, s);

const again = [];
for (const a of picked) {
  chunksOf(a.hints, `consistency|${a.word}`).forEach((hints, c) => again.push(scoreRequest(`cons-${sha(a.word)}-${c}`, a, hints)));
}
const second = await runRequests('consistency', again, { mode: 'batch', accept: acceptScores, estimateUsdPerRequest: Number(process.env.WORDTEMP_SCORE_USD ?? 0.006) });

let n = 0, within10 = 0, within5 = 0, sameBand = 0, nounFlip = 0, sumAbs = 0, crossed96 = 0;
const worst = [];
for (const r of again) {
  for (const [i, s2] of second.get(r.id)?.scores ?? []) {
    const key = `${r.answer}\t${r.hints[i]}`;
    const s1 = firstScore.get(key);
    if (s1 === undefined) continue;
    if ((s1 < 0) !== (s2 < 0)) { nounFlip++; continue; }
    if (s1 < 0) continue;
    n++;
    const d = Math.abs(s1 - s2);
    sumAbs += d;
    if (d <= 10) within10++;
    if (d <= 5) within5++;
    if (bandOf(s1) === bandOf(s2)) sameBand++;
    if ((s1 >= 96) !== (s2 >= 96)) crossed96++;
    worst.push({ key, s1, s2, d });
  }
}
worst.sort((a, b) => b.d - a.d);
const pct = (x) => ((100 * x) / Math.max(1, n)).toFixed(1) + '%';
const report = [
  `낱말 온도 표 일관성 — 정답 ${picked.length}개(약 ${SHARE * 100}%)의 힌트를 다른 묶음으로 다시 채점`,
  `견준 쌍: ${n}`,
  `차이 10 이하: ${pct(within10)} (기준 90% 이상 → ${within10 / Math.max(1, n) >= 0.9 ? '통과' : '미달'})`,
  `차이 5 이하: ${pct(within5)}`,
  `평균 차이: ${(sumAbs / Math.max(1, n)).toFixed(2)}`,
  `같은 띠: ${pct(sameBand)}`,
  `96 경계를 넘나든 쌍: ${crossed96}`,
  `명사 판정이 뒤집힌 쌍: ${nounFlip}`,
  '',
  '차이가 큰 쌍(정답·힌트·처음·다시):',
  ...worst.slice(0, 25).map((w) => `  ${w.key.replace('\t', ' · ')} · ${w.s1} · ${w.s2}`),
];
fs.writeFileSync(path.join(DATA, 'consistency.txt'), report.join('\n') + '\n');
console.log(report.slice(0, 9).join('\n'));

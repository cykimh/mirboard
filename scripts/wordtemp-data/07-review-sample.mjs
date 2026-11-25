// 7단계 — 사람 검수 표본: 실린 표에서 띠별로 골고루 150쌍을 뽑아 review/review-sample.csv 로 쓴다(씨앗 고정 — 다시 돌려도 같은 표본).
//   node 07-review-sample.mjs           표본 만들기(이미 판정을 적은 파일이 있으면 덮어쓰지 않는다 — --force 로만)
//   node 07-review-sample.mjs --grade   판정 칸을 읽어 띠 일치율 계산(기준 80% 이상)
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { ROOT, RESOURCES, TABLE_VERSION, loadBank, shuffled } from './lib.mjs';
import { bandOf } from './06-build.mjs';

const FILE = path.join(ROOT, 'review', 'review-sample.csv');
const QUOTA = { '96+': 20, '75-95': 45, '40-74': 45, '0-39': 40 };
const BAND_NAME = { '96+': '거의 정답', '75-95': '뜨거움', '40-74': '미지근', '0-39': '차가움' };
const LETTER = { A: '96+', B: '75-95', C: '40-74', D: '0-39' };
const HEADER = '번호,정답,뜻풀이,힌트,온도,띠,판정(O/X),내가 보는 띠(A/B/C/D),메모';

if (process.argv.includes('--grade')) {
  const rows = fs.readFileSync(FILE, 'utf8').replace(/^﻿/, '').split('\n').slice(1).filter(Boolean).map((l) => l.split(','));
  let judged = 0, ok = 0;
  const off = [];
  for (const r of rows) {
    const verdict = (r[6] ?? '').trim().toUpperCase();
    const mine = LETTER[(r[7] ?? '').trim().toUpperCase()];
    if (!verdict && !mine) continue;
    judged++;
    const agree = mine ? mine === bandOf(Number(r[4])) : verdict === 'O';
    if (agree) ok++;
    else off.push(`  ${r[1]} · ${r[3]} · ${r[4]}${mine ? ` → 내가 보는 띠 ${mine}` : ''} ${r[8] ?? ''}`);
  }
  console.log(`판정한 쌍 ${judged}/${rows.length} · 띠 일치 ${ok} (${((100 * ok) / Math.max(1, judged)).toFixed(1)}%) — 기준 80% 이상`);
  if (off.length) console.log('어긋난 쌍:\n' + off.join('\n'));
} else {
  if (fs.existsSync(FILE) && !process.argv.includes('--force')) {
    const filled = fs.readFileSync(FILE, 'utf8').split('\n').slice(1).some((l) => { const c = l.split(','); return (c[6] ?? '').trim() || (c[7] ?? '').trim(); });
    if (filled) { console.error('이미 판정을 적은 표본이 있어요 — 덮어쓰려면 --force'); process.exit(1); }
  }
  const gloss = new Map(loadBank(path.join(RESOURCES, 'bank.tsv')).map((b) => [b.word, b.gloss]));
  const text = zlib.gunzipSync(fs.readFileSync(path.join(RESOURCES, `table-${TABLE_VERSION}.tsv.gz`))).toString('utf8');
  const byBand = { '96+': [], '75-95': [], '40-74': [], '0-39': [] };
  for (const line of text.split('\n').slice(1).filter(Boolean)) {
    const [answer, list] = line.split('\t');
    for (const item of list.split(',')) {
      const [hint, score] = item.split(':');
      byBand[bandOf(Number(score))].push({ answer, hint, score: Number(score) });
    }
  }
  const picked = [];
  for (const [band, quota] of Object.entries(QUOTA)) {
    const perAnswer = new Map(); // 한 정답이 표본을 차지하지 않게 띠마다 정답당 1쌍
    for (const row of shuffled(byBand[band], `review|${TABLE_VERSION}|${band}`)) {
      if (perAnswer.has(row.answer)) continue;
      perAnswer.set(row.answer, row);
      if (perAnswer.size === quota) break;
    }
    picked.push(...perAnswer.values());
  }
  const csv = (s) => (/[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s);
  const rows = shuffled(picked, `review-order|${TABLE_VERSION}`).map((r, i) =>
    [i + 1, r.answer, csv((gloss.get(r.answer) ?? '').replace(/,/g, ' ')), r.hint, r.score, BAND_NAME[bandOf(r.score)], '', '', ''].join(','),
  );
  fs.writeFileSync(FILE, '﻿' + [HEADER, ...rows].join('\n') + '\n'); // BOM — 엑셀에서 한글이 깨지지 않게
  console.log(`검수 표본 ${rows.length}쌍 → ${path.relative(process.cwd(), FILE)}`);
  // 96 이상 전부 — 힌트 단계를 끝내는 값이라 따로 본다. 정말 같은 말이면 은행의 같은 말 목록으로 올릴 후보.
  const near = byBand['96+'].map((r) => [r.answer, r.hint, r.score, '', ''].join(','));
  fs.writeFileSync(path.join(ROOT, 'review', 'near-answer.csv'), '\uFEFF' + ['정답,힌트,온도,같은 말로 올릴까(O) / 점수가 너무 높다(X),메모', ...near].join('\n') + '\n');
  console.log(`96 이상 ${near.length}쌍 → review/near-answer.csv`);
}

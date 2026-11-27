// 6단계 — 묶기: 채점 결과(캐시)를 읽어 은행 기준을 통과한 정답만 남기고 서버 자원으로 쓴다. Claude 를 부르지 않는다.
//   server/src/main/resources/wordtemp/table-v1.tsv.gz  유사도 표
//   server/src/main/resources/wordtemp/bank.tsv         정답 은행(낱말·범주·뜻풀이·같은 말)
//   server/src/main/resources/wordtemp/blocked.txt      금칙어
//   server/src/main/resources/wordtemp/score-v1.md      채점 기준문(실시간 채점과 공용)
//   data/stats.txt · data/bank-dropped.tsv
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { ROOT, DATA, RESOURCES, MODEL, PROMPT_VERSION, TABLE_VERSION, writeBank, byCodeUnit } from './lib.mjs';

export const BANDS = ['96+', '75-95', '40-74', '0-39'];
export const bandOf = (s) => (s >= 96 ? '96+' : s >= 75 ? '75-95' : s >= 40 ? '40-74' : '0-39');
// 은행에 남기는 기준 — 설계서 §5.1 의 어림(96+ 1~5 · 75~95 15개 · 40~74 60개)을 실측 뒤 D-313 에서 고쳤다.
export const CRITERIA = { max96: 5, min75: 3, min40: 40 };

if (import.meta.url === `file://${process.argv[1]}`) {
  const { readScores } = await import('./04-score.mjs');
  const { loadBank, loadBlocked, loadFlagged, isOffensive, isHangulWord, hintBlockedByAnswer } = await import('./lib.mjs');
  const scores = readScores(); // 4단계가 적어 둔 정본 — 여기서는 Claude 를 부르지 않는다
  const blocked = loadBlocked();
  const flagged = loadFlagged(); // 5b단계: 낱말 → 갈래
  const rows = new Map();
  let notNoun = 0;
  const cleaned = { proper_noun: 0, derogatory_or_slur: 0, not_a_noun: 0, blocked: 0 };
  const bank = loadBank();
  for (const a of bank) {
    const answers = [a.word, ...a.aliases];
    const hints = new Map();
    for (const [hint, s] of scores.get(a.word) ?? []) {
      if (s < 0) notNoun++;
      // 은행(같은 말 목록)·금칙어를 채점 뒤에 고쳤을 수 있으니 여기서 한 번 더 거른다
      // 5b단계가 뺀 것 가운데 비하 표현·명사 아님만 계속 뺀다(고유명사는 D-322 로 힌트가 된다)
      else if (flagged.get(hint) === 'derogatory_or_slur' || flagged.get(hint) === 'not_a_noun') cleaned[flagged.get(hint)]++;
      else if (isOffensive(hint, blocked)) cleaned.blocked++;
      else if (isHangulWord(hint) && !hintBlockedByAnswer(hint, answers)) hints.set(hint, s);
    }
    rows.set(a.word, hints);
  }

  const kept = [];
  const dropped = ['# 은행 기준을 못 채워 뺀 낱말\t까닭\t96+\t75-95\t40-74\t0-39'];
  for (const a of bank) {
    const hints = rows.get(a.word) ?? new Map();
    const count = Object.fromEntries(BANDS.map((b) => [b, 0]));
    for (const s of hints.values()) count[bandOf(s)]++;
    const reasons = [];
    if (!scores.has(a.word)) reasons.push('채점 안 됨(4단계를 돌릴 것)');
    if (count['96+'] > CRITERIA.max96) reasons.push(`96+ ${count['96+']}개 > ${CRITERIA.max96}`);
    if (count['75-95'] < CRITERIA.min75) reasons.push(`75-95 ${count['75-95']}개 < ${CRITERIA.min75}`);
    if (count['40-74'] < CRITERIA.min40) reasons.push(`40-74 ${count['40-74']}개 < ${CRITERIA.min40}`);
    if (reasons.length) dropped.push([a.word, reasons.join('; '), ...BANDS.map((b) => count[b])].join('\t'));
    else kept.push({ ...a, table: hints, count });
  }
  kept.sort((x, y) => byCodeUnit(x.word, y.word));

  const created = process.env.WORDTEMP_CREATED ?? new Date().toISOString().slice(0, 10);
  const pairs = kept.reduce((s, a) => s + a.table.size, 0);
  const header = ['#wordtemp-table', `version=${TABLE_VERSION}`, `promptVersion=${PROMPT_VERSION}`, `model=${MODEL}`, `created=${created}`, `answers=${kept.length}`, `pairs=${pairs}`].join('\t');
  const body = kept.map((a) => `${a.word}\t${[...a.table].sort((x, y) => byCodeUnit(x[0], y[0])).map(([h, s]) => `${h}:${s}`).join(',')}`);
  const text = [header, ...body].join('\n') + '\n';
  fs.mkdirSync(RESOURCES, { recursive: true });
  const tableFile = path.join(RESOURCES, `table-${TABLE_VERSION}.tsv.gz`);
  fs.writeFileSync(tableFile, zlib.gzipSync(Buffer.from(text, 'utf8'), { level: 9 }));
  writeBank(path.join(RESOURCES, 'bank.tsv'), kept, `낱말 온도 정답 은행 — 표 ${TABLE_VERSION} 와 짝(만든 날 ${created}). scripts/wordtemp-data 가 만든다 — 손으로 고치지 말 것`);
  const hand = fs.readFileSync(path.join(DATA, 'blocked.txt'), 'utf8').trimEnd();
  const derogatory = blocked.filter((b) => !hand.split('\n').includes(b));
  fs.writeFileSync(path.join(RESOURCES, 'blocked.txt'), [hand, '# ── 아래는 어휘 청소(05b-classify.mjs)가 찾은 비하·혐오 표현 ──', ...derogatory].join('\n') + '\n');
  const vocab = [...new Set(kept.flatMap((a) => [...a.table.keys()]))].sort(byCodeUnit);
  fs.writeFileSync(path.join(DATA, 'vocab-clean.txt'), `# 표 ${TABLE_VERSION} 에 실린 힌트 어휘(청소 뒤) — 6단계가 만든다\n${vocab.join('\n')}\n`);
  fs.copyFileSync(path.join(ROOT, 'prompts', `${PROMPT_VERSION}.md`), path.join(RESOURCES, `${PROMPT_VERSION}.md`));
  fs.writeFileSync(path.join(DATA, 'bank-dropped.tsv'), dropped.join('\n') + '\n');

  const total = Object.fromEntries(BANDS.map((b) => [b, kept.reduce((s, a) => s + a.count[b], 0)]));
  const stats = [
    `낱말 온도 표 ${TABLE_VERSION} — ${created} · ${MODEL} · ${PROMPT_VERSION}`,
    `정답 ${kept.length}개(후보 ${kept.length + dropped.length - 1}개 가운데 ${dropped.length - 1}개 뺌) · 쌍 ${pairs}개 · 쓸 수 없는 힌트로 판정돼 버린 쌍 ${notNoun}개`,
    `어휘 청소로 뺀 쌍: 비하 표현 ${cleaned.derogatory_or_slur} · 명사 아님 ${cleaned.not_a_noun} · 금칙어 부분 일치 ${cleaned.blocked}`,
    `띠: ${BANDS.map((b) => `${b} ${total[b]}(${((100 * total[b]) / pairs).toFixed(1)}%)`).join(' · ')}`,
    `파일: ${fs.statSync(tableFile).size} 바이트(gzip) · 풀면 ${Buffer.byteLength(text)} 바이트`,
  ];
  // 기준 온도를 정할 때 보는 수: 정답마다 그 온도 이상인 이웃이 몇 개인가
  const sorted = (arr) => [...arr].sort((x, y) => x - y);
  stats.push('정답당 이웃 수(그 온도 이상) — 가운데값 · 최소 · 0개인 정답 수:');
  for (const t of [50, 55, 60, 65, 70, 75, 80, 85, 88, 90, 96]) {
    const counts = sorted(kept.map((a) => [...a.table.values()].filter((s) => s >= t).length));
    stats.push(`  ≥${t}: ${counts[Math.floor(counts.length / 2)]} · ${counts[0]} · ${counts.filter((c) => c === 0).length}`);
  }
  fs.writeFileSync(path.join(DATA, 'stats.txt'), stats.join('\n') + '\n');
  // 눈으로 보는 예: 정답마다 위쪽 25개
  const tops = kept.map((a) => `${a.word}: ${[...a.table].sort((x, y) => y[1] - x[1]).slice(0, 25).map(([h, s]) => `${h} ${s}`).join(', ')}`);
  fs.writeFileSync(path.join(DATA, 'top-neighbors.txt'), `# 표 ${TABLE_VERSION} — 정답마다 온도가 높은 힌트 25개(6단계가 만든다)\n${tops.join('\n')}\n`);
  // 예전 표 파일은 싣지 않는다(서버는 TABLE_RESOURCE 하나만 읽는다)
  for (const f of fs.readdirSync(RESOURCES)) if (/^table-v\d+\.tsv\.gz$/.test(f) && f !== path.basename(tableFile)) fs.rmSync(path.join(RESOURCES, f));
  console.log(stats.join('\n'));
}

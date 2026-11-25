// 4단계 — 채점: 정답 1개 × 힌트 묶음(최대 100개)을 요청 하나로, 실시간 채점과 같은 기준문(prompts/score-v1.md)으로 매긴다.
// 쓰기:  node 04-score.mjs --pilot 10   (10건만 바로 불러 토큰·비용을 재고 전체 어림을 찍는다)
//        node 04-score.mjs              (나머지를 Message Batches 로 — 예산을 넘길 어림이면 보내지 않는다)
// 결과는 cache/score.jsonl 에 쌓인다(6단계가 읽는다).
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import {
  ROOT, DATA, PROMPT_VERSION, buildParams, runRequests, loadBank, readLines, isHangulWord, loadBlocked, isOffensive,
  hintBlockedByAnswer, sha, shuffled, spentUsd, BUDGET_USD,
} from './lib.mjs';
import { GLOBAL_THEMES } from './02-vocab.mjs';

export const CHUNK = 100;
const GLOBAL_SAMPLE = Number(process.env.WORDTEMP_GLOBAL_SAMPLE ?? 50);

export const RUBRIC = fs.readFileSync(path.join(ROOT, 'prompts', `${PROMPT_VERSION}.md`), 'utf8').trim();
// 묶음 입출력 껍데기 — 기준문 자체는 실시간 채점과 같은 파일이다.
const BATCH_IO = `

## 묶음 채점

입력은 JSON \`{"answer", "gloss", "hints"}\` 이고 hints 는 \`"번호 낱말"\` 꼴의 목록이다. 모든 힌트를 하나도 빠뜨리지 않고 채점해
scores 에 \`[번호, 온도]\` 쌍으로 넣는다. 힌트 하나하나를 따로, 위 기준으로 매긴다.
명사가 아닌 힌트는 온도 자리에 -1 을 쓴다 — 부사(얇게·빨리), 동사·형용사의 활용형(달리는·예쁜), 소리·모양 흉내말(톡·반짝),
사람 이름, 욕설, 없는 말·오타. 명사형(달리기·더위)과 널리 쓰이는 외래어 명사, 나라·도시 이름은 명사로 본다.`;
const SCHEMA = {
  type: 'object',
  properties: { scores: { type: 'array', items: { type: 'array', items: { type: 'integer' } } } },
  required: ['scores'],
  additionalProperties: false,
};

/** 정답마다 채점할 힌트 목록: 그 정답의 후보(3단계) + 같은 범주 어휘·같은 범주 정답 + 흔한 명사 표본. */
export function hintsPerAnswer() {
  const bank = loadBank();
  const blocked = loadBlocked();
  const vocab = (id) => readLines(path.join(DATA, 'vocab', `${id}.txt`));
  const global = [...new Set(GLOBAL_THEMES.flatMap((t) => vocab(t.id)))];
  const own = new Map(readLines(path.join(DATA, 'candidates.tsv')).map((l) => { const [w, list = ''] = l.split('\t'); return [w, list.split(',')]; }));
  const result = [];
  for (const b of bank) {
    if (!own.has(b.word)) continue;
    const answers = [b.word, ...b.aliases];
    const siblings = bank.filter((o) => o.category === b.category).map((o) => o.word);
    const pool = [...own.get(b.word), ...vocab(b.category), ...siblings, ...shuffled(global, `global|${b.word}`).slice(0, GLOBAL_SAMPLE)];
    const hints = [...new Set(pool)].filter((w) => isHangulWord(w) && !isOffensive(w, blocked) && !hintBlockedByAnswer(w, answers));
    result.push({ ...b, hints });
  }
  return result;
}

export function chunksOf(hints, seedText) {
  const order = shuffled(hints, seedText); // 띠가 섞이게 — 한 묶음이 가까운 것만·먼 것만으로 차지 않도록
  const n = Math.ceil(order.length / CHUNK);
  const size = Math.ceil(order.length / n);
  return Array.from({ length: n }, (_, i) => order.slice(i * size, (i + 1) * size));
}

export function scoreRequest(id, answer, hints) {
  return {
    id,
    hints,
    answer: answer.word,
    params: buildParams({
      system: RUBRIC + BATCH_IO,
      user: JSON.stringify({ answer: answer.word, gloss: answer.gloss, hints: hints.map((h, i) => `${i} ${h}`) }),
      schema: SCHEMA,
    }),
  };
}

/** 모든 번호가 딱 한 번씩, 온도는 -1(명사 아님) 또는 0~100 일 때만 받는다. */
export function acceptScores(output, request) {
  const n = request.hints.length;
  if (!Array.isArray(output.scores) || output.scores.length !== n) return false;
  const seen = new Set();
  for (const pair of output.scores) {
    if (!Array.isArray(pair) || pair.length !== 2) return false;
    const [i, s] = pair;
    if (!Number.isInteger(i) || i < 0 || i >= n || seen.has(i)) return false;
    if (!Number.isInteger(s) || s < -1 || s > 100) return false;
    seen.add(i);
  }
  return true;
}

export const SCORES_FILE = path.join(DATA, 'scores.tsv.gz');
/** 채점 결과 정본(정답 \t 힌트 \t 온도, 명사 아님 = -1). 은행을 나중에 고쳐도 6단계가 이 파일만 읽으면 되게 따로 적어 둔다. */
export function writeScores(requests, got) {
  const lines = [];
  for (const r of requests) for (const [i, s] of got.get(r.id)?.scores ?? []) lines.push(`${r.answer}\t${r.hints[i]}\t${s}`);
  lines.sort();
  fs.writeFileSync(SCORES_FILE, zlib.gzipSync(Buffer.from(lines.join('\n') + '\n', 'utf8'), { level: 9 }));
  return lines.length;
}
export function readScores() {
  const map = new Map(); // 정답 → Map(힌트 → 온도)
  for (const line of zlib.gunzipSync(fs.readFileSync(SCORES_FILE)).toString('utf8').split('\n')) {
    if (!line) continue;
    const [answer, hint, score] = line.split('\t');
    if (!map.has(answer)) map.set(answer, new Map());
    map.get(answer).set(hint, Number(score));
  }
  return map;
}

export function allScoreRequests() {
  const requests = [];
  for (const a of hintsPerAnswer()) {
    chunksOf(a.hints, `score|${a.word}`).forEach((hints, c) => requests.push(scoreRequest(`score-${sha(a.word)}-${c}`, a, hints)));
  }
  return requests;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const requests = allScoreRequests();
  const pairs = requests.reduce((s, r) => s + r.hints.length, 0);
  console.log(`채점 요청 ${requests.length}건 · 쌍 ${pairs}개 · 정답 ${new Set(requests.map((r) => r.answer)).size}개`);
  const pilotAt = process.argv.indexOf('--pilot');
  if (pilotAt > 0) {
    const n = Number(process.argv[pilotAt + 1]);
    const sample = shuffled(requests, 'pilot').slice(0, n);
    const before = spentUsd();
    const got = await runRequests('score', sample, { mode: 'sync', estimateUsdPerRequest: 0.04, accept: acceptScores, rounds: 1 });
    const perRequestSync = (spentUsd() - before) / Math.max(1, sample.length);
    const rest = requests.length - got.size;
    const estimate = (perRequestSync / 2) * rest;
    console.log(`시험 ${got.size}/${sample.length}건 통과 · 건당 $${perRequestSync.toFixed(4)}(바로) → 배치 건당 $${(perRequestSync / 2).toFixed(4)}`);
    console.log(`전체 어림: 남은 ${rest}건 × 배치 = $${estimate.toFixed(2)} · 지금까지 $${spentUsd().toFixed(2)} · 상한 $${BUDGET_USD}`);
  } else {
    const got = await runRequests('score', requests, { mode: 'batch', accept: acceptScores });
    console.log(`채점 끝: ${got.size}/${requests.length}건 · 쌍 ${writeScores(requests, got)}개 → data/scores.tsv.gz`);
  }
}

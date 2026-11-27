// 4단계(D-322, 표 v2) — 채점. 기준문 prompts/score-v3.md(실시간 채점과 같은 파일), 정답 1개 × 힌트 약 20개 묶음.
// 세 번 매긴다:
//   A  모든 쌍, 20개 묶음
//   B  모든 쌍을 **다르게 섞은** 20개 묶음 — A 와 평균을 내고, 둘의 차이가 곧 일관성 측정이다
//   C  A·B 가 10 넘게 벌어졌거나 명사 판정이 갈렸거나 평균이 70 이상인 쌍만 10개 묶음으로 한 번 더(가운데값으로 확정)
// 쓰기:  node 04-score.mjs --pass A --pilot 10   (10건만 바로 불러 토큰·비용을 재고 전체 어림을 찍는다)
//        node 04-score.mjs --pass A              (Message Batches — 처음 300건으로 실제 비용을 본 뒤 나머지를 2,500건씩)
//        node 04-score.mjs --pass B  →  --pass C  →  --merge   (data/scores-v3.tsv.gz: 정답·힌트·확정·A·B·C)
// 표 v1 의 채점(100개 묶음·score-v1)은 git 이력과 cache/score.jsonl · data/scores-v1.tsv.gz 에 남아 있다.
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import {
  ROOT, DATA, PROMPT_VERSION, buildParams, runRequests, loadBank, loadFlagged, readLines, isHangulWord, loadBlocked, isOffensive,
  hintBlockedByAnswer, sha, shuffled, spentUsd, BUDGET_USD,
} from './lib.mjs';

const GROUP = { A: 20, B: 20, C: 10 };
const TOP = 70; // C 로 다시 보는 "위쪽 후보" 문턱(A·B 평균)
const GAP = 10; // 이보다 벌어지면 C 로 가른다

export const RUBRIC = fs.readFileSync(path.join(ROOT, 'prompts', `${PROMPT_VERSION}.md`), 'utf8').trim();
// 묶음 입출력 껍데기 — 기준문 자체는 실시간 채점과 같은 파일이다.
const BATCH_IO = `

## 묶음 채점

입력은 JSON \`{"answer", "gloss", "hints"}\` 이고 hints 는 \`"번호 낱말"\` 꼴의 목록이다. 모든 힌트를 하나도 빠뜨리지 않고 채점해
scores 에 \`[번호, 온도]\` 쌍으로 넣는다. 힌트 하나하나를 **따로**, 위 기준으로 매긴다(같은 묶음의 다른 힌트와 견주어 깎지 않는다).
쓸 수 없는 힌트는 온도 자리에 -1 을 쓴다.`;
const SCHEMA = {
  type: 'object',
  properties: { scores: { type: 'array', items: { type: 'array', items: { type: 'integer' } } } },
  required: ['scores'],
  additionalProperties: false,
};

const gunzipLines = (file) => zlib.gunzipSync(fs.readFileSync(file)).toString('utf8').split('\n').filter(Boolean);
const tsvMap = (file) => new Map(readLines(file).map((l) => { const [w, list = ''] = l.split('\t'); return [w, list ? list.split(',') : []]; }));

/**
 * 정답마다 채점할 힌트: 표 v1 때 채점했던 낱말 전부(3단계 후보 + 범주 어휘 + 흔한 명사 표본) + 3b단계의 창의·고유명사 후보.
 * 5b단계가 뺀 것 가운데 **비하 표현·명사 아님만** 계속 뺀다 — 고유명사는 D-322 로 되살린다.
 */
export function hintsPerAnswer() {
  const bank = loadBank();
  const blocked = loadBlocked();
  const flagged = loadFlagged();
  const old = new Map();
  for (const line of gunzipLines(path.join(DATA, 'scores-v1.tsv.gz'))) {
    const [answer, hint] = line.split('\t');
    if (!old.has(answer)) old.set(answer, []);
    old.get(answer).push(hint);
  }
  const creative = tsvMap(path.join(DATA, 'candidates-creative.tsv'));
  const result = [];
  for (const b of bank) {
    const answers = [b.word, ...b.aliases];
    const pool = [...(old.get(b.word) ?? []), ...(creative.get(b.word) ?? [])];
    const hints = [...new Set(pool)].filter((w) => {
      const cls = flagged.get(w);
      return isHangulWord(w) && cls !== 'derogatory_or_slur' && cls !== 'not_a_noun' && !isOffensive(w, blocked) && !hintBlockedByAnswer(w, answers);
    }).sort();
    if (hints.length) result.push({ ...b, hints });
  }
  return result;
}

function chunksOf(hints, seedText, group) {
  const order = shuffled(hints, seedText);
  const n = Math.ceil(order.length / group);
  const size = Math.ceil(order.length / n);
  return Array.from({ length: n }, (_, i) => order.slice(i * size, (i + 1) * size));
}

function scoreRequest(id, answer, hints) {
  return {
    id,
    hints,
    answer: answer.word,
    params: buildParams({
      system: RUBRIC + BATCH_IO,
      cacheSystem: true,
      user: JSON.stringify({ answer: answer.word, gloss: answer.gloss, hints: hints.map((h, i) => `${i} ${h}`) }),
      schema: SCHEMA,
      maxTokens: 4000,
    }),
  };
}

/** 모든 번호가 딱 한 번씩, 온도는 -1(쓸 수 없음) 또는 0~100 일 때만 받는다. */
function acceptScores(output, request) {
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

const stepOf = (pass) => `score-v3${pass.toLowerCase()}`;
export function requestsOf(pass, answers) {
  const requests = [];
  for (const a of answers) {
    chunksOf(a.hints, `score-v3|${pass}|${a.word}`, GROUP[pass]).forEach((hints, c) =>
      requests.push(scoreRequest(`${pass.toLowerCase()}-${sha(a.word)}-${c}`, a, hints)));
  }
  return requests;
}
/** 캐시만 읽어 정답 → Map(힌트 → 온도). */
async function cachedScores(pass, answers) {
  const requests = requestsOf(pass, answers);
  const got = await runRequests(stepOf(pass), requests, { mode: 'batch', accept: acceptScores, rounds: 0 });
  const map = new Map();
  for (const r of requests) for (const [i, s] of got.get(r.id)?.scores ?? []) map.set(`${r.answer}\t${r.hints[i]}`, s);
  return map;
}
/** C 로 다시 볼 쌍: 판정이 갈렸거나, 10 넘게 벌어졌거나, 평균이 위쪽인 것. */
async function passCAnswers(answers) {
  const a = await cachedScores('A', answers);
  const b = await cachedScores('B', answers);
  return answers.map((ans) => ({
    ...ans,
    hints: ans.hints.filter((h) => {
      const x = a.get(`${ans.word}\t${h}`), y = b.get(`${ans.word}\t${h}`);
      if (x === undefined || y === undefined) return false;
      if ((x < 0) !== (y < 0)) return true;
      if (x < 0) return false;
      return Math.abs(x - y) > GAP || (x + y) / 2 >= TOP;
    }),
  })).filter((ans) => ans.hints.length);
}

export const SCORES_FILE = path.join(DATA, 'scores-v3.tsv.gz');
/** 확정 점수: 정답 → Map(힌트 → 온도, 쓸 수 없음 = -1). */
export function readScores() {
  const map = new Map();
  for (const line of gunzipLines(SCORES_FILE)) {
    const [answer, hint, score] = line.split('\t');
    if (!map.has(answer)) map.set(answer, new Map());
    map.get(answer).set(hint, Number(score));
  }
  return map;
}
/** A·B·C 원점수: [정답, 힌트, 확정, A, B, C('' = 없음)] */
export function readScoreRows() {
  return gunzipLines(SCORES_FILE).map((l) => l.split('\t'));
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const arg = (name) => { const at = process.argv.indexOf(name); return at > 0 ? process.argv[at + 1] : undefined; };
  const answers = hintsPerAnswer();
  if (process.argv.includes('--merge')) {
    const [a, b, c] = [await cachedScores('A', answers), await cachedScores('B', answers), await cachedScores('C', await passCAnswers(answers))];
    const lines = [];
    let missing = 0;
    for (const ans of answers) for (const h of ans.hints) {
      const key = `${ans.word}\t${h}`;
      const votes = [a.get(key), b.get(key), c.get(key)].filter((v) => v !== undefined);
      if (a.get(key) === undefined || b.get(key) === undefined) { missing++; continue; }
      const valid = votes.filter((v) => v >= 0).sort((x, y) => x - y);
      let final;
      if (valid.length * 2 <= votes.length) final = -1; // 과반이 쓸 수 없다고 봄(2표가 갈리면 쓸 수 없음 쪽)
      else if (valid.length === 3) final = valid[1]; // 가운데값
      else final = Math.round(valid.reduce((s, v) => s + v, 0) / valid.length);
      lines.push([key, final, a.get(key), b.get(key), c.get(key) ?? ''].join('\t'));
    }
    lines.sort();
    fs.writeFileSync(SCORES_FILE, zlib.gzipSync(Buffer.from(lines.join('\n') + '\n', 'utf8'), { level: 9 }));
    console.log(`확정 ${lines.length}쌍 → data/scores-v3.tsv.gz${missing ? ` · A·B 가 덜 끝난 쌍 ${missing}개는 뺌` : ''}`);
  } else {
    const pass = (arg('--pass') ?? 'A').toUpperCase();
    const requests = requestsOf(pass, pass === 'C' ? await passCAnswers(answers) : answers);
    const pairs = requests.reduce((s, r) => s + r.hints.length, 0);
    console.log(`[${pass}] 채점 요청 ${requests.length}건 · 쌍 ${pairs}개 · 정답 ${new Set(requests.map((r) => r.answer)).size}개`);
    const step = stepOf(pass);
    const pilot = Number(arg('--pilot') ?? 0);
    if (pilot) {
      const sample = shuffled(requests, 'pilot').slice(0, pilot);
      const before = spentUsd();
      // 한 건을 먼저 보내 프롬프트 캐시를 데운 뒤 나머지 — 캐시 적중 때의 비용을 재려고
      const first = await runRequests(step, sample.slice(0, 1), { mode: 'sync', estimateUsdPerRequest: 0.02, accept: acceptScores, rounds: 1 });
      const got = await runRequests(step, sample, { mode: 'sync', estimateUsdPerRequest: 0.02, accept: acceptScores, rounds: 1 });
      const per = (spentUsd() - before) / Math.max(1, sample.length);
      const rest = requests.length - got.size;
      console.log(`시험 ${got.size}/${sample.length}건 통과(첫 건 ${first.size}) · 건당 $${per.toFixed(4)}(바로) → 배치 건당 약 $${(per / 2).toFixed(4)}`);
      console.log(`전체 어림: 남은 ${rest}건 × 배치 = $${((per / 2) * rest).toFixed(2)} · 지금까지 $${spentUsd().toFixed(2)} · 상한 $${BUDGET_USD}`);
    } else {
      // 처음 300건으로 실제 비용(캐시 적중률 포함)을 본 뒤, 그 평균으로 나머지를 어림해 2,500건씩 보낸다.
      let done = 0;
      for (const size of [300, ...Array(Math.ceil(requests.length / 2500)).fill(2500)]) {
        if (done >= requests.length) break;
        const slice = requests.slice(done, done + size);
        const got = await runRequests(step, slice, { mode: 'batch', accept: acceptScores, estimateFrom: stepOf('A') });
        done += slice.length;
        console.log(`[${pass}] ${done}/${requests.length}건까지(${got.size}/${slice.length}) · 누적 $${spentUsd().toFixed(2)}`);
      }
    }
  }
}

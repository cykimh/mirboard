// 낱말 온도 데이터 도구 공용 — Claude 호출(동기·Message Batches), 캐시, 지출 장부, 낱말 규칙.
// API 키는 환경 변수 ANTHROPIC_API_KEY, 없으면 ~/.config/mirboard/anthropic.key 를 이 프로세스에서만 읽는다.
// 키는 어디에도 찍거나 쓰지 않는다.
import Anthropic from '@anthropic-ai/sdk';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

export const ROOT = path.dirname(fileURLToPath(import.meta.url));
export const DATA = path.join(ROOT, 'data');
export const CACHE = path.join(ROOT, 'cache');
export const RESOURCES = path.resolve(ROOT, '../../server/src/main/resources/wordtemp');

export const MODEL = 'claude-sonnet-5-5'; // 사용자 결정(D-312) — 표와 실시간이 같은 모델
export const EFFORT = 'low';
export const PROMPT_VERSION = 'score-v3'; // D-322 — v1 표는 score-v1(cache/score.jsonl·data/scores-v1.tsv.gz 에 남아 있다)
export const TABLE_VERSION = 'v2';

// claude-sonnet-5-5 표준 단가($/100만 토큰). Message Batches 는 절반.
// 캐시 쓰기(5분) 1.25배, 캐시 읽기 $0.10(0.05배).
const PRICE = { input: 2, output: 10, cacheWrite: 2.5, cacheRead: 0.1 };
// 장부는 일감마다 따로: spend.json = 표 v1(S1a, 상한 $12) · spend-v3.json = 표 v2 다시 채점(D-322, 상한 $50 — 사용자가 올림)
export const BUDGET_USD = Number(process.env.WORDTEMP_BUDGET_USD ?? 50);
const SPEND_FILE = path.join(CACHE, process.env.WORDTEMP_LEDGER ?? 'spend-v3.json');

let cachedClient;
export function client() {
  if (cachedClient) return cachedClient;
  let apiKey = process.env.ANTHROPIC_API_KEY;
  if (!apiKey) {
    const keyFile = path.join(os.homedir(), '.config/mirboard/anthropic.key');
    if (fs.existsSync(keyFile)) apiKey = fs.readFileSync(keyFile, 'utf8').trim();
  }
  if (!apiKey) throw new Error('ANTHROPIC_API_KEY 가 없어요(환경 변수 또는 ~/.config/mirboard/anthropic.key).');
  cachedClient = new Anthropic({ apiKey });
  return cachedClient;
}

// ── 낱말 규칙 (룰 §5.1·§5.2 와 같은 뜻 — 서버 엔진이 정본, 여기는 표를 거르는 데만) ──────────────
export function normalizeWord(raw) {
  return String(raw ?? '')
    .normalize('NFC')
    .replace(/[\s.,!?~…·'"“”‘’()\[\]\-_/]/g, '');
}
export const isHangulWord = (w, min = 1, max = 10) => new RegExp(`^[가-힣]{${min},${max}}$`).test(w);

/** 힌트가 정답(또는 같은 말)과 같거나 포함하거나, 2자 이상인데 그 안에 들어 있으면 낼 수 없다. */
export function hintBlockedByAnswer(hint, answers) {
  return answers.some((a) => hint.includes(a) || (hint.length >= 2 && a.includes(hint)));
}

const listFile = (name) =>
  fs.existsSync(path.join(DATA, name))
    ? fs.readFileSync(path.join(DATA, name), 'utf8').split('\n').map((l) => l.trim()).filter((l) => l && !l.startsWith('#'))
    : [];
/** 금칙어 = 손으로 쓴 목록(blocked.txt) + 5b단계가 찾은 비하 표현(blocked-derogatory.txt). 부분 일치로 쓴다. */
export function loadBlocked() {
  return [...new Set([...listFile('blocked.txt'), ...listFile('blocked-derogatory.txt')])];
}
/** 5b단계가 힌트 어휘에서 뺀 낱말(고유명사·비하 표현·명사 아님) → 갈래. */
export function loadFlagged() {
  return new Map(listFile('flagged.tsv').map((l) => l.split('\t')));
}
export const isOffensive = (word, blocked) => blocked.some((b) => word.includes(b));

// ── 파일 ─────────────────────────────────────────────────────────────────────────────
export const readLines = (file) =>
  fs.existsSync(file)
    ? fs.readFileSync(file, 'utf8').split('\n').map((l) => l.trim()).filter((l) => l && !l.startsWith('#'))
    : [];

/** bank.tsv: 낱말 \t 범주 \t 뜻풀이 \t 같은 말(쉼표) */
export function loadBank(file = path.join(DATA, 'bank.tsv')) {
  return readLines(file).map((line) => {
    const [word, category, gloss, aliases = ''] = line.split('\t');
    return { word, category, gloss, aliases: aliases ? aliases.split(',') : [] };
  });
}
export function writeBank(file, rows, header) {
  const body = rows.map((r) => [r.word, r.category, r.gloss, r.aliases.join(',')].join('\t'));
  fs.writeFileSync(file, [`# ${header}`, '# 낱말\t범주\t뜻풀이\t같은 말(쉼표)', ...body].join('\n') + '\n');
}

export const sha = (s, n = 12) => crypto.createHash('sha1').update(s).digest('hex').slice(0, n);

/** 씨앗 고정 난수(mulberry32) — 같은 입력이면 같은 묶음·같은 표본. */
export function rng(seedText) {
  let a = parseInt(sha(seedText, 8), 16) >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
export function shuffled(list, seedText) {
  const r = rng(seedText);
  const a = [...list];
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(r() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

/** Java String.compareTo 와 같은 순서(UTF-16 코드 단위) — 서버가 이진 탐색할 순서. */
export const byCodeUnit = (a, b) => (a < b ? -1 : a > b ? 1 : 0);

// ── 요청 만들기 ─────────────────────────────────────────────────────────────────────────
/** thinking 은 보내지 않는다(Sonnet 5.5 는 끌 수 없음 — effort 로만 조절). 출력은 구조화 출력. */
export function buildParams({ system, user, schema, maxTokens = 16000, cacheSystem = false }) {
  return {
    model: MODEL,
    max_tokens: maxTokens,
    // cacheSystem: 같은 기준문을 수천 번 보내는 채점에서 시스템 프롬프트를 프롬프트 캐시에 올린다(읽기 0.05배).
    system: cacheSystem ? [{ type: 'text', text: system, cache_control: { type: 'ephemeral' } }] : system,
    messages: [{ role: 'user', content: user }],
    output_config: { effort: EFFORT, format: { type: 'json_schema', schema } },
  };
}

// ── 지출 장부 ──────────────────────────────────────────────────────────────────────────
function loadSpend() {
  return fs.existsSync(SPEND_FILE) ? JSON.parse(fs.readFileSync(SPEND_FILE, 'utf8')) : { totalUsd: 0, entries: [] };
}
export const spentUsd = () => loadSpend().totalUsd;
function costOf(usage, mode) {
  const full =
    ((usage.input_tokens ?? 0) * PRICE.input +
      (usage.output_tokens ?? 0) * PRICE.output +
      (usage.cache_creation_input_tokens ?? 0) * PRICE.cacheWrite +
      (usage.cache_read_input_tokens ?? 0) * PRICE.cacheRead) /
    1e6;
  return mode === 'batch' ? full / 2 : full;
}
function recordSpend(step, mode, usages) {
  if (!usages.length) return 0;
  const sum = (k) => usages.reduce((s, u) => s + (u[k] ?? 0), 0);
  const input = sum('input_tokens');
  const output = sum('output_tokens');
  const cacheWrite = sum('cache_creation_input_tokens');
  const cacheRead = sum('cache_read_input_tokens');
  const usd = costOf({ input_tokens: input, output_tokens: output, cache_creation_input_tokens: cacheWrite, cache_read_input_tokens: cacheRead }, mode);
  const spend = loadSpend();
  spend.entries.push({ step, mode, requests: usages.length, input, output, cacheWrite, cacheRead, usd: +usd.toFixed(4) });
  spend.totalUsd = +(spend.totalUsd + usd).toFixed(4);
  fs.writeFileSync(SPEND_FILE, JSON.stringify(spend, null, 1) + '\n');
  return usd;
}

// ── 캐시(요청 id + 내용 해시 → 응답 글) ───────────────────────────────────────────────────────
const cacheFile = (step) => path.join(CACHE, `${step}.jsonl`);
function loadCache(step) {
  const map = new Map();
  for (const line of readLines(cacheFile(step))) {
    const row = JSON.parse(line);
    map.set(row.id, row);
  }
  return map;
}
function appendCache(step, row) {
  fs.appendFileSync(cacheFile(step), JSON.stringify(row) + '\n');
}
const hashOf = (params) => sha(JSON.stringify(params), 16);

function textOf(message) {
  if (message.stop_reason !== 'end_turn') return null; // refusal·max_tokens 는 실패로 친다
  const block = message.content.find((b) => b.type === 'text');
  if (!block) return null;
  try {
    return JSON.parse(block.text);
  } catch {
    return null;
  }
}

/**
 * 요청 묶음을 돌려 결과(id → 파싱된 JSON)를 돌려준다. 이미 캐시에 같은 내용이 있으면 다시 묻지 않는다.
 *  - mode 'sync': 한 건씩 바로(시험·작은 단계). 'batch': Message Batches(반값, 비동기).
 *  - estimateUsdPerRequest: 보내기 전 어림 → 예산(BUDGET_USD)을 넘기면 보내지 않고 멈춘다.
 *  - accept(parsed, request): 내용 검증. false 면 캐시에 넣지 않고 다음 바퀴에 다시 묻는다.
 */
export async function runRequests(step, requests, { mode = 'batch', estimateUsdPerRequest, estimateFrom, accept = () => true, rounds = 3 } = {}) {
  const cache = loadCache(step);
  const results = new Map();
  const withHash = requests.map((r) => ({ ...r, hash: hashOf(r.params) }));
  for (const r of withHash) {
    const hit = cache.get(r.id);
    if (hit && hit.hash === r.hash) results.set(r.id, hit.output);
  }
  for (let round = 1; round <= rounds; round++) {
    const todo = withHash.filter((r) => !results.has(r.id));
    if (!todo.length) break;
    // 어림: 준 값 → 이 단계의 실측 평균 → 비슷한 단계(estimateFrom)의 실측 평균
    const perRequest = estimateUsdPerRequest ?? averageCost(cache, mode) ?? (estimateFrom ? averageCost(loadCache(estimateFrom), mode) : null);
    if (perRequest == null) throw new Error(`[${step}] 비용 어림이 없어요 — 먼저 --pilot 으로 재세요.`);
    const estimate = perRequest * todo.length;
    const spent = spentUsd();
    console.log(
      `[${step}] ${round}바퀴: ${todo.length}건(${mode}) 어림 $${estimate.toFixed(2)} · 지금까지 $${spent.toFixed(2)} · 상한 $${BUDGET_USD}`,
    );
    if (spent + estimate > BUDGET_USD) throw new Error(`[${step}] 예산 초과 어림 — 멈춤($${(spent + estimate).toFixed(2)} > $${BUDGET_USD}).`);

    const got = mode === 'batch' ? await runBatch(step, todo) : await runSync(todo);
    const usages = [];
    let rejected = 0;
    for (const r of todo) {
      const message = got.get(r.id);
      if (!message) continue;
      usages.push(message.usage);
      const output = textOf(message);
      if (output == null || !accept(output, r)) {
        rejected++;
        continue;
      }
      const row = { id: r.id, hash: r.hash, output, usage: { in: message.usage.input_tokens, out: message.usage.output_tokens, cw: message.usage.cache_creation_input_tokens ?? 0, cr: message.usage.cache_read_input_tokens ?? 0 }, mode };
      appendCache(step, row);
      cache.set(r.id, row);
      results.set(r.id, output);
    }
    const usd = recordSpend(step, mode, usages);
    console.log(`[${step}] ${round}바퀴 끝: 응답 ${usages.length}/${todo.length} · 버림 ${rejected} · 실제 $${usd.toFixed(3)} · 누적 $${spentUsd().toFixed(2)}`);
  }
  const missing = withHash.filter((r) => !results.has(r.id)).length;
  if (missing) console.warn(`[${step}] ${missing}건은 끝내 못 받았어요(다시 돌리면 그 건만 묻습니다).`);
  return results;
}

function averageCost(cache, mode) {
  const rows = [...cache.values()];
  if (!rows.length) return null;
  const avg = (k) => rows.reduce((s, r) => s + (r.usage[k] ?? 0), 0) / rows.length;
  return costOf({ input_tokens: avg('in'), output_tokens: avg('out'), cache_creation_input_tokens: avg('cw'), cache_read_input_tokens: avg('cr') }, mode) * 1.15; // 여유 15%
}

async function runSync(todo, concurrency = 4) {
  const anthropic = client();
  const got = new Map();
  let next = 0;
  async function worker() {
    while (next < todo.length) {
      const r = todo[next++];
      try {
        got.set(r.id, await anthropic.messages.create(r.params));
      } catch (err) {
        // 고쳐야 하는 오류(키·요청 꼴·결제 한도) — 다시 불러도 같으니 멈춘다
        if (err instanceof Anthropic.AuthenticationError || err instanceof Anthropic.BadRequestError || err instanceof Anthropic.PermissionDeniedError) throw err;
        if (err instanceof Anthropic.RateLimitError) console.warn(`  ${r.id}: 429 — 다음 바퀴에 다시`);
        else if (err instanceof Anthropic.APIError) console.warn(`  ${r.id}: API 오류 ${err.status ?? ''} — 다음 바퀴에 다시`);
        else throw err;
      }
    }
  }
  await Promise.all(Array.from({ length: concurrency }, worker));
  return got;
}

/** 배치는 만들자마자 id 를 파일에 적는다 — 도중에 끊겨도 다시 돌리면 새로 보내지 않고 이어서 기다린다. */
async function runBatch(step, todo) {
  const anthropic = client();
  const pendingFile = path.join(CACHE, `${step}.pending.json`);
  let pending = fs.existsSync(pendingFile) ? JSON.parse(fs.readFileSync(pendingFile, 'utf8')) : null;
  const wanted = new Map(todo.map((r) => [r.id, r.hash]));
  if (pending && !pending.requests.every(([id, hash]) => wanted.get(id) === hash)) {
    console.warn(`[${step}] 기다리던 배치(${pending.batchIds.join(', ')})가 지금 요청과 달라요 — 그 결과는 쓰지 않습니다.`);
    pending = null;
  }
  if (!pending) {
    const batchIds = [];
    for (let i = 0; i < todo.length; i += 2000) {
      const slice = todo.slice(i, i + 2000);
      const batch = await anthropic.messages.batches.create({
        requests: slice.map((r) => ({ custom_id: r.id, params: r.params })),
      });
      batchIds.push(batch.id);
      console.log(`[${step}] 배치 ${batch.id} 보냄(${slice.length}건)`);
    }
    pending = { batchIds, requests: todo.map((r) => [r.id, r.hash]) };
    fs.writeFileSync(pendingFile, JSON.stringify(pending) + '\n');
  }
  const got = new Map();
  for (const batchId of pending.batchIds) {
    for (;;) {
      const batch = await anthropic.messages.batches.retrieve(batchId);
      if (batch.processing_status === 'ended') {
        const c = batch.request_counts;
        console.log(`[${step}] 배치 ${batchId} 끝: 성공 ${c.succeeded} · 오류 ${c.errored} · 만료 ${c.expired} · 취소 ${c.canceled}`);
        break;
      }
      console.log(`[${step}] 배치 ${batchId} 진행 중 — 남은 ${batch.request_counts.processing}건`);
      await new Promise((resolve) => setTimeout(resolve, 30_000));
    }
    // 결과는 순서가 보장되지 않는다 — custom_id 로 맞춘다.
    for await (const item of await anthropic.messages.batches.results(batchId)) {
      if (item.result.type === 'succeeded') got.set(item.custom_id, item.result.message);
      else if (item.result.type === 'errored') console.warn(`  ${item.custom_id}: ${item.result.error?.error?.type ?? item.result.error?.type ?? 'errored'}`);
    }
  }
  fs.rmSync(pendingFile);
  return got;
}

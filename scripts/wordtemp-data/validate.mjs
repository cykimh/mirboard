// 검증 — 서버에 실린 파일(server/src/main/resources/wordtemp/)만 읽어 불변식을 확인하고 통계를 찍는다.
// 의존성 없음(Node 내장 모듈만) — `node scripts/wordtemp-data/validate.mjs`. 어긋나면 종료 코드 1.
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

const RES = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../server/src/main/resources/wordtemp');
const MIN_75_95 = 3; // 은행 기준(D-313 — 06-build.mjs 의 CRITERIA 와 같은 값)
const MIN_40_74 = 40;
const MAX_96 = 5;
const HANGUL = /^[가-힣]+$/;
const byCodeUnit = (a, b) => (a < b ? -1 : a > b ? 1 : 0);
const errors = [];
const fail = (msg) => { if (errors.length < 40) errors.push(msg); else if (errors.length === 40) errors.push('…(더 있음)'); };

// ── 은행 ──
const bank = new Map();
for (const line of fs.readFileSync(path.join(RES, 'bank.tsv'), 'utf8').split('\n')) {
  if (!line || line.startsWith('#')) continue;
  const [word, category, gloss, aliases = ''] = line.split('\t');
  if (!HANGUL.test(word) || word.length < 2 || word.length > 6) fail(`은행: '${word}' 는 한글 2~6자가 아님`);
  if (!category || !gloss) fail(`은행: '${word}' 범주·뜻풀이 없음`);
  if (bank.has(word)) fail(`은행: '${word}' 중복`);
  const list = aliases ? aliases.split(',') : [];
  for (const a of list) if (!HANGUL.test(a) || a.length > 10 || a === word) fail(`은행: '${word}' 의 같은 말 '${a}' 형식`);
  bank.set(word, { category, gloss, aliases: list });
}
for (const [word, b] of bank) for (const a of b.aliases) if (bank.has(a)) fail(`은행: '${word}' 의 같은 말 '${a}' 가 다른 정답임`);
const blocked = fs.readFileSync(path.join(RES, 'blocked.txt'), 'utf8').split('\n').map((l) => l.trim()).filter((l) => l && !l.startsWith('#'));

// 어휘 청소(05b)가 뺀 낱말 가운데 **비하 표현·명사 아님** — 도구 폴더에 있을 때만 대조한다.
// 고유명사(사람·지명·상표·작품·캐릭터)는 D-322 부터 힌트로 쓸 수 있어 대조하지 않는다.
const flaggedFile = path.resolve(RES, '../../../../../scripts/wordtemp-data/data/flagged.tsv');
const flagged = new Set(fs.existsSync(flaggedFile)
  ? fs.readFileSync(flaggedFile, 'utf8').split('\n').filter((l) => l && !l.startsWith('#')).map((l) => l.split('\t')).filter(([, c]) => c !== 'proper_noun').map(([w]) => w)
  : []);
// 리뷰에서 실제로 나왔던 비하 표현 — 늘 확인한다.
const MUST_NOT_APPEAR = ['절름발이', '난쟁이', '벙어리', '귀머거리', '장님', '꼽추', '병신', '거지'];
for (const w of ['절름발이', '난쟁이']) if (!blocked.includes(w)) fail(`금칙어 목록에 '${w}' 가 없음`);

// ── 표 ──
const tableFile = fs.readdirSync(RES).filter((f) => /^table-v\d+\.tsv\.gz$/.test(f)).sort().pop();
if (!tableFile) { console.error('표 파일이 없어요'); process.exit(1); }
const gz = fs.readFileSync(path.join(RES, tableFile));
const text = zlib.gunzipSync(gz).toString('utf8');
const lines = text.split('\n').filter(Boolean);
const head = Object.fromEntries(lines[0].split('\t').slice(1).map((kv) => kv.split('=')));
if (!lines[0].startsWith('#wordtemp-table\t')) fail('머리 줄이 #wordtemp-table 로 시작하지 않음');
for (const key of ['version', 'promptVersion', 'model', 'created', 'answers', 'pairs']) if (!head[key]) fail(`머리 줄에 ${key} 없음`);
if (tableFile !== `table-${head.version}.tsv.gz`) fail(`파일 이름과 version(${head.version}) 불일치`);
if (!fs.existsSync(path.join(RES, `${head.promptVersion}.md`))) fail(`채점 기준문 ${head.promptVersion}.md 없음`);

const band = { '96+': 0, '75-95': 0, '40-74': 0, '0-39': 0 };
const bandOf = (s) => (s >= 96 ? '96+' : s >= 75 ? '75-95' : s >= 40 ? '40-74' : '0-39');
const vocab = new Set();
const perAnswer = [];
const without96 = [];
let pairs = 0, prevAnswer = null, vocabChars = 0;
for (const line of lines.slice(1)) {
  const [answer, list = ''] = line.split('\t');
  if (!bank.has(answer)) fail(`표: 정답 '${answer}' 가 은행에 없음`);
  if (prevAnswer !== null && byCodeUnit(prevAnswer, answer) >= 0) fail(`표: 정답 정렬·중복 위반 '${prevAnswer}' → '${answer}'`);
  prevAnswer = answer;
  const answers = [answer, ...(bank.get(answer)?.aliases ?? [])];
  const count = { '96+': 0, '75-95': 0, '40-74': 0, '0-39': 0 };
  let prevHint = null, n = 0;
  for (const item of list.split(',')) {
    const at = item.lastIndexOf(':');
    const hint = item.slice(0, at);
    const raw = item.slice(at + 1);
    const score = Number(raw);
    if (!HANGUL.test(hint) || hint.length > 10) fail(`표: ${answer} 의 힌트 '${hint}' 가 한글 1~10자가 아님`);
    if (!/^\d{1,3}$/.test(raw) || score > 100) fail(`표: ${answer}·${hint} 온도 '${raw}' 범위 밖`);
    if (prevHint !== null && byCodeUnit(prevHint, hint) >= 0) fail(`표: ${answer} 의 힌트 정렬·중복 위반 '${prevHint}' → '${hint}'`);
    prevHint = hint;
    // 룰 §5.2: 정답·같은 말과 같거나 포함(HINT_IS_ANSWER), 2자 이상인데 정답 안에 있음(HINT_INSIDE_ANSWER)
    for (const a of answers) {
      if (hint.includes(a)) fail(`표: ${answer} 의 힌트 '${hint}' 가 정답·같은 말 '${a}' 를 포함`);
      else if (hint.length >= 2 && a.includes(hint)) fail(`표: ${answer} 의 힌트 '${hint}' 가 '${a}' 안에 있음`);
    }
    if (blocked.some((b) => hint.includes(b))) fail(`표: ${answer} 의 힌트 '${hint}' 가 금칙어`);
    if (flagged.has(hint) && !bank.has(hint)) fail(`표: ${answer} 의 힌트 '${hint}' 는 어휘 청소에서 뺀 낱말`);
    if (MUST_NOT_APPEAR.includes(hint)) fail(`표: ${answer} 의 힌트 '${hint}' 는 비하 표현`);
    count[bandOf(score)]++;
    if (!vocab.has(hint)) { vocab.add(hint); vocabChars += hint.length; }
    n++;
  }
  if (count['75-95'] < 1) fail(`표: ${answer} 에 75~95 이웃이 없음`);
  if (count['75-95'] < MIN_75_95) fail(`표: ${answer} 의 75~95 이웃 ${count['75-95']}개 < ${MIN_75_95}`);
  if (count['40-74'] < MIN_40_74) fail(`표: ${answer} 의 40~74 이웃 ${count['40-74']}개 < ${MIN_40_74}`);
  if (count['96+'] > MAX_96) fail(`표: ${answer} 의 96+ 이웃 ${count['96+']}개 > ${MAX_96}`);
  if (count['96+'] === 0) without96.push(answer);
  for (const k of Object.keys(band)) band[k] += count[k];
  perAnswer.push(n);
  pairs += n;
}
if (perAnswer.length !== bank.size) fail(`표의 정답 ${perAnswer.length}개 ≠ 은행 ${bank.size}개`);
if (Number(head.answers) !== perAnswer.length) fail(`머리 줄 answers=${head.answers} ≠ ${perAnswer.length}`);
if (Number(head.pairs) !== pairs) fail(`머리 줄 pairs=${head.pairs} ≠ ${pairs}`);

// ── 통계 ──
perAnswer.sort((a, b) => a - b);
const q = (p) => perAnswer[Math.min(perAnswer.length - 1, Math.floor(p * perAnswer.length))];
// 힙 어림(설계서 §5.4 꼴): 어휘 String[](UTF-16 — 객체 24 + 배열 16 + 2×글자, 8바이트 올림 + 참조 4),
// 정답마다 int[] 인덱스 + byte[] 온도(배열 머리 16 씩, 8바이트 올림).
const round8 = (x) => Math.ceil(x / 8) * 8;
let vocabBytes = 16;
for (const w of vocab) vocabBytes += 24 + round8(16 + 2 * w.length) + 4;
const pairBytes = perAnswer.reduce((s, n) => s + round8(16 + 4 * n) + round8(16 + n) + 8, 0);
const categories = new Map();
for (const b of bank.values()) categories.set(b.category, (categories.get(b.category) ?? 0) + 1);

console.log(`표 ${tableFile} — version=${head.version} promptVersion=${head.promptVersion} model=${head.model} created=${head.created}`);
console.log(`정답 은행 ${bank.size}개 · 범주 ${categories.size}개(${[...categories].map(([c, n]) => `${c} ${n}`).join(', ')})`);
console.log(`금칙어 ${blocked.length}개 · 어휘 청소로 뺀 낱말(비하 표현·명사 아님) ${flagged.size}개(대조 ${flagged.size ? '함' : '안 함 — flagged.tsv 없음'})`);
console.log(`같은 말이 있는 정답 ${[...bank.values()].filter((b) => b.aliases.length).length}개`);
console.log(`쌍 ${pairs}개 · 힌트 어휘 ${vocab.size}개(평균 ${(vocabChars / vocab.size).toFixed(1)}자)`);
console.log('띠 분포:');
for (const [k, v] of Object.entries(band)) console.log(`  ${k.padEnd(6)} ${String(v).padStart(7)}  ${((100 * v) / pairs).toFixed(1)}%`);
console.log(`정답당 이웃: 최소 ${perAnswer[0]} · 25% ${q(0.25)} · 가운데 ${q(0.5)} · 75% ${q(0.75)} · 최대 ${perAnswer.at(-1)}`);
console.log(`96+ 이웃이 없는 정답: ${without96.length}개`);
console.log(`파일 크기: ${gz.length} 바이트(gzip) · 풀면 ${Buffer.byteLength(text)} 바이트`);
console.log(`힙 어림: 어휘 ${(vocabBytes / 1048576).toFixed(2)}MB + 쌍 ${(pairBytes / 1048576).toFixed(2)}MB = ${((vocabBytes + pairBytes) / 1048576).toFixed(2)}MB (목표 5MB 이하)`);
if ((vocabBytes + pairBytes) / 1048576 > 5) fail('힙 어림이 5MB 를 넘음');

if (errors.length) {
  console.error(`\n불변식 위반 ${errors.length}건:`);
  for (const e of errors) console.error('  ' + e);
  process.exit(1);
}
console.log('\n불변식 모두 통과');

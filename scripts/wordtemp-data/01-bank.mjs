// 1단계 — 정답 은행 초안: 범주별로 낱말·뜻풀이·같은 말을 만들고(초안), 다른 눈으로 한 번 걸러(검토) data/bank.tsv 를 쓴다.
// 운영자는 data/bank-exclude.txt(한 줄에 낱말 하나)로 빼고 다시 돌리면 된다 — 캐시가 있어 Claude 를 다시 부르지 않는다.
import fs from 'node:fs';
import path from 'node:path';
import { DATA, buildParams, runRequests, normalizeWord, isHangulWord, loadBlocked, isOffensive, readLines, writeBank, MODEL } from './lib.mjs';

const PER_CATEGORY_ASK = 36;
const PER_CATEGORY_KEEP = Number(process.env.WORDTEMP_PER_CATEGORY ?? 24);
const categories = JSON.parse(fs.readFileSync(path.join(DATA, 'categories.json'), 'utf8'));
const blocked = loadBlocked();

const DRAFT_SYSTEM = `너는 한국어 낱말 맞히기 게임의 출제 낱말 목록을 만든다. 한 사람이 낱말을 숨기고 관련 낱말로 힌트를 주면 나머지가 맞히는 게임이다.
주어진 범주에서 정답으로 쓸 낱말을 고른다.

조건:
- 초등학생도 아는 **흔하고 구체적인 명사**. 2~6자, 한글만(띄어쓰기·영문·숫자 없음).
- **뜻이 하나로 읽히는 낱말만.** 동음이의어·다의어(배·눈·다리·차·말·밤·김·감·사과·은행처럼 다른 뜻이 흔한 낱말)는 뺀다.
- 고유명사(사람·회사·상표·지명·작품 이름), 욕설·비속어·혐오 표현, 성인·폭력 낱말은 뺀다.
- 관련 낱말이 풍부해야 한다(특징·부분·쓰임·장소 등으로 힌트를 여러 개 낼 수 있는 낱말).
- 서로 뜻이 거의 같은 낱말을 둘 다 넣지 않는다.
- gloss: 그 낱말의 뜻풀이 한 줄(40자 안팎, 사전처럼 담백하게, 낱말 자체를 쓰지 않는다).
- aliases: **완전히 같은 것을 가리키는** 다른 말·표기 변형·줄임말만(휴대폰 ↔ 핸드폰, 케이크 ↔ 케익). 한글만. 없으면 빈 배열. 비슷하지만 다른 것(상위어·하위 종류)은 넣지 않는다.`;

const DRAFT_SCHEMA = {
  type: 'object',
  properties: {
    words: {
      type: 'array',
      items: {
        type: 'object',
        properties: { word: { type: 'string' }, gloss: { type: 'string' }, aliases: { type: 'array', items: { type: 'string' } } },
        required: ['word', 'gloss', 'aliases'],
        additionalProperties: false,
      },
    },
  },
  required: ['words'],
  additionalProperties: false,
};

const REVIEW_SYSTEM = `너는 한국어 낱말 맞히기 게임의 출제 낱말 목록을 검토한다. 낱말마다 정답으로 써도 되는지 판정한다.
아래 가운데 하나라도 해당하면 ok 를 false 로 하고 reason 에 까닭 코드를 적는다(해당 없으면 ok true, reason 은 빈 글).
- HOMONYM: 흔히 쓰이는 다른 뜻이 있어 헷갈린다(동음이의어·다의어). 예: 배, 눈, 다리, 차, 말, 밤, 김, 감, 사과(과일/사죄), 은행(기관/열매), 풀, 병, 벌, 파리(곤충/도시).
- PROPER: 고유명사(사람·상표·지명·작품).
- OFFENSIVE: 욕설·비속어·혐오·성인 낱말.
- RARE: 초등학생이 모를 만큼 드물거나 전문적이다.
- ABSTRACT: 구체적인 대상이 아니라 힌트를 내기 어렵다.
- NOT_NOUN: 명사가 아니다.
엄격하게 본다 — 애매하면 false.`;

const REVIEW_SCHEMA = {
  type: 'object',
  properties: {
    verdicts: {
      type: 'array',
      items: {
        type: 'object',
        properties: { word: { type: 'string' }, ok: { type: 'boolean' }, reason: { type: 'string' } },
        required: ['word', 'ok', 'reason'],
        additionalProperties: false,
      },
    },
  },
  required: ['verdicts'],
  additionalProperties: false,
};

const drafts = await runRequests(
  'bank-draft',
  categories.map((c) => ({
    id: `bank-${c.id}`,
    params: buildParams({
      system: DRAFT_SYSTEM,
      user: JSON.stringify({ category: c.name, scope: c.hint, count: PER_CATEGORY_ASK }),
      schema: DRAFT_SCHEMA,
    }),
  })),
  { mode: 'sync', estimateUsdPerRequest: 0.04 },
);

// 형식·중복 정리
const seen = new Set();
const draftRows = [];
for (const c of categories) {
  for (const w of drafts.get(`bank-${c.id}`)?.words ?? []) {
    const word = normalizeWord(w.word);
    if (!isHangulWord(word, 2, 6) || seen.has(word) || isOffensive(word, blocked)) continue;
    seen.add(word);
    draftRows.push({ word, category: c.id, gloss: w.gloss.replace(/\s+/g, ' ').trim(), aliases: w.aliases.map(normalizeWord) });
  }
}
const allWords = new Set(draftRows.map((r) => r.word));
for (const r of draftRows) {
  r.aliases = [...new Set(r.aliases)].filter((a) => isHangulWord(a, 1, 10) && a !== r.word && !allWords.has(a) && !isOffensive(a, blocked));
}

const reviews = await runRequests(
  'bank-review',
  categories.map((c) => ({
    id: `review-${c.id}`,
    params: buildParams({
      system: REVIEW_SYSTEM,
      user: JSON.stringify({ category: c.name, words: draftRows.filter((r) => r.category === c.id).map((r) => ({ word: r.word, gloss: r.gloss })) }),
      schema: REVIEW_SCHEMA,
    }),
  })),
  { mode: 'sync', estimateUsdPerRequest: 0.03 },
);
const verdict = new Map();
for (const c of categories) for (const v of reviews.get(`review-${c.id}`)?.verdicts ?? []) verdict.set(normalizeWord(v.word), v);

const excluded = new Set(readLines(path.join(DATA, 'bank-exclude.txt')).map((l) => l.split(/\s/)[0]));
// 운영자가 뺀 같은 말: "낱말 같은말" 한 줄씩(같은 것이 아니거나 다른 뜻이 흔한 말)
const aliasExcluded = new Set(readLines(path.join(DATA, 'bank-alias-exclude.txt')).map((l) => l.split(/\s+/).slice(0, 2).join(' ')));
for (const r of draftRows) r.aliases = r.aliases.filter((a) => !aliasExcluded.has(`${r.word} ${a}`));
const lines = ['# 낱말\t범주\t판정\t까닭\t뜻풀이\t같은 말'];
const kept = [];
const perCategory = new Map();
for (const r of draftRows) {
  const v = verdict.get(r.word);
  let status = !v ? 'NO_VERDICT' : v.ok ? 'OK' : v.reason || 'REJECTED';
  if (excluded.has(r.word)) status = 'OPERATOR_EXCLUDED';
  const n = perCategory.get(r.category) ?? 0;
  if (status === 'OK' && n >= PER_CATEGORY_KEEP) status = 'OVER_QUOTA';
  if (status === 'OK') {
    perCategory.set(r.category, n + 1);
    kept.push(r);
  }
  lines.push([r.word, r.category, status === 'OK' ? 'OK' : 'X', status === 'OK' ? '' : status, r.gloss, r.aliases.join(',')].join('\t'));
}
fs.writeFileSync(path.join(DATA, 'bank-draft.tsv'), lines.join('\n') + '\n');
writeBank(path.join(DATA, 'bank.tsv'), kept, `정답 은행 후보(표 만들기 전) — ${MODEL} 초안 + 검토, 운영자 제외 목록 반영`);
console.log(`초안 ${draftRows.length} → 은행 후보 ${kept.length} (범주별 최대 ${PER_CATEGORY_KEEP})`);
for (const c of categories) console.log(`  ${c.name}: ${perCategory.get(c.id) ?? 0}`);

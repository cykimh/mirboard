// 3단계 — 정답마다 "사람들이 낼 법한 힌트 명사"를 띠별로 넓게 만든다. 점수는 여기서 매기지 않는다(4단계가 따로 매긴다).
// 쓰기:  node 03-candidates.mjs --pilot 6   (6건만 바로 불러 비용을 잰다)
//        node 03-candidates.mjs             (나머지를 Message Batches 로)
// 산출물 data/candidates.tsv (정답 \t 후보,후보,…)
import fs from 'node:fs';
import path from 'node:path';
import { DATA, buildParams, runRequests, loadBank, normalizeWord, isHangulWord, loadBlocked, isOffensive, hintBlockedByAnswer, sha } from './lib.mjs';

const SYSTEM = `너는 한국어 낱말 맞히기 게임을 돕는다. 출제자가 정답 낱말을 숨기고 **관련 명사**를 힌트로 내면 나머지가 정답을 맞힌다.
정답 낱말과 뜻풀이가 주어지면, 출제자들이 힌트로 칠 법한 명사를 가까운 정도별로 넓게 모은다.

- near (20개 안팎): 거의 같은 말·바로 위 갈래·가장 핵심인 특징과 부분. 듣자마자 정답이 떠오르는 낱말.
- strong (60개 안팎): 강하게 이어지는 낱말 — 부분, 재료, 생김새, 쓰임, 하는 일, 쓰는 사람, 있는 곳, 닮은 이웃, 같이 쓰는 물건.
- loose (60개 안팎): 느슨하게 이어지는 낱말 — 넓은 갈래, 관련된 상황·때·느낌·장소, 한 다리 건너 떠오르는 것.

규칙:
- **명사만**, 한글 1~10자, 띄어쓰기 없음. 한 글자 명사(물·손·눈·털 등)도 어울리면 넣는다. 흔한 낱말 위주.
- 정답 낱말이나 그 같은 말을 **포함하는** 낱말은 넣지 않는다(정답 수영 → 수영장 ✗). 정답 안에 들어 있는 2자 이상 낱말도 넣지 않는다(정답 눈사람 → 사람 ✗).
- 고유명사(사람·상표·지명·작품), 욕설·비속어는 넣지 않는다.
- 세 목록 사이에 겹치지 않게.`;
const SCHEMA = {
  type: 'object',
  properties: {
    near: { type: 'array', items: { type: 'string' } },
    strong: { type: 'array', items: { type: 'string' } },
    loose: { type: 'array', items: { type: 'string' } },
  },
  required: ['near', 'strong', 'loose'],
  additionalProperties: false,
};

const bank = loadBank();
const blocked = loadBlocked();
const pilotAt = process.argv.indexOf('--pilot');
const pilot = pilotAt > 0 ? Number(process.argv[pilotAt + 1]) : 0;

const requests = bank.map((b) => ({
  id: `cand-${sha(b.word)}`,
  params: buildParams({ system: SYSTEM, user: JSON.stringify({ answer: b.word, gloss: b.gloss, aliases: b.aliases }), schema: SCHEMA }),
}));
const got = pilot
  ? await runRequests('candidates', requests.slice(0, pilot), { mode: 'sync', estimateUsdPerRequest: 0.03 })
  : await runRequests('candidates', requests, { mode: 'batch' });

const lines = ['# 정답\t힌트 후보(쉼표) — 3단계 산출물, 포함 규칙·형식·금칙어로 거른 뒤'];
let total = 0;
for (const b of bank) {
  const out = got.get(`cand-${sha(b.word)}`);
  if (!out) continue;
  const answers = [b.word, ...b.aliases];
  const words = [...new Set([...out.near, ...out.strong, ...out.loose].map(normalizeWord))].filter(
    (w) => isHangulWord(w) && !isOffensive(w, blocked) && !hintBlockedByAnswer(w, answers),
  );
  total += words.length;
  lines.push(`${b.word}\t${words.join(',')}`);
}
if (!pilot) fs.writeFileSync(path.join(DATA, 'candidates.tsv'), lines.join('\n') + '\n');
console.log(`후보: 정답 ${lines.length - 1}개 · 모두 ${total}개 · 정답당 ${(total / Math.max(1, lines.length - 1)).toFixed(0)}개`);

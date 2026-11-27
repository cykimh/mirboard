// 3b단계(D-322) — 정답마다 **창의적 연상·고유명사 힌트**를 더 모은다. 3단계는 고유명사를 넣지 말라고 했기 때문에
// 인물·캐릭터·작품·상표·지명·속담·이야기·감각 연상이 후보에 거의 없었다.
// 쓰기:  node 03b-creative.mjs --pilot 5  →  node 03b-creative.mjs      산출물 data/candidates-creative.tsv
import fs from 'node:fs';
import path from 'node:path';
import { DATA, buildParams, runRequests, loadBank, loadFlagged, normalizeWord, isHangulWord, loadBlocked, isOffensive, hintBlockedByAnswer, sha } from './lib.mjs';

const SYSTEM = `너는 한국어 낱말 맞히기 게임을 돕는다. 출제자가 정답 낱말을 숨기고 관련 낱말을 힌트로 내면 나머지가 정답을 맞힌다.
정답 낱말과 뜻풀이가 주어지면, 재치 있는 출제자가 낼 법한 **창의적 연상 힌트** 45개 안팎을 모은다. 보통 한국 사람이 "아, 그거!" 하고 알아들을 것만.

- 정답과 묶여 널리 알려진 **고유명사**: 인물(실존·역사), 이야기·만화·영화 속 인물과 캐릭터, 작품 제목, 상표·회사, 나라·도시·명소.
- 속담·관용 표현·옛이야기·동요에서 정답과 짝을 이루는 낱말.
- 비유·상징(정답이 무엇을 상징하는지, 무엇에 빗대어지는지).
- 감각의 연상: 소리·맛·냄새·촉감·색을 가리키는 **명사**.
- 정답이 나오는 유명한 장면·행사·유행, 정답 하면 떠오르는 때와 곳.

규칙: 한글 1~10자, 띄어쓰기 없음, 명사(고유명사 포함). 정답 낱말이나 그 같은 말을 포함하는 낱말, 정답 안에 들어 있는 2자 이상 낱말은 넣지 않는다.
욕설·비하·혐오 표현은 넣지 않는다. 억지스러운 것, 아는 사람만 아는 것은 넣지 않는다 — 모자라면 적게 낸다.`;
const SCHEMA = {
  type: 'object',
  properties: { hints: { type: 'array', items: { type: 'string' } } },
  required: ['hints'],
  additionalProperties: false,
};

const bank = loadBank();
const blocked = loadBlocked();
const flagged = loadFlagged();
const pilotAt = process.argv.indexOf('--pilot');
const pilot = pilotAt > 0 ? Number(process.argv[pilotAt + 1]) : 0;
const requests = bank.map((b) => ({
  id: `creative-${sha(b.word)}`,
  params: buildParams({ system: SYSTEM, user: JSON.stringify({ answer: b.word, gloss: b.gloss, aliases: b.aliases }), schema: SCHEMA }),
}));
const got = pilot
  ? await runRequests('creative', requests.slice(0, pilot), { mode: 'sync', estimateUsdPerRequest: 0.01 })
  : await runRequests('creative', requests, { mode: 'batch' });

const lines = ['# 정답\t창의적 연상·고유명사 힌트 후보(쉼표) — 3b단계 산출물'];
let total = 0;
for (const b of bank) {
  const out = got.get(`creative-${sha(b.word)}`);
  if (!out) continue;
  const answers = [b.word, ...b.aliases];
  const words = [...new Set(out.hints.map(normalizeWord))].filter((w) => {
    const cls = flagged.get(w);
    return isHangulWord(w) && !isOffensive(w, blocked) && !hintBlockedByAnswer(w, answers) && cls !== 'derogatory_or_slur' && cls !== 'not_a_noun';
  });
  total += words.length;
  lines.push(`${b.word}\t${words.join(',')}`);
  if (pilot) console.log(lines.at(-1));
}
if (!pilot) fs.writeFileSync(path.join(DATA, 'candidates-creative.tsv'), lines.join('\n') + '\n');
console.log(`창의 후보: 정답 ${lines.length - 1}개 · 모두 ${total}개`);

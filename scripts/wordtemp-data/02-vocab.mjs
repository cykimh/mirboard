// 2단계 — 힌트 어휘: 범주별 관련 명사 + 어느 정답에나 나올 수 있는 흔한 명사(주제별)를 Claude 로 만든다.
// 직접 만든 목록이다(사전 데이터를 싣지 않는다). 거름: 한글 1~10자, 금칙어 없음. 산출물 data/vocab/<id>.txt
import fs from 'node:fs';
import path from 'node:path';
import { DATA, buildParams, runRequests, normalizeWord, isHangulWord, loadBlocked, isOffensive } from './lib.mjs';

const categories = JSON.parse(fs.readFileSync(path.join(DATA, 'categories.json'), 'utf8'));
export const GLOBAL_THEMES = [
  { id: 'g-onechar', name: '한 글자 명사', hint: '물·불·손·집·꽃처럼 아주 흔한 한 글자 명사' },
  { id: 'g-feeling', name: '감정·성격·느낌', hint: '기쁨·무서움·더위·추위·냄새·소리·맛 같은 느낌과 성질의 명사' },
  { id: 'g-time', name: '시간·때·계절', hint: '아침·주말·여름·방학·생일 같은 때를 가리키는 명사' },
  { id: 'g-shape', name: '색·모양·크기·상태', hint: '빨강·동그라미·크기·무게·속도 같은 명사' },
  { id: 'g-material', name: '재료·물질', hint: '나무·쇠·유리·플라스틱·종이·천·기름 같은 재료와 물질' },
  { id: 'g-people', name: '사람·가족·관계', hint: '엄마·아기·친구·손님·어린이 같은 사람을 가리키는 보통 명사(사람 이름 아님)' },
  { id: 'g-home', name: '집·일상', hint: '부엌·거실·빨래·청소·장보기 같은 집과 일상의 명사' },
  { id: 'g-activity', name: '행동·활동', hint: '여행·요리·운동·공부·놀이·잠·싸움 같은 행동을 가리키는 명사' },
  { id: 'g-society', name: '사회·생활', hint: '돈·일·가게·나라·도시·시골·뉴스·법 같은 생활 명사' },
  { id: 'g-thing', name: '흔한 물건·부분', hint: '뚜껑·손잡이·바퀴·끈·상자·단추·버튼·줄 같은 물건과 부분 이름' },
];

const SYSTEM = `너는 한국어 낱말 맞히기 게임의 힌트 어휘를 만든다. 사람들이 어떤 낱말을 설명하려고 **힌트로 칠 법한 명사**를 모은다.
- 주어진 주제에서 일상적으로 쓰는 **명사만**(보통 명사, 널리 쓰이는 외래어 명사). 한글 1~10자, 띄어쓰기 없음.
- 동사·형용사 활용형(달리는, 예쁜), 조사·어미가 붙은 꼴, 부사, 문장은 넣지 않는다. 명사형(달리기, 더위)은 된다.
- 사람 이름·상표·지명 같은 고유명사, 욕설·비속어·혐오·성인 낱말은 넣지 않는다.
- 겹치지 않게, 흔한 것부터.`;
const SCHEMA = {
  type: 'object',
  properties: { words: { type: 'array', items: { type: 'string' } } },
  required: ['words'],
  additionalProperties: false,
};

if (import.meta.url === `file://${process.argv[1]}`) {
  const blocked = loadBlocked();
  const topics = [
    ...categories.map((c) => ({ id: c.id, name: c.name, hint: `${c.hint} — 이 범주의 물건 이름과, 그것을 설명할 때 쓰는 명사(부분·특징·쓰임·장소·관련된 사람과 일)`, count: 140 })),
    ...GLOBAL_THEMES.map((t) => ({ ...t, count: 90 })),
  ];
  const got = await runRequests(
    'vocab',
    topics.map((t) => ({
      id: `vocab-${t.id}`,
      params: buildParams({ system: SYSTEM, user: JSON.stringify({ topic: t.name, scope: t.hint, count: t.count }), schema: SCHEMA }),
    })),
    { mode: 'sync', estimateUsdPerRequest: 0.02 },
  );
  fs.mkdirSync(path.join(DATA, 'vocab'), { recursive: true });
  let total = 0;
  for (const t of topics) {
    const words = [...new Set((got.get(`vocab-${t.id}`)?.words ?? []).map(normalizeWord))].filter((w) => isHangulWord(w) && !isOffensive(w, blocked));
    fs.writeFileSync(path.join(DATA, 'vocab', `${t.id}.txt`), `# ${t.name} — 힌트 어휘(직접 만든 목록)\n${words.join('\n')}\n`);
    total += words.length;
    console.log(`  ${t.name}: ${words.length}`);
  }
  console.log(`힌트 어휘 ${total}개(주제 사이 겹침 포함)`);
}

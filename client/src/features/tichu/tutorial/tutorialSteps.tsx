import type { ReactNode } from 'react';
import type { Card } from '@/types/tichu';
import { CardChip } from '@/features/tichu/CardChip';
import { PairPractice } from './PairPractice';

/** A2 — 튜토리얼 단계. 콘텐츠 출처: docs/rules-tichu.md (요약). */
export interface TutorialStep {
  title: string;
  body: ReactNode;
}

function CardRow({ cards }: { cards: Card[] }) {
  return (
    <div style={{ display: 'flex', gap: 8, justifyContent: 'center', margin: '12px 0' }}>
      {cards.map((c, i) => (
        <CardChip key={i} card={c} />
      ))}
    </div>
  );
}

const SPECIALS: Card[] = [
  { suit: null, rank: 0, special: 'MAHJONG' },
  { suit: null, rank: 0, special: 'DOG' },
  { suit: null, rank: 0, special: 'PHOENIX' },
  { suit: null, rank: 0, special: 'DRAGON' },
];

export const TUTORIAL_STEPS: TutorialStep[] = [
  {
    title: '미르보드 티츄에 오신 걸 환영합니다',
    body: (
      <p>
        티츄는 4명이 <strong>2:2 팀</strong>으로 즐기는 카드 게임입니다. 마주 보는 두 사람이
        한 팀이 되어, 손패를 가장 먼저 모두 내려놓는 협력 플레이를 합니다. 몇 단계로 핵심만
        빠르게 익혀볼게요.
      </p>
    ),
  },
  {
    title: '목표 — 먼저 목표 점수에 도달하기',
    body: (
      <p>
        라운드마다 점수를 얻고, 두 팀 중 먼저 <strong>목표 점수(기본 1000점)</strong>에 도달한
        팀이 이깁니다. 점수는 5·10·K 등 점수 카드와 트릭 획득, 그리고 티츄 선언 보너스로 만들어집니다.
      </p>
    ),
  },
  {
    title: '카드 구성 — 56장',
    body: (
      <>
        <p>
          4개 슈트(옥·검·탑·별)의 2~A 카드 52장 + 특수 카드 4장 = 총 56장입니다.
        </p>
        <CardRow
          cards={[
            { suit: 'JADE', rank: 2, special: null },
            { suit: 'SWORD', rank: 10, special: null },
            { suit: 'PAGODA', rank: 13, special: null },
            { suit: 'STAR', rank: 14, special: null },
          ]}
        />
      </>
    ),
  },
  {
    title: '족보 — 무엇을 낼 수 있나',
    body: (
      <ul style={{ lineHeight: 1.7, paddingLeft: 18 }}>
        <li>싱글 / 페어 / 트리플</li>
        <li>연속 페어(2페어 이상) / 풀하우스</li>
        <li>스트레이트(5장 이상)</li>
        <li>
          <strong>폭탄</strong> — 같은 숫자 4장 또는 같은 슈트 연속 5장. 어떤 트릭이든 끊습니다.
        </li>
      </ul>
    ),
  },
  {
    title: '카드 패스 — 라운드 시작',
    body: (
      <p>
        카드를 받으면 왼쪽·파트너·오른쪽 상대에게 각각 <strong>1장씩</strong> 건넵니다. 네 명이
        모두 고르면 동시에 교환됩니다. 파트너에게 좋은 카드를 주는 것이 핵심 전략입니다.
      </p>
    ),
  },
  {
    title: '티츄 선언 — 한 방의 보너스',
    body: (
      <p>
        손패를 가장 먼저 비울 자신이 있으면 <strong>티츄(±100)</strong>를, 카드를 받기 전이라면
        <strong> 그랜드 티츄(±200)</strong>를 선언할 수 있습니다. 성공하면 보너스, 실패하면 같은
        점수만큼 감점되는 하이리스크·하이리턴입니다.
      </p>
    ),
  },
  {
    title: '특수 카드 4종',
    body: (
      <>
        <ul style={{ lineHeight: 1.7, paddingLeft: 18 }}>
          <li><strong>마작(1)</strong> — 가장 먼저 내며, 특정 숫자를 내도록 소원을 빌 수 있습니다.</li>
          <li><strong>개</strong> — 차례를 파트너에게 넘깁니다.</li>
          <li><strong>봉황</strong> — 아무 싱글이나 대체하는 와일드(점수 −25).</li>
          <li><strong>용</strong> — 가장 강한 싱글(점수 +25). 단 그 트릭은 상대팀에게 줘야 합니다.</li>
        </ul>
        <CardRow cards={SPECIALS} />
      </>
    ),
  },
  {
    title: '직접 해보기 — 페어 만들기',
    body: (
      <>
        <p>같은 숫자 카드 2장을 골라 페어를 만들어보세요.</p>
        <PairPractice />
      </>
    ),
  },
  {
    title: '준비 완료!',
    body: (
      <p>
        기본은 다 익혔습니다. 처음에는 <strong>봇과 함께</strong> 한 판 연습해보는 걸 추천해요.
        자세한 규칙은 게임 카드의 "자세히" 링크에서 볼 수 있습니다. 즐겁게 플레이하세요!
      </p>
    ),
  },
];

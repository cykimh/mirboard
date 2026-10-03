import type { ReactNode } from 'react';
import type { TutorialStep } from '@/features/tutorial/types';
import type { SkullCard } from '@/types/skullking';
import { SkullCardChip } from '../SkullCardChip';
import { TrickQuiz } from './TrickQuiz';

/**
 * D-121 — 스컬킹 튜토리얼 13단계. 콘텐츠 출처: `docs/rules-skullking.md` (요약).
 *
 * <p>각 단계의 `source` 는 근거 §다(화면에는 그리지 않는다). 룰 문서의 해석(특히 §13)이
 * 바뀌면 같은 커밋으로 여기를 고친다 — `skullkingTutorialSteps.test` 가 핵심 수치·예외 문구를
 * 붙잡고 있어 먼저 빨개진다.
 */

const suit = (s: SkullCard['suit'], rank: number): SkullCard => ({ suit: s, rank, special: null });
const special = (k: SkullCard['special']): SkullCard => ({ suit: null, rank: 0, special: k });

const LIST = { lineHeight: 1.7, paddingLeft: 18 } as const;

/** 카드 줄 — 375px 에서 9장이 가로 스크롤 없이 줄바꿈되도록 wrap + compact. */
function SkullRow({ children }: { children: ReactNode }) {
  return (
    <div
      style={{
        display: 'flex',
        flexWrap: 'wrap',
        gap: 6,
        justifyContent: 'center',
        alignItems: 'center',
        margin: '12px 0',
      }}
    >
      {children}
    </div>
  );
}

const DECK_SAMPLE: SkullCard[] = [
  suit('GREEN', 7),
  suit('PURPLE', 7),
  suit('YELLOW', 7),
  suit('BLACK', 7),
  special('PIRATE'),
  special('MERMAID'),
  special('SKULL_KING'),
  special('TIGRESS'),
  special('ESCAPE'),
];

/** 3자 순환 — 해적 > 인어 > 스컬킹 > 해적. */
const CYCLE: SkullCard[] = [
  special('PIRATE'),
  special('MERMAID'),
  special('SKULL_KING'),
  special('PIRATE'),
];

export const SKULL_KING_TUTORIAL_STEPS: TutorialStep[] = [
  {
    title: '미르보드 스컬킹에 오신 걸 환영합니다',
    source: '§2·§3·§12·§13-⑰',
    body: (
      <>
        <p>
          스컬킹은 <strong>2~8명</strong>이 각자 점수를 겨루는 트릭테이킹 카드 게임입니다.
          라운드마다 내가 이길 트릭 수를 <strong>예측</strong>하고, 정확히 맞혀야 점수를 얻습니다.
        </p>
        <p>
          <strong>10라운드</strong>가 끝났을 때 누적 점수가 가장 높은 사람이 이기고, 동점이면
          공동 승리입니다. 몇 단계로 핵심만 빠르게 익혀볼게요.
        </p>
      </>
    ),
  },
  {
    title: '카드 구성 — 70장',
    source: '§1·§14',
    body: (
      <>
        <p>
          색상 카드 56장 + 특수 카드 14장 = 총 <strong>70장</strong>입니다.
        </p>
        <ul style={LIST}>
          <li>
            <strong>색상 56장</strong> — 초록(앵무새)·보라(지도)·노랑(보물상자)·검정(해적기) 4색
            × 1~14
          </li>
          <li>
            <strong>특수 14장</strong> — 해적 5 · 인어 2 · 스컬킹 1 · 티그리스 1 · 탈출 5
          </li>
        </ul>
        <SkullRow>
          {DECK_SAMPLE.map((c, i) => (
            <SkullCardChip key={i} card={c} compact />
          ))}
        </SkullRow>
        <p>확장 카드(약탈품·크라켄·흰고래)는 쓰지 않습니다.</p>
      </>
    ),
  },
  {
    title: '라운드와 손패',
    source: '§3·§4·§13-⑮⑯',
    body: (
      <ul style={LIST}>
        <li>
          라운드 N 에는 <strong>N장씩</strong> 받고, 그 장수만큼 트릭을 합니다(1라운드 1장 …
          10라운드 10장).
        </li>
        <li>라운드마다 덱 전체를 다시 섞어 나눕니다.</li>
        <li>
          8명이면 덱이 모자라 9·10라운드도 <strong>8장씩</strong> 받고, 남는 6장은 쓰지 않습니다.
        </li>
        <li>
          1라운드 첫 리드는 무작위로 정하고, 라운드마다 다음 좌석으로 한 칸씩 옮깁니다. 라운드
          안에서는 직전 트릭 승자가 리드합니다.
        </li>
      </ul>
    ),
  },
  {
    title: '승수 예측',
    source: '§5·§13-⑪',
    body: (
      <>
        <p>
          카드를 받으면 자기 손패만 보고 이번 라운드에 이길 트릭 수를{' '}
          <strong>0 ~ 손패 장수</strong> 중에서 예측합니다.
        </p>
        <ul style={LIST}>
          <li>
            모두 낼 때까지 남의 예측은 보이지 않고, 전원이 내면 <strong>한꺼번에 공개</strong>
            됩니다.
          </li>
          <li>한 번 낸 예측은 바꿀 수 없습니다.</li>
        </ul>
      </>
    ),
  },
  {
    title: '트릭 — 같은 색 따라내기',
    source: '§3·§6.2·§9',
    body: (
      <>
        <p>리드한 사람부터 차례대로 1장씩 냅니다.</p>
        <ul style={LIST}>
          <li>리드 수트가 정해져 있으면 그 색 카드를 내야 합니다.</li>
          <li>
            <strong>그 색이 손에 없으면</strong> 아무 카드나 낼 수 있습니다.
          </li>
          <li>특수 카드는 언제든 낼 수 있습니다.</li>
          <li>리드 수트가 아직 없으면 제약이 없습니다.</li>
        </ul>
        <p>
          전원이 1장씩 내면 트릭이 끝나고, 이긴 사람이 카드를 모두 가져가 다음 트릭을 리드합니다.
          내 차례에 낼 수 없는 카드는 흐리게 보입니다.
        </p>
      </>
    ),
  },
  {
    title: '리드 수트는 언제 정해지나',
    source: '§6.1·§13-⑤⑥',
    body: (
      <>
        <ul style={LIST}>
          <li>
            첫 카드가 <strong>색상</strong> → 그 색이 리드 수트입니다.
          </li>
          <li>
            첫 카드가 <strong>탈출</strong>(탈출 선언 티그리스 포함) → 보류했다가, 처음 나오는 색상
            카드의 색이 리드 수트가 됩니다. 사이에 캐릭터가 끼어도 계속 보류합니다.
          </li>
          <li>
            첫 카드가 <strong>캐릭터</strong>(해적·인어·스컬킹·해적 선언 티그리스) → 리드 수트가
            없고 모두 자유롭게 냅니다.
          </li>
        </ul>
        <p>트릭 도중에 리드 수트가 정해지면 그다음 사람부터 따라내기 의무가 생깁니다.</p>
      </>
    ),
  },
  {
    title: '검정은 으뜸패',
    source: '§7.1·§13-⑦⑧',
    body: (
      <>
        <p>
          캐릭터(해적 선언 티그리스 포함)가 없는 트릭은 탈출을 먼저 빼고 색상 카드끼리 겨룹니다.
        </p>
        <ul style={LIST}>
          <li>
            검정이 있으면 → <strong>가장 높은 검정</strong>이 이깁니다.
          </li>
          <li>
            검정이 없으면 → <strong>리드 수트 중 가장 높은 숫자</strong>가 이깁니다.
          </li>
          <li>리드 수트가 아닌 다른 색(검정 제외)은 숫자가 커도 집니다.</li>
        </ul>
        <SkullRow>
          <SkullCardChip card={suit('GREEN', 14)} compact />
          <SkullCardChip card={suit('BLACK', 1)} compact />
        </SkullRow>
        <p>
          예: 초록 리드에서 <strong>초록이 없어</strong> 검정 1을 낸 사람이 초록 14를 이깁니다.
        </p>
      </>
    ),
  },
  {
    title: '해적·인어·스컬킹 — 물고 물리는 셋',
    source: '§7·§8·§13-①③',
    body: (
      <>
        <p>
          세 캐릭터는 모두 <strong>모든 색상 카드</strong>(검정 포함)를 이깁니다. 셋 사이는
          가위바위보처럼 물고 물립니다.
        </p>
        <SkullRow>
          {CYCLE.map((c, i) => (
            <span key={i} style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
              {i > 0 && <span aria-hidden="true">&gt;</span>}
              <SkullCardChip card={c} compact />
            </span>
          ))}
        </SkullRow>
        <ul style={LIST}>
          <li>해적 &gt; 인어, 인어 &gt; 스컬킹, 스컬킹 &gt; 해적</li>
          <li>
            <strong>셋이 모두 나오면 인어</strong>가 이깁니다.
          </li>
          <li>
            같은 종류가 여러 장이면 <strong>먼저 낸 카드</strong>가 이깁니다.
          </li>
        </ul>
      </>
    ),
  },
  {
    title: '티그리스와 탈출',
    source: '§1·§6.2·§7.2·§13-②③④⑩',
    body: (
      <>
        <SkullRow>
          <SkullCardChip card={special('TIGRESS')} declaredAs="PIRATE" compact />
          <SkullCardChip card={special('TIGRESS')} declaredAs="ESCAPE" compact />
          <SkullCardChip card={special('ESCAPE')} compact />
        </SkullRow>
        <ul style={LIST}>
          <li>
            <strong>티그리스</strong>는 낼 때 해적 또는 탈출로 선언합니다. 승패·동점·보너스 모두
            선언한 쪽으로 계산합니다. 손패에서 티그리스를 고르면 '해적/탈출' 버튼이 나옵니다.
          </li>
          <li>
            <strong>탈출</strong>은 반드시 집니다. 단 모두 탈출(탈출 선언 티그리스 포함)이면 먼저
            낸 탈출이 이깁니다.
          </li>
        </ul>
      </>
    ),
  },
  {
    title: '점수 — 예측을 맞혔나',
    source: '§10·§11·§13-⑫⑬',
    body: (
      <>
        <ul style={LIST}>
          <li>
            1 이상 예측 <strong>적중</strong>: 승수 ×20 + 보너스
          </li>
          <li>
            1 이상 예측 <strong>실패</strong>: 차이 ×10 감점 (보너스 없음)
          </li>
          <li>
            0 예측 <strong>성공</strong>: 라운드 번호 ×10
          </li>
          <li>
            0 예측 <strong>실패</strong>: 라운드 번호 ×10 감점 (보너스 없음)
          </li>
        </ul>
        <p>
          예: 5라운드에 3을 예측해 3승이면 +60, 1승이면 −20. 7라운드에 0을 예측해 0승이면 +70,
          2승이면 −70.
        </p>
      </>
    ),
  },
  {
    title: '보너스 점수',
    source: '§11·§13-⑨⑩',
    body: (
      <>
        <p>
          예측을 <strong>맞힌 사람만</strong> 받고, 틀리면 전부 사라집니다.
        </p>
        <ul style={LIST}>
          <li>
            내가 딴 트릭에 들어 있으면: 노랑·보라·초록 14 각 <strong>+10</strong>, 검정 14{' '}
            <strong>+20</strong>
          </li>
          <li>
            해적으로 이긴 트릭의 인어 1장당 <strong>+20</strong>
          </li>
          <li>
            스컬킹으로 이긴 트릭의 해적 1장당 <strong>+30</strong>
          </li>
          <li>
            인어로 스컬킹을 이기면 <strong>+40</strong>
          </li>
        </ul>
        <p>
          셋이 다 나온 트릭을 인어가 이기면 캐릭터 포획 보너스는 <strong>+40 뿐</strong>입니다(진
          해적은 세지 않습니다). 14 보너스는 별도로 더합니다.
        </p>
        <p>티그리스는 선언대로 셉니다 — 해적 선언이면 해적으로 세고, 탈출 선언이면 세지 않습니다.</p>
      </>
    ),
  },
  {
    title: '직접 해보기 — 누가 이길까?',
    source: '§6.1·§6.2·§7·§7.1·§7.2·§11·§13-⑨⑩',
    body: (
      <>
        <p>카드는 왼쪽부터 낸 순서입니다. 트릭을 이길 카드를 골라 보세요.</p>
        <TrickQuiz />
      </>
    ),
  },
  {
    title: '준비 완료!',
    source: '§12·§13-⑱',
    body: (
      <>
        <ul style={LIST}>
          <li>
            방을 만들 때 <strong>'🤖 빈 좌석 봇으로 채우기'</strong>를 켜면 봇과 함께 연습할 수
            있습니다.
          </li>
          <li>
            게임 중에는 상단 <strong>'규칙'</strong> 버튼으로 이 안내를 다시 볼 수 있습니다. 턴
            제한이 있는 방이면 보는 동안에도 타이머가 흐릅니다.
          </li>
          <li>게임 도중 나가면 탈주로 처리되고, 내 자리는 자동 조종이 남은 게임을 플레이합니다.</li>
        </ul>
        <p>
          자세한 규칙은 게임 카드의 "자세히" 링크에서 볼 수 있습니다. 즐겁게 플레이하세요!
        </p>
      </>
    ),
  },
];

import type { TutorialStep } from '@/features/tutorial/types';
import type { OneCardCard, OneCardSuit } from '@/types/onecard';
import { OneCardCardChip } from '../OneCardCardChip';
import { PlayQuiz } from './PlayQuiz';
import { ReactionPractice } from './ReactionPractice';

/**
 * D-129 — 원카드 튜토리얼 12단계. 콘텐츠 출처: `docs/rules-onecard.md` (요약).
 *
 * <p>각 단계의 `source` 는 근거 §다(화면에는 그리지 않는다). 룰을 바꾸면 같은 커밋으로 여기를 고친다 —
 * `onecardTutorialSteps.test` 가 핵심 수치·예외 문구를 붙잡고 있어 먼저 빨개진다.
 */

const c = (suit: OneCardSuit, rank: number): OneCardCard => ({ suit, rank, joker: null });
const joker = (kind: 'BLACK' | 'COLOR'): OneCardCard => ({ suit: null, rank: 0, joker: kind });

const LIST = { lineHeight: 1.7, paddingLeft: 18 } as const;

/** 카드 줄 — 375px 에서 가로 스크롤 없이 줄바꿈되도록 wrap + compact. */
function CardRow({ cards }: { cards: OneCardCard[] }) {
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
      {cards.map((card, i) => (
        <OneCardCardChip key={i} card={card} compact />
      ))}
    </div>
  );
}

export const ONE_CARD_TUTORIAL_STEPS: TutorialStep[] = [
  {
    title: '미르보드 원카드에 오신 걸 환영합니다',
    source: '§2·§11',
    body: (
      <>
        <p>
          원카드는 <strong>2~6명</strong>이 각자 겨루는 카드 게임입니다. 손패를 <strong>먼저 다 내는 사람</strong>이
          이기고, 나머지는 남은 장수가 적은 순으로 순위가 매겨집니다.
        </p>
        <p>
          카드가 <strong>1장 남는 순간</strong>이 이 게임의 백미입니다 — 화면에 뜨는 버튼을 누가 먼저 누르느냐의
          싸움이죠. 몇 단계로 핵심만 빠르게 익혀볼게요.
        </p>
      </>
    ),
  },
  {
    title: '카드 구성 — 54장',
    source: '§1',
    body: (
      <>
        <p>
          트럼프 52장(♠♥♦♣ × A~K)에 <strong>조커 2장</strong>(흑백·컬러)을 더한 54장입니다.
        </p>
        <CardRow cards={[c('HEART', 2), c('SPADE', 1), joker('BLACK'), joker('COLOR')]} />
        <ul style={LIST}>
          <li>
            <strong>공격</strong> — 2(+2) · A(+3) · 흑백 조커(+5) · 컬러 조커(+7)
          </li>
          <li>
            <strong>특수</strong> — J 건너뛰기 · Q 방향 반전 · K 한 번 더 · 7 무늬 지정
          </li>
          <li>
            <strong>일반</strong> — 3·4·5·6·8·9·10
          </li>
        </ul>
      </>
    ),
  },
  {
    title: '분배와 시작',
    source: '§2·§3',
    body: (
      <>
        <p>
          각자 <strong>7장</strong>씩 받고, 남은 카드가 뽑을 더미가 됩니다. 더미 맨 위 카드를 뒤집어 시작합니다.
        </p>
        <p>
          시작 카드는 언제나 <strong>일반 카드</strong>입니다 — 공격·특수 카드가 나오면 더미 맨 아래로 보내고 다시
          뒤집어요. 첫 차례는 서버가 무작위로 고릅니다.
        </p>
      </>
    ),
  },
  {
    title: '내 차례 — 내기 또는 먹기',
    source: '§4·§7',
    body: (
      <>
        <ul style={LIST}>
          <li>
            카드를 <strong>1장 내거나</strong>, 더미에서 <strong>1장 먹습니다</strong>.
          </li>
          <li>낼 수 있어도 일부러 먹을 수 있습니다.</li>
          <li>
            먹으면 <strong>차례가 끝납니다</strong> — 방금 먹은 카드는 이번 차례에 낼 수 없어요.
          </li>
          <li>턴 제한을 넘기면 먹은 것으로 칩니다.</li>
        </ul>
      </>
    ),
  },
  {
    title: '낼 수 있는 카드',
    source: '§5',
    body: (
      <>
        <p>공격받는 중이 아닐 때, 맨 위 카드와 비교해 다음 중 하나면 낼 수 있습니다.</p>
        <ul style={LIST}>
          <li>
            <strong>같은 무늬</strong> — 7 로 무늬를 지정했다면 그 무늬
          </li>
          <li>
            <strong>같은 숫자</strong>
          </li>
          <li>
            <strong>조커</strong>는 언제나 · 맨 위가 조커면 아무 카드나
          </li>
        </ul>
        <CardRow cards={[c('HEART', 9), c('HEART', 3), c('SPADE', 9)]} />
        <p style={{ textAlign: 'center', fontSize: '0.85rem', opacity: 0.8 }}>맨 위 ♥9 → ♥3(같은 무늬)·♠9(같은 숫자)</p>
        <p>
          <strong>7 은 와일드가 아닙니다</strong> — 다른 카드처럼 무늬나 숫자가 맞아야 냅니다.
        </p>
      </>
    ),
  },
  {
    title: '공격과 반격',
    source: '§5.3·§6',
    body: (
      <>
        <p>
          공격 카드를 내면 다음 사람은 <strong>공격받는 중</strong>이 됩니다. 맨 위 공격과{' '}
          <strong>같거나 센</strong> 공격 카드로 반격하면 누적이 다음 사람에게 넘어갑니다.
        </p>
        <p style={{ textAlign: 'center' }}>
          세기: <strong>2 &lt; A &lt; 흑백 조커 &lt; 컬러 조커</strong>
        </p>
        <ul style={LIST}>
          <li>같은 숫자는 무늬와 무관 · 다른 숫자(2 위의 A)는 무늬가 같아야 · 조커는 무늬와 무관</li>
          <li>
            공격받는 중에는 일반 카드와 7·J·Q·K 를 낼 수 없습니다. 반격하지 않으면{' '}
            <strong>누적 장수만큼 먹습니다</strong>.
          </li>
        </ul>
      </>
    ),
  },
  {
    title: '특수 카드 — J·Q·K·7',
    source: '§8',
    body: (
      <>
        <CardRow cards={[c('CLUB', 11), c('CLUB', 12), c('CLUB', 13), c('CLUB', 7)]} />
        <ul style={LIST}>
          <li>
            <strong>J</strong> — 다음 사람을 건너뜁니다
          </li>
          <li>
            <strong>Q</strong> — 진행 방향을 뒤집습니다
          </li>
          <li>
            <strong>K</strong> — 같은 사람이 한 번 더 합니다
          </li>
          <li>
            <strong>7</strong> — 낼 때 다음 기준 무늬를 지정합니다
          </li>
        </ul>
        <p>살아 있는 사람이 2명이면 J·Q 도 "한 번 더"로 동작합니다.</p>
      </>
    ),
  },
  {
    title: '원카드! 잡기!',
    source: '§9',
    body: (
      <>
        <p>
          카드를 내서 <strong>1장이 남으면 3초 경쟁</strong>이 열립니다. 화면 어딘가 — 매번 다른 자리 — 에 버튼이
          뜹니다.
        </p>
        <ul style={LIST}>
          <li>
            1장 남은 사람은 <strong>"원카드!"</strong> — 먼저 누르면 안전합니다
          </li>
          <li>
            다른 사람은 <strong>"잡기!"</strong> — 먼저 누르면 상대가 <strong>벌칙 1장</strong>을 먹습니다
          </li>
          <li>3초 동안 아무도 안 누르면 벌칙 없이 닫힙니다</li>
        </ul>
        <p>
          서버에 먼저 도착한 누름이 이깁니다. 봇도 1.0~2.5초 사이에 누릅니다. 단축키는 없어요 — 버튼을 찾아
          누르는 것이 이 게임입니다.
        </p>
      </>
    ),
  },
  {
    title: '파산과 탈락',
    source: '§10',
    body: (
      <>
        <ul style={LIST}>
          <li>
            먹은 뒤 손패가 <strong>20장 이상</strong>이면 <strong>파산</strong> — 탈락합니다. 벌칙 1장으로는 파산하지
            않습니다.
          </li>
          <li>
            게임 중에 나가면 <strong>탈주</strong>로 탈락하고 최하위가 됩니다. 이미 탈락한 뒤에 나가는 것은 탈주가
            아닙니다.
          </li>
          <li>탈락한 사람의 손패는 뽑을 더미 맨 아래로 들어가고, 차례에서 빠집니다.</li>
        </ul>
      </>
    ),
  },
  {
    title: '종료와 순위',
    source: '§11',
    body: (
      <>
        <ul style={LIST}>
          <li>
            누군가 <strong>마지막 카드를 내면</strong> 바로 끝 — 그 사람이 1등입니다.
          </li>
          <li>
            나머지는 <strong>남은 장수가 적은 순</strong>, 그 아래 파산자, 맨 아래 탈주자입니다. 장수가 같으면 공동
            순위(1, 1, 3)입니다.
          </li>
          <li>
            한 명만 남거나 남은 사람이 모두 봇이면 끝납니다. 더 진행할 수 없으면(전원 패스) 남은 장수로 순위를
            매깁니다.
          </li>
        </ul>
      </>
    ),
  },
  {
    title: '연습 — 낼 수 있을까?',
    source: '§5·§6.2',
    body: (
      <>
        <p>맨 위 카드와 상황을 보고, 오른쪽 카드를 지금 낼 수 있는지 골라 보세요.</p>
        <PlayQuiz />
      </>
    ),
  },
  {
    title: '연습 — 원카드! 반응',
    source: '§9',
    body: (
      <>
        <p>
          마지막으로 버튼 누르기를 연습해 봐요. 준비됐으면 '시작하기'로 판에 들어가세요 — 규칙은 게임판의{' '}
          <strong>규칙</strong> 버튼으로 언제든 다시 볼 수 있습니다.
        </p>
        <ReactionPractice />
      </>
    ),
  },
];

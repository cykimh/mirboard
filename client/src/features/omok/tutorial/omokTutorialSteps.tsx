import type { TutorialStep } from '@/features/tutorial/types';
import { ForbiddenQuiz } from './ForbiddenQuiz';
import { MiniBoard } from './MiniBoard';

/**
 * 오목 튜토리얼 — 콘텐츠 출처 `docs/rules-omok.md`(요약). 룰을 바꾸면 같은 커밋으로 여기를 고친다 — `omokTutorialSteps.test` 가 핵심
 * 문구를 붙잡는다.
 */
const LIST = { lineHeight: 1.7, paddingLeft: 18 } as const;

export const OMOK_TUTORIAL_STEPS: TutorialStep[] = [
  {
    title: '오목에 오신 걸 환영합니다',
    body: (
      <>
        <p>15×15 판에 흑과 백이 번갈아 한 수씩 둡니다. 가로·세로·대각선으로 <strong>다섯 개를 먼저 잇는 쪽</strong>이 이깁니다.</p>
        <MiniBoard
          black={[
            { x: 2, y: 4 },
            { x: 3, y: 4 },
            { x: 4, y: 4 },
            { x: 5, y: 4 },
            { x: 6, y: 4 },
          ]}
          white={[
            { x: 3, y: 3 },
            { x: 4, y: 5 },
            { x: 5, y: 3 },
            { x: 6, y: 5 },
          ]}
          winLine={[
            { x: 2, y: 4 },
            { x: 6, y: 4 },
          ]}
          label="흑 5목 — 승리"
        />
      </>
    ),
    source: '§1·§5',
  },
  {
    title: '두는 법 — 두 번 눌러 확정',
    body: (
      <ul style={LIST}>
        <li>판을 누르면 가장 가까운 자리에 <strong>미리보기 돌</strong>이 떠요.</li>
        <li>미리보기를 <strong>한 번 더 누르거나</strong> 아래 <strong>[여기에 두기]</strong>를 누르면 둡니다.</li>
        <li>누른 채 끌면 미리보기가 손가락보다 조금 위를 따라와요 — 손가락에 가리지 않게.</li>
        <li>컴퓨터에서는 마우스를 올리면 미리보기가 뜨고, 설정으로 한 번 클릭에 둘 수도 있어요.</li>
      </ul>
    ),
    source: 'D-180',
  },
  {
    title: '1:1 과 2:2 팀전',
    body: (
      <ul style={LIST}>
        <li>1:1 은 흑·백 한 사람씩. 색은 매 판 무작위로 정해요.</li>
        <li>
          2:2 는 마주 앉은 두 사람이 한 팀이고 <strong>같은 색 돌을 번갈아</strong> 둡니다 — 흑(가) → 백(나) → 흑(가의 팀원) → 백(나의 팀원) …
        </li>
        <li>팀 색으로 5목이 되면 누가 뒀든 팀이 함께 이겨요.</li>
      </ul>
    ),
    source: '§2·§3·§4',
  },
  {
    title: '룰 세 가지',
    body: (
      <ul style={LIST}>
        <li>
          <strong>렌주</strong>(기본) — 흑만 33·44·장목(6목 이상) 금지. 흑은 정확히 5, 백은 5 이상이면 이겨요.
        </li>
        <li>
          <strong>삼삼만 금지</strong> — 흑만 33 금지. 둘 다 5 이상이면 이겨요.
        </li>
        <li>
          <strong>자유 오목</strong> — 금수 없음. 흑이 꽤 유리해요.
        </li>
        <li>방장이 대기실에서 고릅니다.</li>
      </ul>
    ),
    source: '§2.1·§6.1',
  },
  {
    title: '금수 — 흑이 둘 수 없는 자리',
    body: (
      <>
        <p>흑 차례에는 금수 자리에 작은 ✕ 가 보이고, 그 자리에는 둘 수 없어요. 계산은 서버가 해요.</p>
        <MiniBoard
          black={[
            { x: 4, y: 2 },
            { x: 4, y: 3 },
            { x: 2, y: 4 },
            { x: 3, y: 4 },
          ]}
          forbidden={[{ x: 4, y: 4, kind: 'DOUBLE_THREE' }]}
          label="33 — 열린 3 두 개가 한 번에"
        />
        <MiniBoard
          black={[
            { x: 1, y: 4 },
            { x: 2, y: 4 },
            { x: 3, y: 4 },
            { x: 5, y: 4 },
            { x: 6, y: 4 },
          ]}
          forbidden={[{ x: 4, y: 4, kind: 'OVERLINE' }]}
          label="장목 — 여섯 개 이상"
        />
      </>
    ),
    source: '§6',
  },
  {
    title: '5목이 먼저',
    body: (
      <p>
        흑이 둔 수가 <strong>5목을 만들면</strong> 그 수가 동시에 33·44 모양이어도 이겨요. 렌주에서 장목만 생기면 금수입니다.
      </p>
    ),
    source: '§5',
  },
  {
    title: '시간 초과·기권·나가기',
    body: (
      <ul style={LIST}>
        <li>시간이 다 되면 서버가 대신 한 수 둬요(바로 전 수에서 가장 가까운 빈칸).</li>
        <li>
          같은 사람이 <strong>두 번 연속</strong> 시간 초과면 집니다 — 2:2 는 팀이 집니다.
        </li>
        <li>기권·나가기도 그 편이 져요. 판이 가득 차면 무승부예요.</li>
      </ul>
    ),
    source: '§7·§8·§9·§10',
  },
  {
    title: '연습 — 어디가 금수일까?',
    body: <ForbiddenQuiz />,
    source: '§6.4',
  },
];

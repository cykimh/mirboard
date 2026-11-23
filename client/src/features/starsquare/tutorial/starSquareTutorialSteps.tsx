import type { TutorialStep } from '@/features/tutorial/types';
import { StarGrid } from '../StarGrid';

/**
 * 별자리 수판 튜토리얼 — 콘텐츠 출처 `docs/rules-starsquare.md`. 룰을 바꾸면 같은 커밋으로 여기를 고친다 — `starSquareTutorial.test` 가
 * 핵심 문구를 붙잡는다.
 */
const LIST = { lineHeight: 1.7, paddingLeft: 18 } as const;

const LO_SHU = [2, 7, 6, 9, 5, 1, 4, 3, 8];
const LO_SHU_CLUES = [2, 0, 0, 0, 0, 1, 0, 0, 0];

export const STAR_SQUARE_TUTORIAL_STEPS: TutorialStep[] = [
  {
    title: '마방진이란',
    body: (
      <>
        <p>
          N×N 판에 <strong>1부터 N²까지 한 번씩</strong> 넣어 모든 <strong>가로·세로·두 대각선의 합을 같게</strong> 만드는 퍼즐이에요.
        </p>
        <p>
          그 합은 N(N²+1)/2 — 3×3 은 <strong>15</strong>, 4×4 는 <strong>34</strong>, 5×5 는 <strong>65</strong>입니다. 합이 맞은 줄은 별자리 선으로
          이어져요.
        </p>
        <div className="sq-tutorial-board">
          <StarGrid n={3} board={LO_SHU} clues={LO_SHU_CLUES} selected={-1} editable={false} label="완성된 3×3 마방진" />
        </div>
      </>
    ),
    source: '§1',
  },
  {
    title: '단서 별',
    body: (
      <ul style={LIST}>
        <li>퍼즐은 몇 칸을 처음부터 채워 둡니다 — <strong>단서 별</strong>(★)이에요. 단서는 바꿀 수 없어요.</li>
        <li>단서와 맞기만 하면 <strong>어떤 마방진이든 정답</strong>입니다. 서버가 아는 답과 달라도 괜찮아요.</li>
        <li>빈 칸이 하나만 남은 줄은 그 칸이 바로 정해져요(합 − 나머지). 거기서부터 풀어 보세요.</li>
      </ul>
    ),
    source: '§4·§5',
  },
  {
    title: '넣기·지우기',
    body: (
      <ul style={LIST}>
        <li>칸을 누르고 숫자판에서 숫자를 누릅니다. 이미 쓴 숫자를 누르면 그 숫자가 <strong>이 칸으로 옮겨져요</strong>.</li>
        <li>[지우기]·[되돌리기]와, 내가 넣은 숫자를 모두 지우는 [리셋](두 번 눌러야 지워져요)이 있어요. 숫자를 잠깐 겹쳐 써도 괜찮지만, 겹친 숫자는 채운 칸으로 세지 않아요.</li>
        <li>키보드: 숫자(두 자리는 이어서 — 1, 2 → 12), 방향키로 칸 이동, Backspace 로 지우기, Ctrl/⌘+Z 로 되돌리기.</li>
      </ul>
    ),
    source: '§7·§13',
  },
  {
    title: '줄 합 칩 읽기',
    body: (
      <ul style={LIST}>
        <li>가로 줄은 오른쪽, 세로 줄은 아래, 두 대각선은 그 대각선이 끝나는 아래 모서리에 합이 작게 보여요.</li>
        <li>
          <strong>✦ 맞음</strong> · <strong>▽ 모자람</strong>(다 찼는데 작음) · <strong>△ 넘침</strong>(이미 큼) — 색만이 아니라 모양으로도
          구분합니다.
        </li>
        <li>줄 합 표시는 내 판을 더해 보여 줄 뿐이고, 완성 판정은 서버가 합니다.</li>
      </ul>
    ),
    source: '§3.2·§13',
  },
  {
    title: '모두 같이 — 15초 마감',
    body: (
      <ul style={LIST}>
        <li>모두에게 <strong>같은 퍼즐</strong>이 동시에 열리고, 각자 자기 판을 풉니다. 남의 판 숫자는 보이지 않아요 — 채운 칸·맞은 줄 수만.</li>
        <li>
          누군가 처음 완성하면 남은 사람에게 <strong>15초</strong>가 주어져요. 모두 완성하거나 퍼즐 제한 시간이 끝나도 퍼즐이 끝납니다.
        </li>
        <li>먼저 완성한 사람은 기다리는 동안 <strong>아직 푸는 사람의 판을 볼 수 있어요</strong>(보기만).</li>
        <li>퍼즐이 끝나면 모두의 판과 정답 하나를 나란히 보여 주고, 잠시 뒤 다음 퍼즐이 열려요.</li>
      </ul>
    ),
    source: '§6',
  },
  {
    title: '점수',
    body: (
      <ul style={LIST}>
        <li>퍼즐마다 순위를 매겨요 — 완성한 사람은 완성 순서, 못 한 사람은 <strong>맞은 줄 → 채운 칸</strong> 순.</li>
        <li>점수는 (인원 − 순위) — 4명이면 3·2·1·0점. 판이 끝나면 점수 합, 같으면 1등 횟수로 순위를 정합니다.</li>
        <li>혼자 하기도 됩니다(기록은 남지 않아요). 봇은 난이도에 따라 정해진 빠르기로 별을 채워요.</li>
      </ul>
    ),
    source: '§8·§10',
  },
];

import type { GameTutorial, TutorialStep } from '@/features/tutorial/types';
import { ScoreQuiz } from './ScoreQuiz';

const LIST = { lineHeight: 1.7, paddingLeft: 18 } as const;

/**
 * 요트 튜토리얼 6단계. 콘텐츠 출처: `docs/rules-yacht.md`(요약). 룰을 바꾸면 같은 커밋으로 여기를 고친다 —
 * `yachtTutorial.test` 가 핵심 수치를 붙잡고 있다.
 */
export const YACHT_TUTORIAL_STEPS: TutorialStep[] = [
  {
    title: '요트에 오신 걸 환영합니다',
    source: '§2·§3',
    body: (
      <>
        <p>
          요트는 <strong>1~4명</strong>이 주사위 5개로 겨루는 게임입니다. <strong>12라운드</strong> 동안 차례마다 점수표의
          칸을 하나씩 채우고, <strong>총점이 가장 높은 사람</strong>이 이깁니다.
        </p>
        <p>혼자서도 할 수 있어요 — 혼자 한 판은 전적에 남지 않습니다.</p>
      </>
    ),
  },
  {
    title: '한 차례 — 세 번까지 굴린다',
    source: '§4',
    body: (
      <ul style={LIST}>
        <li>
          첫 굴림은 5개 전부. 마음에 드는 주사위를 눌러 <strong>고정</strong>하고 나머지만 다시 굴립니다.
        </li>
        <li>
          굴림은 차례마다 <strong>최대 3번</strong>. 언제든 멈추고 기록할 수 있습니다.
        </li>
        <li>
          기록은 <strong>빈 칸 하나</strong>에 꼭 해야 합니다. 조건이 안 맞는 칸에 적으면 <strong>0점</strong>(버리기).
        </li>
      </ul>
    ),
  },
  {
    title: '위쪽 6칸과 보너스',
    source: '§5·§5.1',
    body: (
      <>
        <p>
          <strong>1~6 칸</strong>은 그 눈인 주사위의 합입니다(예: 5 칸에 5 5 5 → 15점).
        </p>
        <p>
          위쪽 6칸의 합이 <strong>63점 이상</strong>이면 <strong>+35점</strong> 보너스. 칸마다 &quot;그 눈 3개&quot;면 딱
          63점입니다.
        </p>
      </>
    ),
  },
  {
    title: '아래쪽 6칸',
    source: '§5',
    body: (
      <ul style={LIST}>
        <li>
          <strong>초이스</strong> — 다섯 개의 합. 아무 때나.
        </li>
        <li>
          <strong>포 카드</strong> — 같은 눈 4개 이상이면 다섯 개의 합.
        </li>
        <li>
          <strong>풀 하우스</strong> — 같은 눈 3개 + 2개(5개 모두 같아도 됨)면 다섯 개의 합.
        </li>
        <li>
          <strong>스몰 스트레이트</strong> — 연속 4개면 15점. <strong>라지 스트레이트</strong> — 연속 5개면 30점.
        </li>
        <li>
          <strong>요트</strong> — 5개 모두 같으면 50점.
        </li>
      </ul>
    ),
  },
  {
    title: '어디에 넣을까?',
    source: '§5',
    body: <ScoreQuiz />,
  },
  {
    title: '차례 제한과 자동 기록',
    source: '§6',
    body: (
      <>
        <p>
          턴 제한이 있는 방은 <strong>한 차례 전체</strong>에 시간이 걸립니다(굴리거나 고정을 바꿔도 다시 세지 않아요).
        </p>
        <p>
          시간이 다 되면 서버가 (안 굴렸으면 한 번 굴리고) <strong>점수가 가장 높은 빈 칸</strong>에 자동으로 기록합니다.
        </p>
        <p>
          대기실에서 진행 방식을 <strong>모두 같이</strong>로 고르면 라운드마다 모두가 동시에 굴리고, 다 기록하면 다음
          라운드로 갑니다. 시간 제한도 라운드 전체에 하나예요.
        </p>
      </>
    ),
  },
];

/** 요트 튜토리얼 선언 — 열람 키는 게임 전용(D-121). 다이얼로그는 body 포털이라 퀴즈 토큰을 `yt-tokens` 로 푼다. */
export const YACHT_TUTORIAL: GameTutorial = {
  steps: YACHT_TUTORIAL_STEPS,
  description: '요트 기본 규칙 안내 튜토리얼',
  seenKey: 'mirboard.tutorial.yacht.seen.v1',
  bodyClassName: 'yt-tokens',
};

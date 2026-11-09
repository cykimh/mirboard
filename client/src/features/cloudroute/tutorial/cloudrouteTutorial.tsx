import type { GameTutorial, TutorialStep } from '@/features/tutorial/types';

/**
 * D-213 — 구름항로 튜토리얼. 콘텐츠 출처: `docs/rules-cloudroute.md` (요약). 각 단계의 `source` 는 근거 §(화면에는 그리지 않는다).
 * 룰을 바꾸면 같은 커밋으로 여기를 고친다 — `cloudrouteTutorial.test` 가 핵심 수치를 붙잡는다.
 */
const LIST = { lineHeight: 1.7, paddingLeft: 18 } as const;

export const CLOUD_ROUTE_TUTORIAL_STEPS: TutorialStep[] = [
  {
    title: '구름항로에 오신 걸 환영합니다',
    source: '§1·§3·§12',
    body: (
      <>
        <p>
          하늘섬을 도는 비행선 상인이 되어 <strong>섬의 터를 사고 건물을 세워 통행료를 받는</strong> 2~4인 게임입니다. 모두
          <strong> 1,500G</strong> 로 시작합니다.
        </p>
        <p>
          정해진 라운드(4인 <strong>20</strong>·3인 24·2인 30)가 끝나면 <strong>총자산</strong>(현금 + 땅값 + 건설비)이 가장 큰
          사람이 이깁니다. 파산하면 탈락하고 남은 사람끼리 계속합니다.
        </p>
      </>
    ),
  },
  {
    title: '굴리고 움직이기',
    source: '§4·§5',
    body: (
      <ul style={LIST}>
        <li>주사위 두 개의 합만큼 시계 방향으로 갑니다.</li>
        <li>
          <strong>출발 항구</strong>를 지나거나 멈추면 급여 <strong>200G</strong>.
        </li>
        <li>
          <strong>더블</strong>이면 한 번 더 굴립니다 — 세 번 연속 더블이면 소용돌이로 곧장.
        </li>
      </ul>
    ),
  },
  {
    title: '땅 사기와 건물',
    source: '§6.1·§6.2',
    body: (
      <ul style={LIST}>
        <li>주인 없는 땅에 멈추면 살 수 있습니다. 살 때 <strong>천막·상점</strong>까지 한 번에 지을 수 있어요.</li>
        <li>
          내 땅에 다시 멈추면 더 짓습니다 — 맨 위 <strong>풍등탑</strong>은 다시 멈췄을 때만.
        </li>
        <li>단계마다 건설비는 땅값의 절반입니다.</li>
      </ul>
    ),
  },
  {
    title: '통행료와 인수',
    source: '§6.3·§6.4',
    body: (
      <ul style={LIST}>
        <li>남의 땅에 멈추면 통행료를 냅니다. 같은 섬 두 칸을 다 가진 사람에게는 <strong>×1.5</strong>.</li>
        <li>
          통행료를 낸 뒤 그 땅을 (땅값 + 건설비) <strong>×1.5</strong> 에 <strong>인수</strong>할 수 있습니다 — 주인은 거절할
          수 없고, <strong>풍등탑</strong>이 선 땅은 인수할 수 없습니다.
        </li>
      </ul>
    ),
  },
  {
    title: '특별한 칸',
    source: '§6.5·§6.6·§7',
    body: (
      <ul style={LIST}>
        <li>🌀 <strong>소용돌이</strong> — 갇힙니다. 더블·50G·구명 부표로 나오고, 두 번 실패하면 세 번째 차례에 저절로 풀려요.</li>
        <li>🏮 <strong>등불 축제</strong> — 내 땅 하나의 통행료가 <strong>×2</strong>. 판에 축제는 하나뿐입니다.</li>
        <li>🎐 <strong>바람 정류장</strong> — 50G 내고 아무 칸으로 날아갑니다.</li>
        <li>🛃 <strong>세관</strong> — 100G. ✉ <strong>바람 편지</strong> — 한 장 뽑아 바로 따릅니다.</li>
      </ul>
    ),
  },
  {
    title: '돈이 모자라면',
    source: '§10',
    body: (
      <p>
        내야 할 돈이 모자라면 서버가 <strong>싼 땅의 건물부터 한 단계씩</strong>, 그다음 땅을 절반 값에 팔아 메웁니다. 다 팔아도
        모자라면 <strong>파산</strong> — 남은 현금을 내고 탈락하며, 땅은 은행으로 돌아갑니다.
      </p>
    ),
  },
];

/** 열람 키는 게임 전용(다른 게임 키와 격리). 게임판 토큰은 쓰지 않아 bodyClassName 이 없다. */
export const CLOUD_ROUTE_TUTORIAL: GameTutorial = {
  steps: CLOUD_ROUTE_TUTORIAL_STEPS,
  description: '구름항로 기본 규칙 안내 튜토리얼',
  seenKey: 'mirboard.tutorial.cloud_route.seen.v1',
};

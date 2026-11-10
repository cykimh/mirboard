import type { GameTutorial, TutorialStep } from '@/features/tutorial/types';

const LIST = { lineHeight: 1.7, paddingLeft: 18 } as const;

/**
 * 씨앗포 튜토리얼 6단계(S4 D-224 — 포대·특수탄·아이템 단계 추가). 콘텐츠 출처: `docs/rules-artillery.md`(요약). 룰을 바꾸면 같은 커밋으로 여기를 고친다 —
 * `artilleryTutorial.test` 가 핵심 수치를 붙잡고 있다.
 */
export const ARTILLERY_TUTORIAL_STEPS: TutorialStep[] = [
  {
    title: '씨앗포에 오신 걸 환영합니다',
    source: '§1·§9.3',
    body: (
      <>
        <p>
          구름 바다 위 <strong>흙섬 정원</strong>에서 화분 포대끼리 씨앗을 쏘는 게임입니다. <strong>2~4명</strong>이 차례로 한
          발씩 쏘고, <strong>마지막까지 남은 화분</strong>이 이깁니다.
        </p>
        <p>체력이 0 이 되거나 섬 아래 구름 바다로 떨어지면 탈락이에요.</p>
      </>
    ),
  },
  {
    title: '조준 — 각도·힘·바람',
    source: '§6.1·§7',
    body: (
      <ul style={LIST}>
        <li>
          <strong>전장을 끌어</strong> 줄기(포신) 방향을 정하고, ↺ ↻ 로 1°씩 다듬습니다.
        </li>
        <li>
          <strong>힘</strong>은 0~100. 슬라이더로 정해 [발사]를 누르거나, <strong>[꾹 눌러 쏘기]</strong>를 누르고 있다가 원하는
          힘에서 떼세요.
        </li>
        <li>
          위쪽의 <strong>🍃 바람</strong>(−10~10)이 씨앗을 옆으로 밉니다. 차례마다 바뀌어요.
        </li>
        <li>궤적은 미리 보여 주지 않습니다 — 지난 발의 힘 눈금을 보고 고쳐 쏘세요.</li>
      </ul>
    ),
  },
  {
    title: '이동 — 쏘기 전에 한 번',
    source: '§5',
    body: (
      <ul style={LIST}>
        <li>
          ◀ ▶ 를 누르고 있으면 걷습니다. 연료는 차례마다 다시 차고(<strong>새싹포 100</strong>·도토리포 70·민들레포 130), 한
          칸에 1, 오르막은 높이만큼 더 듭니다.
        </li>
        <li>3칸보다 높은 턱은 벽이라 못 넘어요. 낭떠러지 앞에서는 멈춥니다.</li>
        <li>이동은 발사와 함께 한 번에 서버로 갑니다 — 다른 사람에게는 쏘는 순간 걸어가는 모습이 보여요.</li>
      </ul>
    ),
  },
  {
    title: '탄과 땅 — 구멍과 낙하',
    source: '§6.2·§6.3',
    body: (
      <ul style={LIST}>
        <li>
          <strong>콩알</strong>: 피해 최대 30, 작은 구멍, 무제한. <strong>호박탄</strong>: 피해 최대 45, 큰 구멍,{' '}
          <strong>한 판에 2발</strong>.
        </li>
        <li>터진 자리에서 가까울수록 많이 다치고, 직접 맞으면 최대 피해입니다. 내 씨앗에 나도 다쳐요.</li>
        <li>
          땅이 패여 발밑이 비면 화분이 떨어집니다. <strong>20칸</strong>보다 높이 떨어지면 낙하 피해, 구름 바다까지 가면 탈락.
        </li>
      </ul>
    ),
  },
  {
    title: '포대 고르기 · 특수탄 · 아이템',
    source: '§3·§6.2·§6.4',
    body: (
      <ul style={LIST}>
        <li>
          판이 시작되면 모두 함께 <strong>15초</strong> 안에 포대를 고릅니다(안 고르면 새싹포). 남이 고른 건 다 고른 뒤에
          공개돼요.
        </li>
        <li>
          <strong>새싹포</strong> 체력 100·바람 100% — 덩굴탄은 맞은 자리에 <strong>흙 둔덕을 쌓아</strong> 엄폐물을 만듭니다.
        </li>
        <li>
          <strong>도토리포</strong> 체력 120·바람 60% — 알밤탄은 피해는 작지만 <strong>큰 구멍</strong>으로 발밑을 꺼뜨립니다.
        </li>
        <li>
          <strong>민들레포</strong> 체력 85·바람 140% — 홀씨탄은 꼭대기에서 <strong>세 갈래</strong>로 퍼집니다.
        </li>
        <li>특수탄은 한 판에 2발. 아이템은 모두 하나씩 — 💧물뿌리개(체력 +25)·🌀바람개비(이번 발 바람 무시)·🌱겹씨앗(같은 조준으로 한 발 더). 한 차례에 하나만 씁니다.</li>
      </ul>
    ),
  },
  {
    title: '구름 밀물과 순위',
    source: '§8·§9.3',
    body: (
      <>
        <p>
          모두가 <strong>10번씩</strong> 쏘고도 끝나지 않으면 차례마다 구름이 차올라 낮은 땅부터 삼킵니다 — 판은 반드시 끝나요.
        </p>
        <p>
          순위는 늦게 탈락한 순입니다. 차례 시간이 다 되면 그 차례는 <strong>넘어갑니다</strong>.
        </p>
      </>
    ),
  },
];

/** 씨앗포 튜토리얼 선언 — 열람 키는 게임 전용(D-121). */
export const ARTILLERY_TUTORIAL: GameTutorial = {
  steps: ARTILLERY_TUTORIAL_STEPS,
  description: '씨앗포 기본 규칙 안내 튜토리얼',
  seenKey: 'mirboard.tutorial.artillery.seen.v1',
};

import type { TutorialStep } from '@/features/tutorial/types';
import { TempGauge } from '../wordTempArt';

/**
 * 낱말 온도 튜토리얼 — 콘텐츠 출처 `docs/rules-wordtemp.md`. 룰을 바꾸면 같은 커밋으로 여기를 고친다 — `wordTempTutorial.test` 가
 * 핵심 문구를 붙잡는다.
 */
const LIST = { lineHeight: 1.7, paddingLeft: 18 } as const;

/**
 * 튜토리얼 글에 적는 차수별 기준 온도(룰 §3.4 의 값 — 판에서는 서버가 준 `floors` 로 그린다). 룰 수치를 바꾸면 같이 고친다.
 */
export const TUTORIAL_FLOORS: readonly number[] = [70, 75, 80, 85, 88];

/** 온도계 보기 한 줄 — 낱말 + 온도계. */
function Sample({ word, temp, floor }: { word: string; temp: number; floor?: number }) {
  return (
    <li className="wt-hint">
      <span className="wt-hint-word">{word}</span>
      <TempGauge temp={temp} floor={floor} small />
    </li>
  );
}

export const WORD_TEMP_TUTORIAL_STEPS: TutorialStep[] = [
  {
    title: '온도로 낱말 맞히기',
    body: (
      <>
        <p>
          한 사람이 <strong>정답 낱말</strong>을 숨기고 관련 낱말(힌트)을 냅니다. 힌트가 정답과 얼마나 가까운지 <strong>온도 0~100°</strong>로
          나와요. 나머지는 공개된 힌트와 온도를 보고 정답을 맞힙니다.
        </p>
        <p>
          정답이 <strong>바다</strong>라면 —
        </p>
        <ol className="wt-hints wt-tutorial-samples">
          <Sample word="책상" temp={12} />
          <Sample word="여름" temp={58} />
          <Sample word="갈매기" temp={71} />
          <Sample word="파도" temp={97} />
        </ol>
        <p>
          <strong>40° 아래</strong>는 차가움, <strong>40~74°</strong> 미지근, <strong>75~95°</strong> 뜨거움, <strong>96° 이상</strong>은 거의
          정답이에요.
        </p>
      </>
    ),
    source: '§1',
  },
  {
    title: '낱말 고르기와 차례',
    body: (
      <ul style={LIST}>
        <li>
          라운드마다 내 후보 가운데 <strong>낱말 하나</strong>를 고릅니다. 후보는 나만 보여요.
        </li>
        <li>
          한 라운드에 <strong>모두가 한 번씩 출제자</strong>가 돼요. 내 차례가 오면 고른 낱말이 정답입니다.
        </li>
        <li>
          한 출제 차례는 <strong>힌트 → 추리</strong>를 최대 <strong>{TUTORIAL_FLOORS.length}차</strong>까지 합니다. 누가 맞히면 거기서 끝나요.
        </li>
      </ul>
    ),
    source: '§3·§3.1',
  },
  {
    title: '출제자 — 힌트 내기',
    body: (
      <ul style={LIST}>
        <li>
          힌트는 <strong>낼 때마다 모두에게</strong> 보여요 — 낱말과 온도가 그대로 공개됩니다.
        </li>
        <li>
          차수마다 <strong>기준 온도</strong>가 있어요. 기준 이상인 힌트가 나오면 <strong>그 자리에서 추리</strong>로 넘어갑니다.
        </li>
        <li>
          한 차수에 힌트는 <strong>10개까지</strong>, 시간은 방의 힌트 제한만큼이에요. 더 낼 것이 없으면 [그만 내기].
        </li>
        <li>정답이 들어간 낱말(수영 → 수영장), 정답 안에 든 낱말(눈사람 → 사람)은 낼 수 없어요.</li>
        <li>
          처음 보는 낱말은 잠깐 <strong>채점 중…</strong>이 떴다가 온도가 나와요.
        </li>
      </ul>
    ),
    source: '§3.2·§5.2·§6.2',
  },
  {
    title: '기준 온도',
    body: (
      <>
        <p>
          차수가 올라갈수록 기준도 올라가요 — <strong>{TUTORIAL_FLOORS.map((f, i) => `${i + 1}차 ${f}°`).join(' · ')}</strong>.
        </p>
        <ol className="wt-hints wt-tutorial-samples">
          <Sample word="기준 넘김" temp={TUTORIAL_FLOORS[0] + 2} floor={TUTORIAL_FLOORS[0]} />
          <Sample word="기준 못 넘김" temp={TUTORIAL_FLOORS[0] - 9} floor={TUTORIAL_FLOORS[0]} />
        </ol>
        <ul style={LIST}>
          <li>온도계의 세로 선이 이번 차수의 기준이에요.</li>
          <li>
            <strong>기준을 넘기면</strong>: 바로 추리 — 아무도 못 맞히면 출제자가 점수를 받아요.
          </li>
          <li>
            <strong>기준을 못 넘기면</strong>(시간 끝·10개 다 냄): 그래도 추리는 하지만, 아무도 못 맞혀도 <strong>이번 차수 점수는 없어요</strong>.
          </li>
        </ul>
      </>
    ),
    source: '§3.2·§3.4',
  },
  {
    title: '추리와 점수',
    body: (
      <ul style={LIST}>
        <li>
          추리자는 차수마다 <strong>답 하나</strong>를 냅니다(모르겠으면 패스). 한 번 내면 못 바꿔요.
        </li>
        <li>
          모두가 내면 답이 <strong>한꺼번에 공개</strong>돼요. 틀린 답도 그대로 보입니다.
        </li>
        <li>
          맞힌 사람은 <strong>+1점</strong>, 그 출제 차례는 끝나요.
        </li>
        <li>
          아무도 못 맞히면 출제자가 <strong>차수 번호만큼</strong>(1차 +1 … {TUTORIAL_FLOORS.length}차 +{TUTORIAL_FLOORS.length}점) 받고 다음
          차수로 갑니다 — 그 차수의 기준을 넘겼을 때만요.
        </li>
        <li>
          점수는 <strong>라운드 번호만큼 곱해져요</strong> — 2라운드는 ×2, 3라운드는 ×3.
        </li>
        <li>"사과요"·"사과입니다"처럼 끝말이 붙어도 정답이에요. 오타는 안 됩니다.</li>
      </ul>
    ),
    source: '§3.3·§3.4·§4·§7',
  },
  {
    title: '약속 하나',
    body: (
      <>
        <p>
          <strong>채팅으로 정답을 알려 주지 않기.</strong> 정답이나 내 답을 채팅에 쓰면 재미가 없어요. 막아 두지는 않았으니 우리끼리 지켜요.
        </p>
        <ul style={LIST}>
          <li>차례가 끝나면 정답이 공개됩니다.</li>
          <li>판이 끝나면 총점이 높은 순으로 순위가 정해져요(같으면 공동 순위).</li>
          <li>판 도중에 나가면 최하위가 되고, 판은 남은 사람끼리 계속돼요.</li>
        </ul>
      </>
    ),
    source: '§8·§10·§12',
  },
];

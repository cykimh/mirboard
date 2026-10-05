import { useState } from 'react';
import type { OneCardClientRace, PressAction } from './onecardStore';
import { racePosition } from './raceSlots';

interface Props {
  race: OneCardClientRace;
  mySeat: number;
  /** 누를 수 없는 동안 — 내 누름이 응답을 기다리는 중(중복 누름 방지)이거나 연결이 끊겼다. */
  pending: boolean;
  onPress: (action: PressAction) => void;
}

/**
 * 창 끝까지 남은 시간 막대. **창당 한 번만** 계산한다 — 렌더마다 `Date.now()` 로 다시 재면 진행 중인 CSS 애니메이션의
 * duration 이 줄어들어 막대가 앞당겨지고 일찍 비는데(게임판은 스토어 전체를 구독해 누름·BUSY 신호·채팅 안읽음·끊김
 * 배지마다 다시 그려진다), 부모가 `key` 에 창 번호와 마감 시각을 실어 resync 가 마감을 바꾸면 새로 맞춘다.
 */
function RaceTimer({ closesAt, windowMillis }: { closesAt: number; windowMillis: number }) {
  const [timing] = useState(() => {
    const remaining = Math.max(0, closesAt - Date.now());
    return { remaining, start: windowMillis > 0 ? Math.min(1, remaining / windowMillis) : 0 };
  });
  return (
    <span
      className="oc-race-timer"
      aria-hidden
      style={{
        ['--oc-race-start' as string]: String(timing.start),
        animationDuration: `${timing.remaining}ms`,
      }}
    />
  );
}

/**
 * 외치기 경쟁 버튼 (설계서 §4.10). 화면 위 오버레이 레이어에, 서버가 고른 슬롯 + 지터 위치에 뜬다. 주인은 "원카드!",
 * 나머지 살아 있는 플레이어는 "잡기!" — 관전자·탈락자에게는 게임판이 이 컴포넌트를 그리지 않는다.
 *
 * <p>**자동 포커스와 단축키를 두지 않는다.** 둘 다 사실상 "위치와 무관하게 즉시 누르기"라 무작위 위치의 의미와
 * 레이팅 공정성을 깬다. 진짜 `<button>` 이라 탭·클릭·보조기기로 누를 수 있고, 창이 열린 사실은 게임판의 aria-live
 * 영역이 알린다. 막대는 창 끝까지 남은 시간이다(이 클라 시계 — 판정은 서버).
 */
export function RaceButton({ race, mySeat, pending, onPress }: Props) {
  const owner = mySeat === race.ownerSeat;
  const { left, top } = racePosition(race.slot, race.jitterX, race.jitterY);

  return (
    <div className="oc-race-layer">
      <button
        type="button"
        className={`oc-race-btn ${owner ? 'oc-race-call' : 'oc-race-catch'}`}
        style={{
          left: `clamp(var(--oc-race-half-w), ${left}%, calc(100% - var(--oc-race-half-w)))`,
          top: `${top}%`,
        }}
        disabled={pending}
        onClick={() => onPress(owner ? 'CALL_ONE_CARD' : 'CATCH')}
      >
        <span className="oc-race-label">{owner ? '원카드!' : '잡기!'}</span>
        <RaceTimer
          key={`${race.raceId}:${race.closesAt}`}
          closesAt={race.closesAt}
          windowMillis={race.windowMillis}
        />
      </button>
    </div>
  );
}

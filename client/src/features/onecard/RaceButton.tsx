import type { OneCardClientRace, PressAction } from './onecardStore';
import { racePosition } from './raceSlots';

interface Props {
  race: OneCardClientRace;
  mySeat: number;
  /** 내 누름이 응답을 기다리는 중 — 중복 누름을 막는다. */
  pending: boolean;
  onPress: (action: PressAction) => void;
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
  const remaining = Math.max(0, race.closesAt - Date.now());
  const start = race.windowMillis > 0 ? Math.min(1, remaining / race.windowMillis) : 0;

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
        <span
          key={race.raceId}
          className="oc-race-timer"
          aria-hidden
          style={{
            ['--oc-race-start' as string]: String(start),
            animationDuration: `${remaining}ms`,
          }}
        />
      </button>
    </div>
  );
}

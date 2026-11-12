import { useEffect, useRef } from 'react';

/**
 * D-264 얏추 — 휴대폰을 흔들어 굴리기(DeviceMotion). 흔들림은 연출·입력 신호일 뿐이다: 흔들기가 멎으면 게임판이 굴리기 버튼과 같은
 * 경로로 `ROLL` 을 보내고, 눈은 서버가 정한다. 지원하지 않거나 권한이 없으면 아무 일도 하지 않는다(버튼·꾹 누르기는 그대로).
 */
/** 이만큼(m/s²) 넘게 움직여야 흔든 것으로 친다 — 걷거나 들어 올리는 정도로는 넘지 않는다. */
export const MOTION_SHAKE_THRESHOLD = 11;
/** 중력이 섞인 값밖에 없는 기기에서 쓰는 문턱(중력 9.81 과의 차이). */
export const MOTION_GRAVITY_THRESHOLD = 7;
/** 센 움직임이 이만큼 끊기면 흔들기가 끝난 것으로 본다. */
export const MOTION_QUIET_MS = 350;
/** 센 움직임이 이 수보다 적으면 툭 친 것이다 — 굴리지 않는다. */
export const MOTION_MIN_JOLTS = 3;
const MOTION_KEY = 'mirboard.yachtMotionShake';

type MotionCtor = { requestPermission?: () => Promise<string> };

/** 터치 기기 + DeviceMotion 이 있을 때만 켜기 버튼을 보인다. */
export function motionShakeSupported(): boolean {
  try {
    return typeof window !== 'undefined' && 'DeviceMotionEvent' in window && (navigator.maxTouchPoints ?? 0) > 0;
  } catch {
    return false;
  }
}

/** iOS 는 사용자 동작 안에서 권한을 물어야 한다(그 밖의 기기는 물을 것이 없다). */
export function motionNeedsPermission(): boolean {
  const ctor = (window as unknown as { DeviceMotionEvent?: MotionCtor }).DeviceMotionEvent;
  return typeof ctor?.requestPermission === 'function';
}

/** 권한을 묻는다 — 반드시 버튼을 누른 그 자리에서 부른다. 거절·오류면 false. */
export async function requestMotionPermission(): Promise<boolean> {
  try {
    const ctor = (window as unknown as { DeviceMotionEvent?: MotionCtor }).DeviceMotionEvent;
    if (!ctor) return false;
    if (typeof ctor.requestPermission !== 'function') return true;
    return (await ctor.requestPermission()) === 'granted';
  } catch {
    return false;
  }
}

/** 지난번에 켜 두었나 — 권한을 다시 물어야 하는 기기(iOS)는 매번 꺼진 채 시작한다. */
export function loadMotionPreference(): boolean {
  try {
    return motionShakeSupported() && !motionNeedsPermission() && window.localStorage.getItem(MOTION_KEY) === '1';
  } catch {
    return false;
  }
}

export function saveMotionPreference(on: boolean) {
  try {
    window.localStorage.setItem(MOTION_KEY, on ? '1' : '0');
  } catch {
    // 저장소를 못 쓰는 환경 — 이번 화면에서만 켠다.
  }
}

interface Options {
  /** 켜져 있고 지금 굴릴 수 있을 때만 true — false 면 듣지 않는다. */
  enabled: boolean;
  onStart: () => void;
  /** `roll` — 충분히 흔들었다(툭 친 것이 아니다). */
  onStop: (roll: boolean) => void;
}

export function useDeviceShake({ enabled, onStart, onStop }: Options) {
  const handlers = useRef({ onStart, onStop });
  handlers.current = { onStart, onStop };
  useEffect(() => {
    if (!enabled) return;
    let jolts = 0;
    let quiet = 0;
    const listen = (e: DeviceMotionEvent) => {
      const a = e.acceleration;
      const g = e.accelerationIncludingGravity;
      let strong = false;
      if (a && a.x != null && a.y != null && a.z != null) {
        strong = Math.hypot(a.x, a.y, a.z) > MOTION_SHAKE_THRESHOLD;
      } else if (g && g.x != null && g.y != null && g.z != null) {
        strong = Math.abs(Math.hypot(g.x, g.y, g.z) - 9.81) > MOTION_GRAVITY_THRESHOLD;
      }
      if (!strong) return;
      if (jolts === 0) handlers.current.onStart();
      jolts += 1;
      window.clearTimeout(quiet);
      quiet = window.setTimeout(() => {
        const roll = jolts >= MOTION_MIN_JOLTS;
        jolts = 0;
        handlers.current.onStop(roll);
      }, MOTION_QUIET_MS);
    };
    window.addEventListener('devicemotion', listen);
    return () => {
      window.removeEventListener('devicemotion', listen);
      window.clearTimeout(quiet);
      if (jolts > 0) handlers.current.onStop(false);
    };
  }, [enabled]);
}

/**
 * D-273 — 숨긴 탭은 연결을 끊는다. 운영 서버(앱·Redis)는 연결이 하나도 없을 때만 잠드는데(D-171), 열어 둔 탭 하나의 STOMP
 * 소켓이 하트비트(D-158)로 두 머신을 밤새 깨워 두었다. 탭이 **계속 숨겨진 채** 정해진 시간이 지나면 소켓 주인이 스스로 끊고,
 * 다시 보이면 각 훅의 기존 복귀 경로(`activate()` + resync)가 다시 붙인다.
 *
 * <p>진행 중인 판에 앉은 플레이어는 탈주(유예 180s)로 지지 않게 더 길게 기다린다 — 그래도 턴 제한 없는 판에 잊힌 탭이 서버를
 * 계속 깨우지 않게 상한은 둔다. 판 상태는 `RoomPage` 가 {@link setHiddenIdleInGame} 으로 알린다(게임판·소켓 훅은 게임을 모른다).
 */
export const HIDDEN_IDLE_MS = 10 * 60_000;
export const HIDDEN_IDLE_IN_GAME_MS = 60 * 60_000;

let inGameSeat = false;
const rearmers = new Set<() => void>();

/** 지금 이 탭이 진행 중인 판의 플레이어 자리인가. 숨긴 동안 바뀌면 남은 시간을 다시 잰다. */
export function setHiddenIdleInGame(value: boolean): void {
  if (inGameSeat === value) return;
  inGameSeat = value;
  rearmers.forEach((rearm) => rearm());
}

export function hiddenIdleLimitMs(): number {
  return inGameSeat ? HIDDEN_IDLE_IN_GAME_MS : HIDDEN_IDLE_MS;
}

/**
 * 탭이 숨겨진 채 {@link hiddenIdleLimitMs} 가 지나면 `onIdle` 을 한 번 부른다(다시 숨겨질 때마다 처음부터). 보이면 취소.
 * 반환값은 정리 함수. 시각은 숨긴 순간부터 재므로 백그라운드 타이머가 늦게 깨어나도(브라우저 절전) 늦어질 뿐 일찍 끊지 않는다.
 */
export function watchHiddenIdle(onIdle: () => void): () => void {
  let hiddenAt: number | null = null;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let fired = false;

  const clear = () => {
    if (timer !== undefined) clearTimeout(timer);
    timer = undefined;
  };
  const arm = () => {
    clear();
    if (hiddenAt === null || fired) return;
    const left = hiddenAt + hiddenIdleLimitMs() - Date.now();
    if (left <= 0) {
      fired = true;
      onIdle();
      return;
    }
    timer = setTimeout(arm, left);
  };
  const onVisibility = () => {
    if (document.visibilityState === 'hidden') {
      if (hiddenAt !== null) return; // 이미 재는 중 — 숨김 이벤트가 겹쳐도 처음 숨긴 시각 그대로
      hiddenAt = Date.now();
      fired = false;
      arm();
    } else {
      hiddenAt = null;
      fired = false;
      clear();
    }
  };

  document.addEventListener('visibilitychange', onVisibility);
  rearmers.add(arm);
  if (document.visibilityState === 'hidden') onVisibility();

  return () => {
    document.removeEventListener('visibilitychange', onVisibility);
    rearmers.delete(arm);
    clear();
  };
}

import { useCallback, useEffect, useRef, useState, type RefObject } from 'react';
import type { OneCardCard, OneCardSuit } from '@/types/onecard';
import type { LastPlay, LastRace, OneCardClientRace } from './onecardStore';
import { isAttack, isSuitChange } from './onecardRules';
import { racePosition, raceLeftCss, raceTopCss } from './raceSlots';
import { prefersReducedMotion } from './useCardPlayFlight';

/**
 * 네온 파티 테마(D-260)의 화면 전용 연출. 스토어의 **공개 상태 변화**(방금 낸 카드·차례·방금 닫힌 경쟁)에서만 파생되고 아무것도
 * 보내지 않는다 — 서버 판정·차례·타이머·경쟁 버튼(위치·크기·찾기 난이도)은 그대로다. 연출 조각은 `pointer-events: none` 인 장식이라
 * 조작을 막지도 늦추지도 않는다. 카드 애니메이션 설정이 꺼져 있거나 OS 가 동작 줄이기를 원하면 만들지 않는다.
 */

export type SpecialEffect = 'skip' | 'reverse' | 'again';

/**
 * J·Q·K 의 화면 효과 (`docs/rules-onecard.md` §8) — K 는 한 번 더, J 는 건너뛰기(살아 있는 사람이 2명이면 한 번 더), Q 는 방향 바뀜
 * (2명이면 상대 차례 그대로라 효과 없음, D-173). 표시 전용이다.
 */
export function specialEffectOf(card: OneCardCard, aliveCount: number): SpecialEffect | null {
  if (card.joker) return null;
  if (card.rank === 13) return 'again';
  if (card.rank === 11) return aliveCount <= 2 ? 'again' : 'skip';
  if (card.rank === 12) return aliveCount > 2 ? 'reverse' : null;
  return null;
}

export const SPECIAL_LABEL: Record<SpecialEffect, string> = {
  skip: '건너뛰기',
  reverse: '방향 바뀜',
  again: '한 번 더',
};

/** 공격 누적의 불꽃 단계(0 = 없음, 1~4) — 누적이 클수록 숫자와 불꽃 테두리가 커진다. */
export function attackLevel(attackStack: number): number {
  if (attackStack <= 0) return 0;
  if (attackStack <= 3) return 1;
  if (attackStack <= 7) return 2;
  if (attackStack <= 12) return 3;
  return 4;
}

interface Point {
  left: number;
  top: number;
}

export type OneCardFxItem =
  | ({ id: number; kind: 'attack'; total: number; level: number } & Point)
  | ({ id: number; kind: 'suit'; suit: OneCardSuit } & Point)
  | ({ id: number; kind: 'special'; effect: SpecialEffect } & Point)
  /** 가운데(left·top)에서 받는 사람 좌석(+dx·+dy)으로 가는 충격. */
  | ({ id: number; kind: 'shock'; dx: number; dy: number } & Point)
  /** 잡힌 사람 좌석 위로 쏟아지는 벌칙 카드. */
  | ({ id: number; kind: 'pour' } & Point)
  /** 경쟁 버튼이 있던 자리(화면 기준 CSS 좌표)의 폭죽·글자. */
  | { id: number; kind: 'race'; outcome: 'CALLED' | 'CAUGHT'; leftCss: string; topCss: string };

type NewFx = OneCardFxItem extends infer T ? (T extends { id: number } ? Omit<T, 'id'> : never) : never;

/** 연출 조각이 화면에 머무는 시간(ms) — CSS 애니메이션(지연 포함)보다 조금 길게. */
export const FX_LIFETIME_MS: Record<OneCardFxItem['kind'], number> = {
  attack: 1300,
  suit: 1400,
  special: 1300,
  shock: 1000,
  pour: 1300,
  race: 1100,
};

export interface OneCardFxInput {
  lastPlay: LastPlay | null;
  declaredSuit: OneCardSuit | null;
  attackStack: number;
  turnSeat: number;
  /** 살아 있는(탈락하지 않은) 좌석 수. */
  aliveCount: number;
  lastRace: LastRace | null;
  race: OneCardClientRace | null;
}

export function useOneCardFx(
  rootRef: RefObject<HTMLElement | null>,
  input: OneCardFxInput,
  mySeat: number,
  enabled: boolean,
): OneCardFxItem[] {
  const [items, setItems] = useState<OneCardFxItem[]>([]);
  const nextId = useRef(1);
  const timers = useRef(new Set<number>());
  const latest = useRef(input);
  latest.current = input;

  useEffect(() => {
    const pending = timers.current;
    return () => {
      for (const t of pending) window.clearTimeout(t);
      pending.clear();
    };
  }, []);

  const spawn = useCallback((fx: NewFx) => {
    const id = nextId.current++;
    setItems((list) => [...list, { ...fx, id } as OneCardFxItem]);
    const timer = window.setTimeout(() => {
      timers.current.delete(timer);
      setItems((list) => list.filter((x) => x.id !== id));
    }, FX_LIFETIME_MS[fx.kind]);
    timers.current.add(timer);
  }, []);

  /** 게임판 루트 기준 가운데 점. 못 찾으면 null. */
  const pointOf = useCallback(
    (selector: string): Point | null => {
      const root = rootRef.current;
      const el = root?.querySelector(selector);
      if (!root || !el) return null;
      const r = root.getBoundingClientRect();
      const e = el.getBoundingClientRect();
      return { left: e.left + e.width / 2 - r.left, top: e.top + e.height / 2 - r.top };
    },
    [rootRef],
  );
  const seatPoint = useCallback(
    (seat: number) => pointOf(seat === mySeat ? '.oc-me' : `.oc-felt [data-seat="${seat}"]`),
    [pointOf, mySeat],
  );

  const active = () => enabled && !prefersReducedMotion();

  // ① 방금 낸 카드 — 공격 누적·7 무늬 바꾸기·J/Q/K. 순번이 바뀔 때만(처음 그릴 때·resync 는 아니다).
  const lastPlay = input.lastPlay;
  const playedSeq = useRef<number | null>(lastPlay?.seq ?? null);
  useEffect(() => {
    const seq = lastPlay?.seq ?? null;
    if (seq === playedSeq.current) return;
    playedSeq.current = seq;
    if (!lastPlay || !active()) return;
    const center = pointOf('.oc-top');
    if (!center) return;
    const { attackStack, declaredSuit, aliveCount } = latest.current;
    if (isAttack(lastPlay.card) && attackStack > 0) {
      spawn({ kind: 'attack', total: attackStack, level: attackLevel(attackStack), ...center });
    }
    if (isSuitChange(lastPlay.card) && declaredSuit) {
      spawn({ kind: 'suit', suit: declaredSuit, ...center });
    }
    const effect = specialEffectOf(lastPlay.card, aliveCount);
    if (effect) spawn({ kind: 'special', effect, ...center });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lastPlay, enabled, pointOf, spawn]);

  // ② 공격을 받는 사람 쪽으로 충격 — 공격 카드 뒤 차례가 넘어온 좌석. 낸 카드마다 한 번.
  const shockedSeq = useRef<number | null>(lastPlay?.seq ?? null);
  const { turnSeat, attackStack } = input;
  useEffect(() => {
    if (!lastPlay || shockedSeq.current === lastPlay.seq) return;
    if (!isAttack(lastPlay.card)) {
      shockedSeq.current = lastPlay.seq;
      return;
    }
    if (attackStack <= 0 || turnSeat < 0 || turnSeat === lastPlay.seat) return;
    shockedSeq.current = lastPlay.seq;
    if (!active()) return;
    const center = pointOf('.oc-top');
    const target = seatPoint(turnSeat);
    if (!center || !target) return;
    spawn({ kind: 'shock', ...center, dx: target.left - center.left, dy: target.top - center.top });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lastPlay, turnSeat, attackStack, enabled, pointOf, seatPoint, spawn]);

  // ③ 경쟁이 닫혔다 — 누른 사람이 있으면 버튼 자리에서 폭죽·글자, 잡혔으면 주인 좌석으로 벌칙 카드가 쏟아진다.
  // 버튼 자리는 열려 있던 창에서 기억한다(닫히면 스토어에서 사라진다). 위치·크기·찾기 난이도는 읽기만 한다.
  const openRace = useRef<OneCardClientRace | null>(input.race);
  if (input.race) openRace.current = input.race;
  const lastRace = input.lastRace;
  const resolvedRace = useRef<number | null>(lastRace?.raceId ?? null);
  useEffect(() => {
    const raceId = lastRace?.raceId ?? null;
    if (raceId === resolvedRace.current) return;
    resolvedRace.current = raceId;
    if (!lastRace || !active()) return;
    if (lastRace.outcome !== 'CALLED' && lastRace.outcome !== 'CAUGHT') return;
    const was = openRace.current;
    if (was && was.raceId === lastRace.raceId) {
      const { left, top } = racePosition(was.slot, was.jitterX, was.jitterY);
      spawn({ kind: 'race', outcome: lastRace.outcome, leftCss: raceLeftCss(left), topCss: raceTopCss(top) });
    }
    if (lastRace.outcome === 'CAUGHT' && lastRace.ownerSeat >= 0) {
      const target = seatPoint(lastRace.ownerSeat);
      if (target) spawn({ kind: 'pour', ...target });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lastRace, enabled, seatPoint, spawn]);

  return items;
}

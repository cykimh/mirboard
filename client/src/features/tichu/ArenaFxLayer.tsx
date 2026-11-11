import { Fragment, useEffect, useRef } from 'react';
import type { CSSProperties } from 'react';
import { CardBack, CardFaceArt } from './CardChip';
import { cardKey } from '@/types/tichu';
import { useEffectStore } from './effectStore';
import type { ArenaFx } from './effectStore';
import { viewPosOf } from './seatView';
import type { SeatViewPos } from './seatView';
import { SpecialArt } from './tichuArt';
import { prefersReducedMotion } from './tichuTheme';

interface ArenaFxLayerProps {
  mySeat: number;
  isInDealing: boolean;
  /** 딜 단계에서 지금까지 받은 장수(8 → 14). 바뀔 때마다 딜 연출을 한 번 띄운다. */
  dealingCardCount: number;
  /** ⋮ 메뉴의 '카드 애니메이션' — 꺼져 있으면 카드가 움직이는 연출(딜·쓸어 가기·용 넘기기)을 건너뛴다. */
  cardAnimEnabled: boolean;
}

const SEATS: readonly SeatViewPos[] = ['s', 'e', 'n', 'w'];
/** 딜 한 번에 좌석마다 날아가는 뒷면 수. */
const DEAL_CARDS_PER_SEAT = 3;
/** 카드가 실제로 움직이는 연출 — 카드 애니 끔·동작 줄이기면 그리지 않는다. */
const MOVING: ReadonlySet<ArenaFx['kind']> = new Set(['DEAL', 'TRICK_SWEEP', 'DRAGON_GIVEN']);

/**
 * 경기장 연출 층 (D-241, 옻칠 테마 전용). `.table-arena` 안에 깔려 `effectStore.arenaFx` 줄을 그린다 — 딜 비행, 트릭
 * 쓸어 가기, 용 넘기기, 선언 인장, 소원 패. 전부 화면만이다: 누를 수 없고(`pointer-events: none`), 서버 이벤트나 스토어
 * 상태를 바꾸지 않으며, 조작을 기다리게 하지도 않는다.
 *
 * 자리는 JS 로 재지 않는다. 층이 컨테이너(`container-type: size`)라 좌석 방향 클래스(`tc-to-s` …)가 컨테이너 단위로
 * 이동 거리를 정한다 — 좌석 클래스와 같은 `viewPosOf` 매핑을 쓴다.
 */
export function ArenaFxLayer({ mySeat, isInDealing, dealingCardCount, cardAnimEnabled }: ArenaFxLayerProps) {
  const arenaFx = useEffectStore((s) => s.arenaFx);
  const pushArenaFx = useEffectStore((s) => s.pushArenaFx);
  const pruneArenaFx = useEffectStore((s) => s.pruneArenaFx);
  const clearArenaFx = useEffectStore((s) => s.clearArenaFx);

  // 딜 — 서버는 딜을 resync 로 알리므로(이벤트 없음) 받은 장수가 바뀌는 것을 본다.
  const prevDealRef = useRef<number | null>(null);
  useEffect(() => {
    const cur = isInDealing && dealingCardCount > 0 ? dealingCardCount : null;
    const prev = prevDealRef.current;
    prevDealRef.current = cur;
    if (cur === null || cur === prev) return;
    if (!cardAnimEnabled || prefersReducedMotion()) return;
    pushArenaFx({ kind: 'DEAL' });
  }, [isInDealing, dealingCardCount, cardAnimEnabled, pushArenaFx]);

  // 가장 먼저 끝나는 연출에 맞춰 걷는다.
  useEffect(() => {
    if (arenaFx.length === 0) return;
    const next = Math.min(...arenaFx.map((f) => f.expiresAt));
    const id = window.setTimeout(pruneArenaFx, Math.max(0, next - Date.now()) + 30);
    return () => window.clearTimeout(id);
  }, [arenaFx, pruneArenaFx]);

  // 게임판을 떠나면 남은 연출을 버린다(다음 방에서 낡은 연출이 뜨지 않게).
  useEffect(() => clearArenaFx, [clearArenaFx]);

  const still = !cardAnimEnabled || prefersReducedMotion();
  const shown = still ? arenaFx.filter((f) => !MOVING.has(f.kind)) : arenaFx;
  if (shown.length === 0) return null;

  return (
    <div className="tc-fx-layer" aria-hidden="true">
      {shown.map((fx) => {
        const to = viewPosOf(fx.seat ?? 0, mySeat);
        switch (fx.kind) {
          case 'DEAL':
            return (
              <div key={fx.id} className="tc-fx-deal">
                {SEATS.flatMap((pos, s) =>
                  Array.from({ length: DEAL_CARDS_PER_SEAT }, (_, i) => (
                    <span
                      key={`${pos}${i}`}
                      className={`tc-fx-deal-card tc-to-${pos}`}
                      style={{ '--tc-d': `${(i * SEATS.length + s) * 45}ms` } as CSSProperties}
                    >
                      <CardBack />
                    </span>
                  )),
                )}
              </div>
            );
          case 'TRICK_SWEEP':
            return (
              <Fragment key={fx.id}>
                <div className={`tc-fx-sweep tc-to-${to}`}>
                  <div className="tc-fx-sweep-pile">
                    {(fx.cards ?? []).map((c, i) => (
                      <span key={cardKey(c)} className="tc-fx-sweep-card" style={{ '--tc-i': i } as CSSProperties}>
                        <CardFaceArt card={c} />
                      </span>
                    ))}
                  </div>
                </div>
                {fx.points ? (
                  <span className={`tc-fx-points tc-to-${to}${fx.points < 0 ? ' tc-fx-points-minus' : ''}`}>
                    {fx.points > 0 ? `+${fx.points}` : fx.points}
                  </span>
                ) : null}
              </Fragment>
            );
          case 'DRAGON_GIVEN':
            return (
              <div key={fx.id} className={`tc-fx-dragon tc-from-${viewPosOf(fx.fromSeat ?? 0, mySeat)} tc-to-${to}`}>
                <span className="tc-fx-dragon-token">
                  <SpecialArt special="DRAGON" className="tc-fx-dragon-art" />
                </span>
                <span className="tc-fx-dragon-label">용 트릭</span>
              </div>
            );
          case 'DECLARE_STAMP':
            return (
              <div key={fx.id} className={`tc-fx-stamp tc-to-${to}${fx.grand ? ' tc-fx-stamp-grand' : ''}`}>
                <span className="tc-fx-ring" />
                <span className="tc-fx-ring tc-fx-ring-late" />
                <span className="tc-seal">{fx.grand ? '그랜드' : '티츄'}</span>
              </div>
            );
          case 'WISH':
            return (
              <div key={fx.id} className="tc-fx-wish">
                <span className="tc-fx-wish-tile">
                  <small>소원</small>
                  <b>{wishLabel(fx.rank)}</b>
                </span>
              </div>
            );
          default:
            return null;
        }
      })}
    </div>
  );
}

/** 소원 숫자 표기 — 11~14 는 J·Q·K·A. */
export function wishLabel(rank: number | null | undefined): string {
  if (rank == null) return '';
  if (rank === 11) return 'J';
  if (rank === 12) return 'Q';
  if (rank === 13) return 'K';
  if (rank === 14) return 'A';
  return String(rank);
}

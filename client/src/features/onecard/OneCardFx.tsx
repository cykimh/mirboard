import type { CSSProperties } from 'react';
import type { RaceButtonSize } from '@/types/api';
import { SuitGlyph } from './OneCardFace';
import { SPECIAL_LABEL, type OneCardFxItem, type SpecialEffect } from './useOneCardFx';

/** CSS 변수 묶음 — `style` 에 `--oc-*` 를 싣는다. */
function vars(v: Record<string, string | number>): CSSProperties {
  return v as CSSProperties;
}

const SPARKS = 12;
const POUR_CARDS = 7;
const CONFETTI = 18;

/** J·Q·K 효과 그림 — 건너뛰기 화살, 방향 바뀜 회전 화살, 한 번 더. 직접 그린 선 그림이다. */
function SpecialIcon({ effect }: { effect: SpecialEffect }) {
  return (
    <svg className="oc-fx-special-icon" viewBox="0 0 48 48" width="56" height="56" aria-hidden="true" focusable="false">
      {effect === 'skip' && <path d="M8 10l13 14L8 38M22 10l13 14-13 14M41 9v30" />}
      {effect === 'reverse' && (
        <path d="M8 21a16 16 0 0 1 29-7M37 14l1-8M37 14l-8-1M40 27a16 16 0 0 1-29 7M11 34l-1 8M11 34l8 1" />
      )}
      {effect === 'again' && <path d="M24 8a16 16 0 1 0 15 10M24 8l-7-4M24 8l-4 7M24 17v14M17 24h14" />}
    </svg>
  );
}

interface Props {
  items: OneCardFxItem[];
  /** 방장이 고른 경쟁 버튼 크기 — 폭죽이 버튼과 같은 가장자리 보정(`--oc-race-half-*`)을 쓰게. */
  raceSize: RaceButtonSize;
}

/**
 * D-260 — 연출 조각을 그린다(`useOneCardFx` 가 만든 목록). 전부 장식(`aria-hidden`, `pointer-events: none`)이고 같은 정보는
 * 배지·좌석·알림 문장이 이미 준다. 게임판 루트(`.oc-table`, position: relative) 기준 절대 위치이고, 경쟁 폭죽만 버튼과 같은
 * 화면 고정 레이어에 뜬다.
 */
export function OneCardFxLayer({ items, raceSize }: Props) {
  if (items.length === 0) return null;
  const raceItems = items.filter((x) => x.kind === 'race');
  return (
    <>
      {items.map((fx) => {
        switch (fx.kind) {
          case 'attack':
            return (
              <div
                key={fx.id}
                className="oc-fx oc-fx-attack"
                data-level={fx.level}
                aria-hidden="true"
                style={{ left: fx.left, top: fx.top }}
              >
                <span className="oc-fx-attack-ring" />
                <span className="oc-fx-attack-num">+{fx.total}</span>
              </div>
            );
          case 'suit':
            return (
              <div key={fx.id} className="oc-fx oc-fx-suit" aria-hidden="true" style={{ left: fx.left, top: fx.top }}>
                <SuitGlyph suit={fx.suit} size={104} />
              </div>
            );
          case 'special':
            return (
              <div
                key={fx.id}
                className={`oc-fx oc-fx-special oc-fx-special-${fx.effect}`}
                aria-hidden="true"
                style={{ left: fx.left, top: fx.top }}
              >
                <SpecialIcon effect={fx.effect} />
                <span className="oc-fx-special-label">{SPECIAL_LABEL[fx.effect]}</span>
              </div>
            );
          case 'shock':
            return (
              <div key={fx.id} aria-hidden="true">
                <div
                  className="oc-fx oc-fx-shock"
                  style={{
                    left: fx.left,
                    top: fx.top,
                    ...vars({ '--oc-fx-dx': `${fx.dx}px`, '--oc-fx-dy': `${fx.dy}px` }),
                  }}
                />
                <div className="oc-fx oc-fx-hit" style={{ left: fx.left + fx.dx, top: fx.top + fx.dy }} />
              </div>
            );
          case 'pour':
            return (
              <div key={fx.id} className="oc-fx oc-fx-pour" aria-hidden="true" style={{ left: fx.left, top: fx.top }}>
                {Array.from({ length: POUR_CARDS }, (_, i) => (
                  <span key={i} className="oc-fx-pour-card" style={vars({ '--oc-i': i })} />
                ))}
              </div>
            );
          default:
            return null;
        }
      })}
      {raceItems.length > 0 && (
        <div className={`oc-race-layer oc-race-size-${raceSize.toLowerCase()} oc-fx-race-layer`} aria-hidden="true">
          {raceItems.map(
            (fx) =>
              fx.kind === 'race' && (
                <div
                  key={fx.id}
                  className={`oc-fx-race ${fx.outcome === 'CALLED' ? 'oc-fx-race-call' : 'oc-fx-race-catch'}`}
                  style={{ left: fx.leftCss, top: fx.topCss }}
                >
                  {Array.from({ length: SPARKS }, (_, i) => (
                    <span key={i} className="oc-fx-spark" style={vars({ '--oc-i': i })} />
                  ))}
                  <span className="oc-fx-race-text">{fx.outcome === 'CALLED' ? '원카드!' : '잡기!'}</span>
                </div>
              ),
          )}
        </div>
      )}
    </>
  );
}

/** D-260 — 판이 끝나면 파티 조명과 색종이(한 번 돌고 사라진다). 내가 1위면 더 화려하게. */
export function OneCardParty({ win }: { win: boolean }) {
  return (
    <div className={`oc-party${win ? ' oc-party-win' : ''}`} aria-hidden="true">
      <span className="oc-party-beam" />
      <span className="oc-party-beam" />
      <span className="oc-party-beam" />
      {Array.from({ length: CONFETTI }, (_, i) => (
        <span key={i} className="oc-party-confetti" style={vars({ '--oc-i': i })} />
      ))}
    </div>
  );
}

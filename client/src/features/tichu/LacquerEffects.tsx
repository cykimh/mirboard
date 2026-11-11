import type { CSSProperties } from 'react';
import type { ActiveEffect } from './effectStore';
import { LanternArt } from './tichuArt';

/** 결정적 의사 난수(0~1) — 같은 조각은 늘 같은 자리에서 난다(렌더마다 흔들리지 않게). */
function rand(i: number, salt: number): number {
  const x = Math.sin(i * 127.1 + salt * 311.7) * 43758.5453;
  return x - Math.floor(x);
}

const SHARDS = 22;
const DUST = 34;
const LANTERNS = [8, 26, 50, 74, 92];

/** 폭탄 — 금빛 섬광 + 충격 고리 + 사방으로 튀는 파편. 스트레이트 플러시는 주홍 파편이 섞이고 고리가 하나 더 돈다. */
function BombFx({ straightFlush }: { straightFlush: boolean }) {
  return (
    <div className={`tc-fx-screen tc-fx-bomb${straightFlush ? ' tc-fx-bomb-sf' : ''}`}>
      <span className="tc-fx-bomb-flash" />
      <span className="tc-fx-bomb-core" />
      <span className="tc-fx-bomb-ring" />
      <span className="tc-fx-bomb-ring tc-fx-bomb-ring-late" />
      {Array.from({ length: SHARDS }, (_, i) => {
        const angle = (360 / SHARDS) * i + rand(i, 1) * 14;
        const style = {
          '--tc-a': `${angle.toFixed(1)}deg`,
          '--tc-r': `${(24 + rand(i, 2) * 26).toFixed(1)}vmin`,
          '--tc-s': (0.6 + rand(i, 3) * 0.9).toFixed(2),
          '--tc-d': `${Math.round(rand(i, 4) * 90)}ms`,
        } as CSSProperties;
        return <span key={i} className={`tc-fx-shard${i % 3 === 0 ? ' tc-fx-shard-red' : ''}`} style={style} />;
      })}
      <span className="tc-fx-bomb-label">{straightFlush ? '스트레이트 플러시 폭탄!' : '폭탄!'}</span>
    </div>
  );
}

/** 티츄·그랜드 티츄 선언 — 화면 가운데에 큰 인장이 찍힌다(좌석 인장은 경기장 연출 층이 찍는다). */
function DeclareFx({ grand }: { grand: boolean }) {
  return (
    <div className={`tc-fx-screen tc-fx-declare${grand ? ' tc-fx-declare-grand' : ''}`}>
      <span className="tc-fx-declare-ring" />
      <span className="tc-seal tc-seal-big">
        {grand && <small>그랜드</small>}
        티츄
      </span>
    </div>
  );
}

/** 매치 끝 — 이기면 금가루가 내리고 등불이 오른다. 지면 등불 하나만 조용히. */
function VictoryFx({ effect }: { effect: ActiveEffect }) {
  const lose = effect.tone === 'lose';
  const text = (effect.text ?? '승리!').replace(/^🏆\s*/u, '');
  return (
    <div className={`tc-fx-screen tc-fx-victory${lose ? ' tc-fx-victory-lose' : ''}`}>
      <span className="tc-fx-victory-glow" />
      {(lose ? [50] : LANTERNS).map((left, i) => (
        <span
          key={left}
          className="tc-fx-lantern"
          style={{ '--tc-x': `${left}%`, '--tc-d': `${i * 170}ms`, '--tc-s': (0.8 + rand(i, 7) * 0.5).toFixed(2) } as CSSProperties}
        >
          <LanternArt className="tc-fx-lantern-art" />
        </span>
      ))}
      {!lose &&
        Array.from({ length: DUST }, (_, i) => (
          <span
            key={i}
            className="tc-fx-dust"
            style={
              {
                '--tc-x': `${(rand(i, 11) * 100).toFixed(1)}%`,
                '--tc-d': `${Math.round(rand(i, 12) * 1000)}ms`,
                '--tc-s': (0.5 + rand(i, 13) * 1.1).toFixed(2),
                '--tc-w': `${(rand(i, 14) * 12 - 6).toFixed(1)}vw`,
              } as CSSProperties
            }
          />
        ))}
      <span className="tc-fx-plaque">{text}</span>
    </div>
  );
}

/**
 * 옻칠 테마의 화면 전역 연출 (D-241). `EffectsOverlay` 가 테마일 때 이쪽으로 넘긴다 — 노출 시간·소리·자동 해제는
 * `EffectsOverlay` 가 그대로 맡고, 여기는 그림만 바꾼다. 모양은 `25-tichu-lacquer.css` 의 `.tc-fx-*`.
 */
export function LacquerEffect({ effect }: { effect: ActiveEffect }) {
  switch (effect.kind) {
    case 'MY_TURN':
      return (
        <div key={effect.id} className="tc-fx-screen tc-fx-myturn">
          <span className="tc-fx-myturn-pill">내 차례</span>
        </div>
      );
    case 'MATCH_VICTORY':
      return <VictoryFx key={effect.id} effect={effect} />;
    case 'TICHU_DECLARED':
      return <DeclareFx key={effect.id} grand={effect.grand ?? /그랜드/.test(effect.text ?? '')} />;
    case 'STRAIGHT_FLUSH_BOMB':
      return <BombFx key={effect.id} straightFlush />;
    case 'BOMB':
      return <BombFx key={effect.id} straightFlush={false} />;
    default:
      return null;
  }
}

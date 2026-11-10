import { useEffect, useState } from 'react';
import { PICK_MS, SHELL_NAMES, UNIT_KINDS, type UnitKind, type UnitView } from '@/types/artillery';
import { useArtilleryStore } from './artilleryStore';

const KINDS: UnitKind[] = ['SPROUT', 'ACORN', 'DANDELION'];
export const KIND_ICONS: Record<UnitKind, string> = { SPROUT: '🌱', ACORN: '🌰', DANDELION: '🌼' };

interface Props {
  units: UnitView[];
  mySeat: number;
  /** 고를 수 있나(내 좌석·연결·아직 안 고름). */
  canPick: boolean;
  myPick: UnitKind | null;
  nameOf: (seat: number) => string;
  onPick: (kind: UnitKind) => void;
}

/** 고르기 남은 초 — resync 면 서버의 남은 시간부터, 아니면 15초부터. 0 이 되면 서버가 안 고른 사람을 새싹포로 정한다. */
function usePickSeconds(): number {
  const clock = useArtilleryStore((s) => s.pickClock);
  const deadline = clock ? clock.since + (clock.remainingMs ?? PICK_MS) : null;
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (deadline === null) return;
    setNow(Date.now());
    const timer = window.setInterval(() => setNow(Date.now()), 250);
    return () => window.clearInterval(timer);
  }, [deadline]);
  return deadline === null ? 0 : Math.ceil(Math.max(0, deadline - now) / 1000);
}

/**
 * 판 시작 유닛 고르기 (D-224) — 전원이 동시에 15초 안에 고른다. 남이 무엇을 골랐는지는 다 고를 때까지 숨긴다("골랐어요"만).
 * 수치는 서버 `UnitKind` 미러(안내용 — 판정은 서버).
 */
export function ArtilleryPicker({ units, mySeat, canPick, myPick, nameOf, onPick }: Props) {
  const seconds = usePickSeconds();
  const me = units.find((u) => u.seat === mySeat);
  const iPicked = myPick !== null || !!me?.picked;
  return (
    <section className="ar-picker" aria-label="포대 고르기">
      <header className="ar-picker-head">
        <h2 className="ar-picker-title">포대를 고르세요</h2>
        <span className={`ar-badge ar-countdown${seconds <= 5 ? ' ar-countdown-urgent' : ''}`} role="timer" aria-live="off">
          ⏱ {seconds}초
        </span>
      </header>
      <p className="ar-picker-note">안 고르면 새싹포로 시작해요. 다 고르면 함께 공개돼요.</p>
      <div className="ar-picker-cards" role="radiogroup" aria-label="포대 종류">
        {KINDS.map((k) => {
          const info = UNIT_KINDS[k];
          const chosen = myPick === k;
          return (
            <button
              key={k}
              type="button"
              role="radio"
              aria-checked={chosen}
              className={`ar-pick-card${chosen ? ' ar-pick-card-on' : ''}`}
              disabled={!canPick || iPicked}
              onClick={() => onPick(k)}
            >
              <span className="ar-pick-icon" aria-hidden="true">
                {KIND_ICONS[k]}
              </span>
              <span className="ar-pick-name">{info.name}</span>
              <span className="ar-pick-blurb">{info.blurb}</span>
              <dl className="ar-pick-stats">
                <dt>체력</dt>
                <dd>{info.hp}</dd>
                <dt>연료</dt>
                <dd>{info.fuel}</dd>
                <dt>바람</dt>
                <dd>{info.windPercent}%</dd>
                <dt>특수탄</dt>
                <dd>{SHELL_NAMES[info.special]} ×2</dd>
              </dl>
            </button>
          );
        })}
      </div>
      <ul className="ar-picker-seats" aria-label="고르기 현황">
        {units.map((u) => (
          <li key={u.seat} className={u.picked ? 'ar-picked' : ''}>
            {nameOf(u.seat)}
            {u.seat === mySeat ? ' (나)' : ''} — {u.seat === mySeat && myPick ? UNIT_KINDS[myPick].name : u.picked ? '골랐어요' : '고르는 중'}
          </li>
        ))}
      </ul>
    </section>
  );
}

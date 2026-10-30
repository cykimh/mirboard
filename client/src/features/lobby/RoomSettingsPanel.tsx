import type { GameSummary, RaceButtonSize, Room, RoomSettingsUpdate } from '@/types/api';
import { Label } from '@/components/ui/label';
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group';

/** D-154 — 게임이 턴 제한 선택지를 싣지 않았을 때(구 서버·픽스처). 방 만들기 모달과 같다. */
const DEFAULT_TURN_CHOICES = [0, 30, 60, 90];
/** 목표 점수 선택지(티츄) — 예전 방 만들기 모달과 같다. */
const TARGET_SCORE_CHOICES = [300, 500, 700, 1000];
/** 판돈 선택지 — 서버 허용 집합 `RoomService.ALLOWED_STAKES`. 0 = 내기 없음. */
const STAKE_CHOICES = [0, 10, 50, 100, 500];
const RACE_SIZE_LABEL: Record<RaceButtonSize, string> = { SMALL: '작게', MEDIUM: '보통', LARGE: '크게' };

interface Props {
  room: Room;
  /** 이 방 게임의 카탈로그 정보. 모르면(요청 중·실패) 턴 제한만 기본 선택지로 그린다. */
  game: GameSummary | null;
  /** 방장만 바꾼다. 다른 사람은 지금 값만 본다. */
  canEdit: boolean;
  onChange: (settings: RoomSettingsUpdate) => void;
  /** 바꾸는 중이면 선택지를 잠근다. */
  pending?: boolean;
}

const turnLabel = (v: number) => (v === 0 ? '끔' : `${v}초`);
const stakeLabel = (v: number) => (v === 0 ? '없음' : `${v}칩`);

/**
 * D-161 — 대기실 게임 설정. 방 만들기에서 고르던 턴 제한·목표 점수·판돈과 원카드! 버튼 크기를 방장이 여기서 바꾼다. 게임이 선언한
 * 설정만 보인다(D-106 — 서버도 같은 집합으로 검증). 다른 사람 화면은 방 메타 갱신으로 바로 바뀐다.
 */
export function RoomSettingsPanel({ room, game, canEdit, onChange, pending = false }: Props) {
  const options = game?.supportedRoomOptions ?? [];
  const rows: { label: string; value: string; choices: { value: string; text: string }[]; send: (v: string) => RoomSettingsUpdate }[] = [
    {
      label: '턴 제한',
      value: String(room.turnSeconds ?? 0),
      choices: (game?.turnSecondsChoices ?? DEFAULT_TURN_CHOICES).map((v) => ({ value: String(v), text: turnLabel(v) })),
      send: (v) => ({ turnSeconds: Number(v) }),
    },
  ];
  if (options.includes('TARGET_SCORE')) {
    rows.push({
      label: '목표 점수',
      value: String(room.targetScore),
      choices: TARGET_SCORE_CHOICES.map((v) => ({ value: String(v), text: String(v) })),
      send: (v) => ({ targetScore: Number(v) }),
    });
  }
  if (options.includes('BETTING')) {
    rows.push({
      label: '💰 판돈',
      value: String(room.stake ?? 0),
      choices: STAKE_CHOICES.map((v) => ({ value: String(v), text: stakeLabel(v) })),
      send: (v) => ({ stake: Number(v) }),
    });
  }
  if (options.includes('RACE_BUTTON_SIZE')) {
    rows.push({
      label: '원카드! 버튼 크기',
      value: room.raceButtonSize ?? 'MEDIUM',
      choices: (Object.keys(RACE_SIZE_LABEL) as RaceButtonSize[]).map((v) => ({ value: v, text: RACE_SIZE_LABEL[v] })),
      send: (v) => ({ raceButtonSize: v as RaceButtonSize }),
    });
  }

  return (
    <section aria-label="게임 설정" className="flex flex-col gap-3">
      {rows.map((row) => (
        <div key={row.label} className="flex flex-col gap-1.5">
          <Label>{row.label}</Label>
          {canEdit ? (
            <ToggleGroup
              type="single"
              aria-label={row.label}
              value={row.value}
              onValueChange={(v) => v !== '' && v !== row.value && onChange(row.send(v))}
              className="flex-wrap justify-start"
              disabled={pending}
            >
              {row.choices.map((c) => (
                <ToggleGroupItem key={c.value} value={c.value}>
                  {c.text}
                </ToggleGroupItem>
              ))}
            </ToggleGroup>
          ) : (
            <span className="text-sm">{row.choices.find((c) => c.value === row.value)?.text ?? row.value}</span>
          )}
        </div>
      ))}
      {!canEdit && <p className="text-xs text-muted-foreground">게임 설정은 방장이 바꿔요.</p>}
    </section>
  );
}

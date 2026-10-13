import { useEffect, useRef, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { shareRoomLink, type ShareEnv, type ShareResult } from './shareRoomLink';

interface InviteLinkButtonProps {
  roomId: string;
  roomName: string;
  /** 게임 표시 이름(카탈로그) — 공유창 제목 "{게임} 같이 해요". */
  gameName: string;
  /** 정원이 다 찼다(봇 자리 포함) — 링크를 받은 친구는 관전으로 들어온다. 버튼은 그대로 둔다. */
  full?: boolean;
  /** 공유창·클립보드. 기본은 브라우저 `navigator` — 테스트가 주입한다. */
  env?: ShareEnv;
  /** 링크의 사이트 주소. 기본은 지금 주소의 origin. */
  origin?: string;
}

/**
 * 입력란의 글 전체를 고른다. iOS WebKit(카톡 iOS 앱 내 브라우저 포함)은 읽기 전용 입력란에 `select()` 만으로는 선택이 잡히지 않는
 * 경우가 있어 `setSelectionRange(0, 길이)` 도 부른다(최종 리뷰 FR-6 — 실기기 확인은 QA 4번).
 */
function selectAll(input: HTMLInputElement) {
  input.select();
  input.setSelectionRange(0, input.value.length);
}

/** 공유됨·복사됨 알림이 떠 있는 시간. */
const NOTICE_MS = 3000;

/**
 * 브라우저 기본 공유 환경. 공유창은 휴대폰·태블릿(굵은 포인터)에서만 쓴다 — Web Share API 는 데스크톱 Chrome·Edge(Windows)·
 * Safari(macOS)에도 있어 그대로 두면 PC 에서 "링크 복사" 대신 OS 공유창(메일·근처 공유 — 카톡 PC 는 대상이 아님)이 뜬다.
 */
function browserShareEnv(): ShareEnv {
  const coarse = typeof window.matchMedia === 'function' && window.matchMedia('(pointer: coarse)').matches;
  return {
    // 메서드로 부른다 — 떼어 부르면 브라우저가 Illegal invocation 을 던진다.
    share: coarse && typeof navigator.share === 'function' ? (data) => navigator.share(data) : undefined,
    clipboard: navigator.clipboard,
  };
}

/**
 * D-133 — 대기실의 "초대 링크". 방 주소 그대로(`/rooms/<방ID>`)를 건넨다 — 서버가 그 주소의 HTML 에 방 미리보기를 넣고,
 * 로그인 안 된 친구는 로그인 화면을 거쳐 이 방으로 돌아온다. 공유창 → 복사 → 수동 순서는 `shareRoomLink` 가 정하고 여기는
 * 결과만 그린다. 수동(앱 내 브라우저)이면 링크를 선택해 둔 입력란을 보여 길게 눌러 복사하게 한다.
 */
export function InviteLinkButton({ roomId, roomName, gameName, full = false, env, origin }: InviteLinkButtonProps) {
  const [result, setResult] = useState<ShareResult | null>(null);
  const [busy, setBusy] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const url = `${origin ?? window.location.origin}/rooms/${encodeURIComponent(roomId)}`;

  async function handleClick() {
    setBusy(true);
    setResult(null);
    try {
      const outcome = await shareRoomLink(
        url,
        { title: `${gameName} 같이 해요`, text: `${roomName} — 미르보드카페` },
        env ?? browserShareEnv(),
      );
      setResult(outcome);
    } finally {
      setBusy(false);
    }
  }

  // 수동 복사 — 입력란이 뜨면 링크 전체를 선택해 둔다.
  useEffect(() => {
    if (result !== 'manual') return;
    const input = inputRef.current;
    if (!input) return;
    input.focus();
    selectAll(input);
  }, [result]);

  // 공유됨·복사됨은 짧은 알림 — 잠시 뒤 내린다.
  useEffect(() => {
    if (result !== 'shared' && result !== 'copied') return;
    const timer = setTimeout(() => setResult(null), NOTICE_MS);
    return () => clearTimeout(timer);
  }, [result]);

  return (
    <section aria-label="친구 초대" className="flex flex-col gap-2 rounded-lg border p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-muted-foreground">
          {full ? '자리가 다 찼어요 — 링크를 받은 친구는 관전으로 들어와요' : '링크를 보내면 친구가 바로 이 방에 들어와요.'}
        </p>
        <Button type="button" onClick={handleClick} disabled={busy}>
          초대 링크
        </Button>
      </div>
      {/* 라이브 영역은 늘 두고 내용만 바꾼다 — 영역과 내용을 함께 삽입하면 일부 스크린 리더가 읽지 않는다. */}
      <p role="status" className="min-h-5 text-sm">
        {result === 'shared' ? '초대 링크를 보냈어요' : result === 'copied' ? '링크를 복사했어요' : ''}
      </p>
      {result === 'manual' && (
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="invite-link-manual">직접 복사해 주세요</Label>
          <Input
            id="invite-link-manual"
            ref={inputRef}
            readOnly
            value={url}
            onFocus={(e) => selectAll(e.currentTarget)}
          />
        </div>
      )}
    </section>
  );
}

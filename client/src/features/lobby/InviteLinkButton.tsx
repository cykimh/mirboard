import { useEffect, useRef, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { showToast } from '@/components/toast/toastStore';
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
        { title: `${gameName} 같이 해요`, text: roomName },
        env ?? browserShareEnv(),
      );
      setResult(outcome);
      // D-157 — 공유됨·복사됨은 토스트로(버튼 줄에 글을 끼우면 줄이 일그러졌다).
      if (outcome === 'shared') showToast('초대 링크를 보냈어요');
      else if (outcome === 'copied') showToast('링크를 복사했어요');
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

  // D-146 — 대기실 위쪽 버튼 줄에 버튼 하나만 둔다. 설명은 title 로, 알림은 토스트로(D-157). 수동 입력란은
  // 버튼 줄 아래에 한 줄을 다 쓰고 끼어든다(줄 안 flex-wrap 의 basis-full) — 버튼에 붙여 띄우면 휴대폰에서 화면 왼쪽 밖으로 나갔다.
  return (
    <>
      <Button
        type="button"
        variant="outline"
        onClick={handleClick}
        disabled={busy}
        title={full ? '자리가 다 찼어요 — 링크를 받은 친구는 관전으로 들어와요' : '링크를 보내면 친구가 바로 이 방에 들어와요'}
      >
        초대 링크
      </Button>
      {result === 'manual' && (
        <div className="order-last flex min-w-64 basis-full flex-col gap-1.5">
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
    </>
  );
}

import { useEffect, useState } from 'react';

interface ReconnectBannerProps {
  connected: boolean;
}

/**
 * Phase 8A — STOMP 끊김이 1초 이상 지속되면 노란 배너, 1분 이상이면 추가 안내(D-135 — 방장 강제 종료가 봇 판만이 돼 탈주 유예
 * 안내로). D-158 — 유예가 3분이 됐고, 안내는 유예가 끝나기 전에 보여야 쓸모가 있어 1분에 띄운다(예전엔 3분에 "2분"을 알렸다).
 * 끊김 시각화로 사용자가 "내 차례인데 왜 안 가지?" 같은 혼동을 피한다.
 */
export function ReconnectBanner({ connected }: ReconnectBannerProps) {
  const [disconnectedAt, setDisconnectedAt] = useState<number | null>(null);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (connected) {
      setDisconnectedAt(null);
      return;
    }
    if (disconnectedAt === null) {
      setDisconnectedAt(Date.now());
    }
  }, [connected, disconnectedAt]);

  useEffect(() => {
    if (connected || disconnectedAt === null) return;
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, [connected, disconnectedAt]);

  if (connected || disconnectedAt === null) return null;

  const elapsedMs = now - disconnectedAt;
  if (elapsedMs < 1000) return null;  // 1초 미만 끊김은 무시 (네트워크 튐 노이즈).

  const elapsedSec = Math.floor(elapsedMs / 1000);
  const longDisconnect = elapsedMs >= 60 * 1000;

  return (
    <div
      role="status"
      style={{
        background: '#3b3000',
        color: '#ffe69c',
        padding: '8px 12px',
        borderRadius: 6,
        border: '1px solid #6b5300',
        marginBottom: 12,
        fontSize: 14,
      }}
    >
      재연결 중... ({elapsedSec}s)
      {longDisconnect && (
        <div style={{ marginTop: 4, fontSize: 12, opacity: 0.85 }}>
          장시간 끊김 — 게임 중 3분 넘게 끊기면 탈주로 처리됩니다. 네트워크를 확인해 주세요.
        </div>
      )}
    </div>
  );
}

import { useEffect, useState } from 'react';

interface ReconnectBannerProps {
  connected: boolean;
}

/**
 * Phase 8A — STOMP 끊김이 1초 이상 지속되면 노란 배너, 3분 이상이면 추가 안내.
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
  const longDisconnect = elapsedMs >= 3 * 60 * 1000;

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
          장시간 끊김 — 호스트가 게임을 강제 종료할 수 있습니다.
        </div>
      )}
    </div>
  );
}

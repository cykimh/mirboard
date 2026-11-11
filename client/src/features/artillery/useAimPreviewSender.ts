import { useEffect, useRef } from 'react';
import type { Shell } from '@/types/artillery';
import { previewDue } from './liveMove';

/** 바뀌었는지·유지 간격이 됐는지 살피는 주기(ms) — 보내는 간격보다 잘게 봐야 250ms 박자가 밀리지 않는다. */
const CHECK_MS = 125;

/**
 * 내 조준 미리보기 보내기 (D-251, 설계 §3·§6.5) — 차례 동안 포신 각도·고른 탄이 바뀌면 초당 4번까지, 안 바뀌어도 2초마다 한 번
 * (resync 로 들어온 사람의 포신을 맞춘다). **힘은 보내지 않는다**(사용자 결정 — 발사 순간의 긴장). `n` 은 보낸 순서 — 받는 쪽이 낡은
 * 프레임을 버린다.
 */
export function useAimPreviewSender({
  active,
  angle,
  shell,
  sendPreview,
}: {
  active: boolean;
  angle: number;
  shell: Shell;
  sendPreview: ((preview: Record<string, unknown>) => void) | undefined;
}) {
  const current = useRef({ angle, shell });
  current.current = { angle, shell };
  const sent = useRef<{ angle: number; shell: Shell; at: number } | null>(null);
  const n = useRef(0);
  const sendRef = useRef(sendPreview);
  sendRef.current = sendPreview;

  useEffect(() => {
    if (!active) {
      sent.current = null;
      return;
    }
    const check = () => {
      const now = Date.now();
      const cur = current.current;
      const last = sent.current;
      const changed = !last || last.angle !== cur.angle || last.shell !== cur.shell;
      if (!previewDue(changed, last?.at ?? 0, now)) return;
      n.current += 1;
      sendRef.current?.({ '@action': 'PREVIEW', angle: cur.angle, shell: cur.shell, n: n.current });
      sent.current = { ...cur, at: now };
    };
    check();
    const timer = window.setInterval(check, CHECK_MS);
    return () => window.clearInterval(timer);
  }, [active]);
}

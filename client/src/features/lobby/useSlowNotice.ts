import { useEffect, useState } from 'react';

/** D-197 — 이만큼 기다리면 "서버 깨우는 중" 안내로 바꾼다. 깨어 있는 서버의 방 만들기는 0.2초쯤이라 이 시간을 넘기면 콜드 스타트다. */
export const SLOW_NOTICE_MS = 2000;

/** D-197 — 방 만들기·참여하기 버튼이 오래 걸릴 때의 글자. 운영 서버는 쉬다가 첫 요청에 깨어난다(약 30초). */
export const WAKING_LABEL = '서버 깨우는 중… (최대 30초)';

/**
 * D-197 — 요청이 `active` 인 채로 `delayMs` 를 넘기면 true. 요청이 끝나면(`active` false) 바로 false 로 돌아가고 타이머도 지운다
 * (언마운트 포함). 방 만들기 모달과 메인 카드 [참여하기]가 같이 쓴다.
 */
export function useSlowNotice(active: boolean, delayMs: number = SLOW_NOTICE_MS): boolean {
  const [slow, setSlow] = useState(false);
  useEffect(() => {
    if (!active) {
      setSlow(false);
      return;
    }
    const id = window.setTimeout(() => setSlow(true), delayMs);
    return () => window.clearTimeout(id);
  }, [active, delayMs]);
  return active && slow;
}

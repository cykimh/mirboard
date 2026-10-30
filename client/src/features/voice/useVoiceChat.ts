import { useCallback, useEffect, useRef, useState } from 'react';

/** 클라 → 서버 (`/app/room/{id}/voice`). `to` 는 OFFER·ANSWER·ICE 의 받는 사람. */
export interface VoiceSignal {
  type: 'JOIN' | 'LEAVE' | 'OFFER' | 'ANSWER' | 'ICE';
  to?: number;
  data?: string;
}

/** 서버 → 클라(방 공지·본인 큐 둘 다). `from` 은 서버가 붙인 보낸 사람 userId. */
export interface VoiceMessage {
  type: string;
  from: number;
  data: string | null;
}

export type PeerState = 'connecting' | 'connected' | 'failed';

interface Options {
  myUserId: number;
  connected: boolean;
  send: (signal: VoiceSignal) => void;
  /** 음성 신호 받기(방 공지 + 본인 큐). 해제 함수를 돌려준다. */
  subscribe: (handler: (m: VoiceMessage) => void) => () => void;
}

/** 무료 공개 STUN 만 — 중계(TURN)는 두지 않는다(D-155, 무료 유지). */
const ICE_SERVERS: RTCIceServer[] = [{ urls: 'stun:stun.l.google.com:19302' }];

/**
 * D-155 — 음성 대화(무료 P2P, WebRTC 메시). 소리는 브라우저끼리 직접 오가고 서버는 신호만 중계한다(`VoiceSignalController`).
 *
 * <ul>
 *   <li>참여(`join`)는 마이크를 묻지 않는다 — 듣기만. 각 연결은 처음부터 `sendrecv` 트랜시버라 마이크를 켤 때 연결을 다시 맺지 않고
 *       `replaceTrack` 만 한다.</li>
 *   <li>서로를 아는 법: 들어오면 JOIN 을 알리고, 모르는 사람의 JOIN 을 받으면 내 JOIN 을 한 번 더 알린다(서버는 참여자를 기억하지
 *       않는다). 두 사람 중 userId 가 작은 쪽이 OFFER 를 보낸다(동시에 보내는 충돌 방지).</li>
 *   <li>원격 설명 전에 온 ICE 후보는 모아 두었다가 붙인다.</li>
 * </ul>
 * 게임 중립이다 — 게임판이 방 소켓의 `sendVoice`·`subscribeVoice` 를 넘긴다.
 */
export function useVoiceChat({ myUserId, connected, send, subscribe }: Options) {
  const [joined, setJoined] = useState(false);
  const [micOn, setMicOn] = useState(false);
  const [members, setMembers] = useState<number[]>([]);
  const [peerState, setPeerState] = useState<Record<number, PeerState>>({});
  const [micError, setMicError] = useState<string | null>(null);

  const joinedRef = useRef(false);
  const sendRef = useRef(send);
  sendRef.current = send;
  const pcs = useRef(new Map<number, RTCPeerConnection>());
  const pendingIce = useRef(new Map<number, RTCIceCandidateInit[]>());
  const audios = useRef(new Map<number, HTMLAudioElement>());
  const localTrack = useRef<MediaStreamTrack | null>(null);
  const known = useRef(new Set<number>());

  const setState = (peer: number, state: PeerState | null) =>
    setPeerState((prev) => {
      const next = { ...prev };
      if (state === null) delete next[peer];
      else next[peer] = state;
      return next;
    });

  const closePeer = useCallback((peer: number) => {
    pcs.current.get(peer)?.close();
    pcs.current.delete(peer);
    pendingIce.current.delete(peer);
    const audio = audios.current.get(peer);
    if (audio) {
      audio.srcObject = null;
      audios.current.delete(peer);
    }
    known.current.delete(peer);
    setMembers((prev) => prev.filter((m) => m !== peer));
    setState(peer, null);
  }, []);

  const createPeer = useCallback((peer: number): RTCPeerConnection => {
    const existing = pcs.current.get(peer);
    if (existing) return existing;
    const pc = new RTCPeerConnection({ iceServers: ICE_SERVERS });
    pcs.current.set(peer, pc);
    setState(peer, 'connecting');
    pc.onicecandidate = (e) => {
      if (e.candidate) sendRef.current({ type: 'ICE', to: peer, data: JSON.stringify(e.candidate.toJSON()) });
    };
    pc.ontrack = (e) => {
      let audio = audios.current.get(peer);
      if (!audio) {
        audio = new Audio();
        audio.autoplay = true;
        audios.current.set(peer, audio);
      }
      audio.srcObject = e.streams[0] ?? null;
      void audio.play?.()?.catch(() => {});
    };
    pc.onconnectionstatechange = () => {
      const s = pc.connectionState;
      if (s === 'connected') setState(peer, 'connected');
      else if (s === 'failed') setState(peer, 'failed');
    };
    return pc;
  }, []);

  /** 트랜시버를 보내기·받기로 두고, 마이크가 있으면 싣는다. */
  const attachLocal = async (pc: RTCPeerConnection) => {
    for (const t of pc.getTransceivers()) {
      t.direction = 'sendrecv';
      if (localTrack.current) await t.sender.replaceTrack(localTrack.current);
    }
  };

  const flushIce = async (peer: number, pc: RTCPeerConnection) => {
    const queued = pendingIce.current.get(peer) ?? [];
    pendingIce.current.delete(peer);
    for (const c of queued) await pc.addIceCandidate(c).catch(() => {});
  };

  const offerTo = useCallback(
    async (peer: number) => {
      const pc = createPeer(peer);
      if (pc.getTransceivers().length === 0) pc.addTransceiver('audio', { direction: 'sendrecv' });
      await attachLocal(pc);
      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);
      sendRef.current({ type: 'OFFER', to: peer, data: JSON.stringify(pc.localDescription ?? offer) });
    },
    [createPeer],
  );

  const handle = useCallback(
    async (m: VoiceMessage) => {
      if (!joinedRef.current || m.from === myUserId) return;
      const peer = m.from;
      switch (m.type) {
        case 'JOIN': {
          if (known.current.has(peer)) return;
          known.current.add(peer);
          setMembers((prev) => (prev.includes(peer) ? prev : [...prev, peer]));
          // 새로 온 사람이 나를 알게 한 번 더 알린다(이미 아는 사람들은 무시한다).
          sendRef.current({ type: 'JOIN' });
          if (myUserId < peer) await offerTo(peer);
          return;
        }
        case 'LEAVE':
          closePeer(peer);
          return;
        case 'OFFER': {
          if (!m.data) return;
          known.current.add(peer);
          setMembers((prev) => (prev.includes(peer) ? prev : [...prev, peer]));
          const pc = createPeer(peer);
          await pc.setRemoteDescription(JSON.parse(m.data) as RTCSessionDescriptionInit);
          await attachLocal(pc);
          const answer = await pc.createAnswer();
          await pc.setLocalDescription(answer);
          sendRef.current({ type: 'ANSWER', to: peer, data: JSON.stringify(pc.localDescription ?? answer) });
          await flushIce(peer, pc);
          return;
        }
        case 'ANSWER': {
          const pc = pcs.current.get(peer);
          if (!pc || !m.data) return;
          await pc.setRemoteDescription(JSON.parse(m.data) as RTCSessionDescriptionInit);
          await flushIce(peer, pc);
          return;
        }
        case 'ICE': {
          if (!m.data) return;
          const candidate = JSON.parse(m.data) as RTCIceCandidateInit;
          const pc = pcs.current.get(peer);
          if (pc && pc.remoteDescription) {
            await pc.addIceCandidate(candidate).catch(() => {});
          } else {
            pendingIce.current.set(peer, [...(pendingIce.current.get(peer) ?? []), candidate]);
          }
          return;
        }
      }
    },
    [myUserId, offerTo, closePeer, createPeer],
  );

  useEffect(() => subscribe((m) => void handle(m)), [subscribe, handle]);

  // 소켓이 다시 붙으면 참여 중임을 다시 알린다(끊긴 사이 새로 온 사람이 나를 모른다).
  useEffect(() => {
    if (connected && joinedRef.current) sendRef.current({ type: 'JOIN' });
  }, [connected]);

  const join = useCallback(() => {
    if (joinedRef.current) return;
    joinedRef.current = true;
    setJoined(true);
    sendRef.current({ type: 'JOIN' });
  }, []);

  const leave = useCallback(() => {
    if (!joinedRef.current) return;
    joinedRef.current = false;
    sendRef.current({ type: 'LEAVE' });
    for (const peer of [...pcs.current.keys()]) closePeer(peer);
    known.current.clear();
    localTrack.current?.stop();
    localTrack.current = null;
    setMembers([]);
    setPeerState({});
    setMicOn(false);
    setJoined(false);
  }, [closePeer]);

  const toggleMic = useCallback(async () => {
    if (!joinedRef.current) return;
    if (micOn && localTrack.current) {
      localTrack.current.enabled = false;
      setMicOn(false);
      return;
    }
    try {
      if (!localTrack.current) {
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        localTrack.current = stream.getAudioTracks()[0] ?? null;
      }
      if (!localTrack.current) return;
      localTrack.current.enabled = true;
      for (const pc of pcs.current.values()) {
        for (const t of pc.getTransceivers()) await t.sender.replaceTrack(localTrack.current);
      }
      setMicError(null);
      setMicOn(true);
    } catch {
      setMicError('마이크를 쓸 수 없어요 — 브라우저에서 마이크를 허용해 주세요');
    }
  }, [micOn]);

  // 게임판을 떠나면 나간다.
  const leaveRef = useRef(leave);
  leaveRef.current = leave;
  useEffect(() => () => leaveRef.current(), []);

  return { joined, micOn, micError, members, peerState, join, leave, toggleMic };
}

/** 좌석 표시용 — 그 사람이 음성 대화에 있으면 on, 나와의 연결이 실패했으면 failed, 아니면 없음. */
export function voiceStatusOf(
  voice: Pick<ReturnType<typeof useVoiceChat>, 'members' | 'peerState'>,
  userId: number | undefined,
): 'on' | 'failed' | undefined {
  if (userId == null || !voice.members.includes(userId)) return undefined;
  return voice.peerState[userId] === 'failed' ? 'failed' : 'on';
}

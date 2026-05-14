// STOMP envelope — `docs/stomp-protocol.md` 와 일치.

export interface StompEnvelope<TPayload = unknown> {
  eventId: string;
  type: string;
  ts: number;
  seq?: number;
  payload: TPayload;
}

export interface LobbyChatPayload {
  userId: number;
  username: string;
  message: string;
}

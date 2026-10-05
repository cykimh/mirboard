# 티츄 매 플레이 resync 제거 (D-126)

> 상태: **서버 완료**(브랜치 `worktree-fix-tichu-resync`), **클라 대기** — 원카드 S0(D-124, 순번 판정을
> `useStompRoom` 으로 옮기고 `tichuStore` 를 순수 리듀서로 만든다)가 main 에 병합된 뒤 그 코드를 기준으로
> 한다. 결정 배경은 `docs/decisions.md` D-126.

## 1. 원인과 측정

카드를 낼 때마다 비공개 `HAND_DEALT`(D-62)가 방 순번을 하나 써서, 다음 공개 이벤트가 모든 클라에서
구멍(gap)이었다 → 한 번 낼 때마다 4명 전원 REST resync. `TichuEventStreamIT` 가 봇 4명 매치 한 판의 STOMP
프레임을 전부 기록하고 클라 순번 판정을 좌석마다 흉내 내 센다(`server/build/d126-resync-stats.txt`).
모델은 resync 응답이 그 액션의 프레임이 다 나간 직후 온다고 본다 — 실제로는 더 늦으므로 수정 전 수치는 하한.

| 시점 | 플레이 수 | 클라 1명의 resync/플레이 | 클라 1명의 매치당 resync |
| --- | --- | --- | --- |
| 수정 전 | 391 | **1.263** | 569 |
| 서버만 수정 (현재 클라) | 242 | 0.103 | 75 |
| 클라 반영 후 (모델 예측) | 242 | **0.037** | 59 |

봇 매치 길이는 실행마다 다르다(라운드 수가 달라짐) — 비교는 플레이당 값으로 한다. 클라 반영 후 플레이
배치에 남는 resync 는 라운드가 끝나는 플레이의 `ROUND_STARTED`(새 손패 8장을 resync 로 받는다)뿐이다.
라이프사이클 resync(`DEALING_PHASE_STARTED`·`PASSING_STARTED`·`CARDS_PASSED`→`PLAYING_STARTED`)는 그대로다.

## 2. 매 플레이 resync 에 숨어 있던 의존 (전수 확인)

공개 뷰 `TableView` 필드별로 "그 값을 바꾸는 서버 전이 ↔ 클라 리듀서"를 맞대 봤다.

| 필드 / 클라 상태 | 바뀌는 때 | 이벤트·리듀서 | 조치 |
| --- | --- | --- | --- |
| `activeWishRank` → null | 소원 숫자가 나옴, 용 양도(§9 (b)) | **없었음** | 서버 `WISH_CLEARED` ✅, 클라 리듀서 ⏳ |
| `phase`·`currentTurnSeat`·`currentTop`·`roundScores`(최종)·`activeWishRank` | 라운드 종료(`ROUND_END`) | `ROUND_ENDED` 리듀서가 `roundEnded` 만 세팅 | 비최종 라운드는 같은 배치 `ROUND_STARTED` resync 가 덮음. **최종 라운드는 남는다** — 마지막에 낸 좌석이 여전히 차례로 보인다 ⏳ |
| `matchScores` | 라운드 종료 | 없음(`ROUND_STARTED` resync / `MATCH_ENDED.finalScores`) | `MATCH_ENDED` 때 반영 ⏳ |
| `roundEnded`·`matchEnded` | `ROUND_ENDED`·`MATCH_ENDED` | 리듀서는 있었지만 **항상 구멍이라 실행된 적이 없다** | 이제 실행된다. 그런데 `applySnapshot` 이 둘을 null 로 지워서 라운드 종료 표시가 `ROUND_STARTED` resync 에 바로 지워진다(깜빡임) ⏳ |
| `errorMessage` | 서버 `ERROR` | `applySnapshot` 이 지움 | 매 플레이 resync 가 사라지면 오래 남는다 ⏳ |
| 나머지(`handCounts`·`declarations`·`readySeats`·`passingSubmittedSeats`·`finishingOrder`·`currentTop` 트릭 중) | — | `PLAYED`·`PLAYER_FINISHED`·`TICHU_DECLARED`·`PLAYER_READY`·`PASSING_SUBMITTED`·`TRICK_TAKEN`·`TURN_CHANGED` | 이미 맞음 |
| 단계 전환(`DEALING`→`PASSING`→`PLAYING`, 새 라운드) | 라이프사이클 이벤트 | 미지 → resync | 그대로(설계상 resync) |

부수 발견: 수정 전에는 정상 종료 때 `MATCH_ENDED` 가 늘 구멍이라 종료 패널(리매치 버튼)이 뜨지 않았다(모델에서
`gap:MATCH_ENDED`, 탈주 종료만 단독 이벤트라 떴다). 서버 수정만으로 뜨기 시작한다.

## 3. 서버 (완료)

- 포트 `GameEvent.sequenced()`(기본 true) + `GameEventBroadcaster` 가 false 면 순번 미발급·seq null.
  원카드 설계 §4.5b 와 같은 시그니처 — 원카드 S3 는 이걸 그대로 쓴다.
- `TichuEvent.sequenced()` = `!isPrivate()` (`HAND_DEALT`·`CARDS_RECEIVED` 만 false).
- `TichuEvent.WishCleared(rank)` / `WISH_CLEARED` — `applyPlayCard`(달성)·`applyGiveDragonTrick`(소멸).
- `RoomController.resync` 가 `RoomActionLock.acquireWaiting` 안에서 상태·순번·뷰를 읽는다. 못 잡으면 잠금 없이.
- 테스트: `TichuEngineWishClearedTest`, `TichuEventSequencingTest`, `GameEventBroadcasterTest`,
  `RoomControllerResyncLockTest`, `TichuEventStreamIT`.

## 4. 클라 (S0 병합 뒤)

S0 이후 `tichuStore.applyEvent` 는 순번을 모르는 순수 리듀서다. 아래는 그 코드 기준이다.

1. **`WISH_CLEARED`** — `advance({ ...table, activeWishRank: null })`. 테스트: 소원 표시가 resync 없이 사라진다
   (`applied` 반환, `activeWishRank` null).
2. **`ROUND_ENDED`** — 기존 `roundEnded`·`roundHistory` 처리에 더해, 테이블이 있으면 서버 `ROUND_END` 뷰와
   같게 만든다: `phase: 'ROUND_END'`, `currentTurnSeat: -1`, `currentTop: null`, `currentTopSeat: -1`,
   `activeWishRank: null`, `roundScores: { A: teamAScore, B: teamBScore }`. 테스트: 마지막 플레이 뒤
   `myTurn` 이 false.
3. **`MATCH_ENDED`** — 테이블이 있으면 `matchScores: finalScores`.
4. **`applySnapshot`** — 라운드·매치 종료 표시는 **새 매치일 때만** 지운다(`tableView.completedRounds` 가 비어
   있으면 새 매치 — 리매치). 같은 매치 안의 resync(`ROUND_STARTED`·화면 복귀)는 유지. 테스트: `ROUND_ENDED`
   → `ROUND_STARTED` 스냅샷 뒤에도 `roundEnded` 유지, 리매치 스냅샷은 지움.
5. **`errorMessage`** — `TURN_CHANGED` 때 지운다(예전엔 매 플레이 resync 가 지웠다).
6. `TichuEventStreamIT.CLIENT_HANDLED` 에 `WISH_CLEARED` 를 넣고 `CLIENT_HANDLED_AFTER_D126` 을 지운다.
7. 문서: `docs/stomp-protocol.md` 리듀서 목록에 `WISH_CLEARED` 추가, "미지 이벤트로 resync" 문장 삭제.

## 5. 범위 밖 (후속)

- **스컬킹 `HAND_DEALT`** 도 순번을 쓴다(라운드당 1회 구멍, D-103 수용). 옮기려면 스컬킹 리듀서 감사가 먼저다.
- **용을 낸 직후 양도 모달 깜빡임**: `PLAYED`(내 용) 와 `TURN_CHANGED` 사이 한 프레임 동안 `mustGiveDragon`
  조건이 참이다. 예전엔 resync 왕복 동안 열려 있었으니 짧아졌지만 남아 있다.
- 매치 종료 뒤 새로고침하면 티츄는 `matchEnded` 를 복원하지 못한다(D-120 이 스컬킹에서 고친 `matchResult` 의 티츄판).

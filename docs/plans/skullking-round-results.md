# 스컬킹 라운드 결과 · 라운드별 점수표 계획

> 상태: **구현 완료 · 통합 대기** · 작성 2026-10-03 · 결정 D-120(`docs/decisions.md`)
> 범위: 스컬킹 전용. 서버 스컬킹 도메인 + 클라. `GameEngine` 포트·`ResyncResponse`·인프라 무변경.

## 1. 배경

실플레이에서 라운드 결과가 보이지 않는다. 원인은 세 겹이다.

| 증상 | 원인 |
| --- | --- |
| 라운드 결과 패널이 몇 ms 만에 사라진다 | `SkullKingGameEngine.advance` 가 정산(`ROUND_ENDED`)과 다음 라운드 시작(`BIDDING_STARTED`)을 **한 배치**로 보낸다. 클라의 `BIDDING_STARTED` 처리가 `roundScores` 를 비우므로, `phase==='ROUND_END'` 에만 뜨던 `SkullRoundEndPanel` 은 곧바로 내려간다 |
| 새로고침·재접속 뒤 라운드 내역이 없다 | 매치 상태(`SkullKingMatchState`)에 **누적 합만** 있다. 라운드 내역의 권위 원천이 없다 |
| 재접속하면 매치 종료 패널이 사라진다 | `applySnapshot` 이 `matchEnded` 를 `null` 로 고정해서 덮는다 |
| 봇 방은 라운드 10 결과·최종 점수를 아예 못 본다 | 매치가 끝나면 `MatchProgressService` 가 `markFinished` 를 부르고, 이 FINISHED 메타가 게임 이벤트보다 먼저 올 수 있다. 그러면 `RoomPage` 가 게임판을 내린다 |

티츄는 같은 문제를 D-108 에서 `TableView.completedRounds` 로 풀었다. 이번 계획은 같은
경로(공개 뷰 → resync)를 스컬킹에 적용하고, 타이밍·종료 전이 문제를 클라에서 푼다.

## 2. 목표 / 비목표

**목표**

- 다음 라운드 예측 중에 직전 라운드 결과를 **입력을 막지 않고** 보여 준다
- 헤더 '점수표' 로 라운드×좌석 점수표를 언제든 연다
- 어느 기기·어느 시점(새로고침·재접속·관전 진입)에 들어와도 점수표와 종료 패널이 정확하다
- 봇 방에서 매치가 끝나도 마지막 결과 화면이 남는다

**비목표**

- 서버가 라운드 사이에 멈추기 — §6 기각 대안
- 티츄 봇 방의 같은 종료 화면 소실 — 후속(§9)
- 처음부터 FINISHED 로 진입(새로고침)했을 때 게임판 종료 화면 — 기존 종료 카드 유지

## 3. 서버 — 스컬킹 도메인 안에 가둔다

| 파일 | 변경 |
| --- | --- |
| `state/SkullKingMatchState.java` | 5번째 컴포넌트 `completedRounds`(`CompletedRound(roundNumber, Map<seat, RoundScore>)`). 구 JSON 은 빈 목록, `@JsonIgnoreProperties(ignoreUnknown)` 로 다음 필드부터 롤백 안전. `withRoundCompleted(round, scores, seatCount)` 가 누적·기록·시작 좌석 회전을 **한 번에** 하고 번호 불일치는 `IllegalStateException`. `hasSettled(round)`. 기록을 빠뜨리던 `withRoundScored` 는 **삭제** |
| `SkullKingEngine.settleRound` | `withRoundCompleted(state.roundNumber(), state.scores(), …)`. 이벤트 무변경. 탈주 조기 종료는 진행 중 라운드를 기록하지 않는다(§13-⑲) |
| `SkullKingGameEngine.advance` | 이미 정산된 라운드면(`hasSettled`) **복구 분기** — §5 |
| `state/SkullKingStateMapper.java` | `toTableView(state, match)`. `TableView` 끝에 `completedRounds`(`CompletedRoundView`, total 포함)·`matchResult`(`MatchResultView`, 매치 종료 후에만) |
| `infra/**` | **0** — `grep -rniE skullking server/src/main/java/com/mirboard/infra` 0건 유지 |

테스트 픽스처 `MatchStateFixtures.scored(match, deltas, seatCount)` 가 삭제한 메서드의 테스트
호출부 14곳을 대신한다(좌석별 `RoundScore(0, 0, d, 0)` — 적중이라 §11 가드 통과).

## 4. 클라 — 덮어쓰기로 합류 + 다음 라운드 동안 표시

| 대상 | 변경 |
| --- | --- |
| `types/skullking.ts` | `CompletedRoundView`, `SkullKingTableView.completedRounds?`·`matchResult?`(옵셔널 — 기존 시드·구 응답 호환) |
| `skullkingStore.ts` | `completedRounds` — resync 는 **통째 교체**, `ROUND_ENDED` 는 번호 기준 **upsert**+정렬, `BIDDING_STARTED` 스크럽은 보존. `matchEnded = matchResult ?? null`. 쓰기만 하던 `roundEnded` 제거. 순수 셀렉터 `lastRoundResult` |
| `SkullScorePanels.tsx` | `SkullRoundEndPanel` → `SkullRoundResultPanel`(✓/✗, 내 행 강조, '점수표 전체'·'닫기', 사실과 다른 "다음 라운드가 곧 시작됩니다" 삭제). `SkullMatchEndPanel` 에 `<details>` 라운드 표(합계 = `finalScores`) |
| 신규 `SkullRoundHistoryTable.tsx` | 라운드×좌석 공용 표. 합계 행은 **호출자가 넘긴 권위 합계**, Σ행≠합계면 "일부 라운드 기록 없음" |
| 신규 `SkullRoundHistoryModal.tsx` | shadcn Dialog(`app-shell sk-history-modal`), 0라운드 빈 상태 |
| `SkullKingTable.tsx` | 헤더 '점수표'(채팅 앞), 결과 패널을 입력·손패 **아래**, 내 정보줄 '직전' 값, `roomFinished` prop |
| `RoomPage.tsx` | 이 세션에서 IN_GAME→FINISHED 전이를 본 스컬킹 게임판 유지 — §5 |

렌더 순서: 좌석 → (PLAYING 트릭 레일) → 매치 종료 패널 / 종료 안내 → [내 정보줄 →
예측 패널 → 예측 중 손패] → **라운드 결과 패널**. 입력 동선이 바뀌지 않고, 관전자에게는
좌석 바로 아래다.

### `lastRoundResult` — 번호를 대칭으로 확인한다

- 매치 종료면 `null` (종료 패널이 대신한다)
- `ROUND_END`: 마지막 기록이 이 라운드면 그것, 아니면 `roundScores` 로 합성(§5 경합)
- `BIDDING`: 마지막 기록이 **바로 앞 라운드**일 때만
- 그 외 `null`

## 5. 경계 조건

- **resync 경합** — resync 는 잠금 없이 라운드 상태와 매치 상태를 따로 읽는다. RoundEnd 저장과
  매치 저장 사이에 끼면 기록·누적이 한 라운드 늦은 과도 상태가 보인다. 셀렉터의 번호 대칭
  검사와 `roundScores` 합성으로 라벨을 맞추고, 다음 라이브 이벤트나 resync 가 자가 치유한다.
- **복구 분기** — 정산(매치 저장) 후 다음 라운드 저장 전에 멈춘 방은 저장 상태 RoundEnd(N),
  매치 N+1 이다. 탈주 CONTINUED 가 `advance(RoundEnd)` 를 다시 부르는 경로가 실제로 있다.
  `hasSettled(N)` 이면 이중 정산·이벤트 재발행 없이, 매치가 안 끝났으면 다음 라운드만
  시작하고 `Advance.NONE` 을 돌려준다(라운드 메트릭·FINISHED 중복 방지). 레코드의 번호 가드는
  정상 경로의 fail-fast 로 남는다.
- **FINISHED 유지** — `RoomPage` 의 메타 콜백이 `IN_GAME → FINISHED` 를 관측하면
  `boardHeld` 를 세우고 같은 렌더에서 `room` 을 바꾼다(언마운트 없음). 판단 근거가 "전이를
  봤는가" 하나라 메타·게임 이벤트의 도착 순서와 무관하다. 게임 분기 지점은 여전히 한 곳이고
  티츄는 기존 동작이다. `roomFinished` 면 입력을 숨기고, 나가기에 탈주 확인을 묻지 않고,
  매치 결과가 없으면(abort·MATCH_ENDED 직전 찰나) 종료 안내를 띄운다.
- **구 JSON** — 배포 시점에 진행 중이던 매치는 앞선 라운드 기록이 빈다. 합계는 권위값
  `cumulativeScores` 를 쓰므로 숫자는 틀리지 않고, 표가 "일부 라운드 기록 없음"을 알린다.
  `roundsPlayed` 는 마지막 기록 번호(없으면 상태로 역산)라 이 경우에도 `MatchEnded` 와 같다.
- **롤백** — 이 배포를 넘어 롤백하면, 롤백 대상 코드에는 `ignoreUnknown` 이 없어 진행 중
  스컬킹 방이 TTL 6h 동안 매치 상태 역직렬화에 실패한다. 진행 중 스컬킹 방이 없는 시점에
  롤백하거나 6h 를 감수한다. 다음 필드 추가부터는 이번 어노테이션이 막는다.
- **방 해시 TTL** — `room_finish.lua` 가 600s 로 줄인다. 유지된 게임판에서 10분 뒤 '메인으로'·
  resync 는 방 없음 오류 화면으로 떨어진다.

## 6. 배제한 대안

| 대안 | 배제 이유 |
| --- | --- |
| 서버가 라운드 사이에 멈춘다 | 대기 좌석이 비어 봇·타임아웃이 진행을 못 일으킨다 — 새 데드라인·포트 확장 필요. 멈춘 사이 탈주가 같은 라운드를 두 번 정산할 위험, 10라운드×N초 순수 지연 |
| 전원 '확인' 게이트 | AFK 한 명이 테이블을 붙잡는다 |
| 클라 라이브 누적만 | D-108 이전 티츄의 결함 반복(새로고침·두 번째 기기에서 빈다) |
| 별도 Redis 키 | 매치 상태와 원자적으로 맞춰야 하는 부담만 는다 |
| `ResyncResponse` 최상위 필드 | 포트 계약이 넓어진다 |
| `markFinished` 를 브로드캐스트 뒤로 | 메타·게임 이벤트가 다른 STOMP 구독이라 순서 보장이 없고, 호출자 3곳의 인프라를 고쳐야 한다 |

## 7. 작업 순서

1. 서버 테스트(픽스처 이전 + 기록·가드·뷰·복구 분기) → 매치 상태·정산·매퍼·어댑터 구현
2. 봇 풀매치 IT 에 기록 10건 단언(별도 테스트 메서드)
3. 클라 테스트(스토어·표/모달·게임판·RoomPage) → 타입·스토어 → 표/모달 → 게임판 → RoomPage
4. CSS(`18-skullking-table.css` 끝 `/* D-120 */`, `17-responsive.css` 끝 터치 하한)
5. 로컬 실측 후 보정 → 문서 동기화(§9)

## 8. 검증 기준

| 항목 | 방법 | 결과 |
| --- | --- | --- |
| 기록 누적·번호 가드·`hasSettled`·탈주 승계 | `SkullKingMatchStateTest` | 통과 |
| 2~8인 풀매치 기록 10건 = 누적, 이벤트 점수와 동일 | `SkullKingMatchSimulationTest` | 통과 |
| 조기 종료 기록 건수 = `roundsPlayed` | `SkullKingDesertionTest` | 통과 |
| 공개 뷰 기록·현재 라운드 은닉·매치 결과·복구 분기 | `SkullKingGameEngineCompletedRoundsTest` | 통과 |
| 구 JSON·모르는 필드 | `SkullKingJsonRoundTripTest` | 통과 |
| 실배선 봇 풀매치 기록 10건 | `SkullKingBotMatchSimulationIT` | 통과 |
| 스토어 교체·upsert·보존·matchResult 복원·셀렉터 경계 | `skullkingStore.test` | 통과 |
| 결과 패널 위치(예측 패널 뒤)·닫기·관전자·점수표·roomFinished | `SkullKingTable.test` | 통과 |
| FINISHED 전이 유지·새로고침 진입·티츄 무변경 | `RoomPage.test` | 통과 |
| `.sk-` 네임스페이스·18 의 `@media` 0개 | `skullkingCssNamespace.test`(무수정) | 통과 |
| 375×667 4인/8인 예측 화면, 모달 가로 스크롤·R 열 고정, 라이트/다크 대비, 봇 방 라운드 10 종료 화면 유지, 실서버 resync 필드 | 격리 포트 로컬 실행 실측 | 확인(7열 머리글 줄바꿈 발견 → nowrap·여백 보정) |

## 9. 문서 동기화 · 후속

- `docs/api.md` — 스컬킹 resync `tableView` 예시·필드(`completedRounds`·`matchResult`)
- `docs/stomp-protocol.md` — 라운드 사이 정지 없음, `ROUND_ENDED` upsert 패치·권위값
- `docs/redis-keys.md` — `match:{roomId}:state` 에 `SkullKingMatchState` 병기
- `docs/rules-skullking.md` — §3 `withRoundCompleted`·정지 구간 아님, §12 갭 D-115 정정
- `docs/qa-scenarios.md` — 수동 시나리오
- 통합 시: `docs/decisions.md` D-120, `docs/implementation-status.md`, `docs/plans/mvp-roadmap.md`
  M6 ②, `CLAUDE.md`(D-103 문장에 FINISHED 유지 병기), 인용 테스트 수(README·case-study)

**후속(별도 D)**: FINISHED 유지를 티츄 게임판으로 넓히기 — 티츄 `TableView` 의 매치 결과,
'한 판 더' 버튼의 `room.status` 게이팅, abort 안내가 선행이다.

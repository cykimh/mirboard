# 라운드별 점수 내역 모달 계획

> 상태: **설계 승인 · 미착수** · 작성 2026-08-04 · 결정 D-108(`docs/decisions.md`)
> 범위: 티츄 전용. 서버 티츄 도메인 + 클라, 인프라 무변경.

## 1. 배경

게임판 좌상단 점수 칩([TableArena.tsx:210](../../client/src/features/tichu/TableArena.tsx))은
`R{n} / 우리 X / 상대 Y` 누적만 보여준다. 라운드마다 몇 점씩 벌었는지는 매치가
끝나야(`MatchEndedPanel`) 볼 수 있다.

데이터는 이미 양쪽에 다 있다.

| 위치 | 값 | 성격 |
| --- | --- | --- |
| 서버 `TichuMatchState.roundScores` | `List<RoundScore>` | 영속. 라운드마다 `withRoundCompleted` 로 누적 |
| 클라 `tichuStore.roundHistory` | 같은 4필드 배열 | 휘발. 라이브 `ROUND_ENDED` 로만 누적 |

`RoundScore(teamAScore, teamBScore, firstFinisherSeat, doubleVictory)` 와 클라
`roundHistory` 엔트리는 **필드가 1:1**이라 매핑 비용이 없다.

**진짜 문제는 둘이 이어져 있지 않다는 것이다.** `applySnapshot`
([tichuStore.ts:244](../../client/src/features/tichu/tichuStore.ts))은 `roundHistory`
를 건드리지 않는다. 그래서 다음 상황에서 내역이 비거나 일부만 남는다.

- 브라우저 새로고침 / 서버 재배포 후 재접속
- 두 번째 기기(폰)로 같은 매치에 붙기
- 매치 도중 관전 진입

기존 `MatchEndedPanel` 의 라운드 표도 같은 약점을 이미 갖고 있다 — 이번에 함께 해소된다.

## 2. 목표 / 비목표

**목표**

- 점수 칩을 눌러 라운드별 획득 점수를 언제든 확인
- 어느 기기·어느 시점에 들어와도 내역이 정확
- `MatchEndedPanel` 과의 표 중복 제거

**비목표**

- 티츄 선언 성패 표시 — `RoundScore` 에 없다. 서버 정산 구조 변경이 필요해 별건
- 스컬킹 — 매치 영속 자체가 미구현(D-102 보류 ①)
- 좌석별 기여도(MVP) 노출 — `SeatContribution` 은 있으나 이번 범위 밖

## 3. 서버 — 티츄 도메인 안에 가둔다

`RoomController.resync`([RoomController.java:188](../../server/src/main/java/com/mirboard/infra/rest/rooms/RoomController.java))
는 `engine.publicView(state)` 만 부르는 **게임 중립** 코드다(D-98). 따라서 티츄 공개
뷰에 필드를 더하면 인프라가 전부 무변경으로 남는다.

| 파일 | 변경 |
| --- | --- |
| `domain/game/tichu/state/TableView.java` | `List<RoundScore> completedRounds` 필드 추가 (+ compact constructor 에 `List.copyOf`) |
| `domain/game/tichu/TichuGameEngine.java:141` | `publicView` 가 `TichuMatchState.roundScores` 를 실어줌 |
| `infra/**` | **0** — 컨트롤러·`ResyncResponse`·`GameEngine` 포트 계약 무변경 |

### 이름 주의 — `roundScores` 재사용 금지

`TableView` 에는 **이미 `roundScores` 가 있다**. 그건 `Map<Team,Integer>` 로 **현재
라운드의 팀별 점수**다. 라운드 이력과 이름이 겹치므로 새 필드는 `completedRounds` 로
간다. 이 한 줄이 이 계획에서 가장 사고 나기 쉬운 지점이다.

### 배제한 대안

`ResyncResponse` 에 최상위 필드를 새로 다는 방식. `GameEngine` 포트가 "라운드 이력"
개념을 알아야 해서 스컬킹 등 다른 게임까지 끌고 들어간다. 티츄만의 관심사를 포트로
올리지 않는다.

## 4. 클라 — 덮어쓰기로 합류

`roundHistory` 의 진실원을 정리한다.

- `applySnapshot` 이 `tableView.completedRounds` 로 `roundHistory` 를 **통째로 교체**
  (append 아님 → 중복 누적이 구조적으로 불가능)
- `ROUND_ENDED` 라이브 append([tichuStore.ts:386](../../client/src/features/tichu/tichuStore.ts))
  는 즉시 반응성 때문에 **유지**
- 어긋나면 다음 resync 가 교정한다

`types/tichu.ts` 의 `TableView` 미러에 `completedRounds` 추가.

## 5. UI

| 대상 | 변경 |
| --- | --- |
| `TableArena.tsx:210` `.scoreboard` | `div` → `button` (`aria-haspopup="dialog"`, 키보드 포커스) |
| 신규 `RoundHistoryModal.tsx` | shadcn Dialog. `MakeWishModal`/`PassReceivedModal` 과 같은 패턴 |
| 신규 `RoundHistoryTable.tsx` | 공용 표. 아래 §6 |
| `GameTable.tsx` | 모달 렌더 + open 상태 (`useGameTableModel` 에 얹지 않고 로컬 `useState` — 서버 상태가 아니다) |

표 구성: `R | 우리 | 상대 | 배지` + 합계 행.

- **"우리/상대"는 내 팀 기준으로 스왑한다.** 점수 칩이 이미 그 관점이므로 Team A/B 를
  그대로 쓰면 칩과 어긋난다. 내 좌석이 B팀이면 `teamBScore` 가 "우리" 열로 간다.
- 배지: 더블 빅토리, 첫 완주자 닉네임(`playerIds` → `usernames` 매핑)
- 375px 에서는 배지를 아이콘 + `title` 로 축약해 4열이 안 깨지게 한다

### 경계 조건

- **합계 행은 "지금까지 누적"이다.** 진행 중 매치에서는 `tableView.matchScores` 와
  같은 값이어야 한다 — 라운드 증분의 합이 칩에 찍힌 누적과 어긋나면 그 자체가 버그
  신호다. 매치 종료 화면에서는 기존대로 `matchEnded.finalScores` 를 쓴다.
- **완료 라운드가 0개일 때**(라운드 1 진행 중) 모달은 빈 표 대신 "아직 끝난 라운드가
  없습니다" 를 보여준다. 칩은 항상 눌리게 두고 비활성화하지 않는다 — 눌리지 않는
  버튼은 기능이 없는 것처럼 읽힌다.
- **관전자**도 동일하게 볼 수 있다. `completedRounds` 는 공개 뷰라 State Hiding 과
  무관하다(손패 아님).

CSS 는 게임판 규약을 따른다 — `styles/parts/` 에 새 part 추가, `17-responsive.css` 는
계속 마지막(D-88/D-94 불변식).

## 6. 중복 제거 — `RoundHistoryTable` 추출

`MatchEndedPanel.tsx:116` 이 이미 같은 표(`.score-history`)를 갖고 있다. 공용
컴포넌트로 뽑아 둘이 공유한다.

관점이 다르므로(패널=`Team A`/`Team B`, 모달=`우리`/`상대`) 컴포넌트는 **표기를 모르게**
만든다 — 행 데이터와 열 라벨을 prop 으로 받는다.

```
RoundHistoryTable({ rows, labels, totals })
  rows:   { round, left, right, doubleVictory, finisherName? }[]
  labels: { left: string; right: string }
  totals: { left: number; right: number }
```

각 호출자가 자기 관점으로 매핑해서 넘긴다. `MatchEndedPanel` 은 **표기·동작 무변경**이
목표다(리팩터링이지 UX 변경이 아님).

## 7. 작업 순서

1. 서버: `TableView.completedRounds` + `publicView` 배선 + 단위 테스트
2. 클라 타입 미러 + `applySnapshot` 덮어쓰기 + 스토어 테스트
3. `RoundHistoryTable` 추출 → `MatchEndedPanel` 을 그 위로 이관 (렌더 무변경 확인)
4. `RoundHistoryModal` + 칩 버튼화 + 관점 스왑
5. CSS part + 375px 실측
6. 문서 동기화 (§9)

3번을 4번보다 먼저 두는 이유: 기존 화면을 먼저 공용 컴포넌트로 옮겨 **무변경을 확인한
뒤** 새 호출자를 붙여야, 표가 깨졌을 때 원인이 추출인지 신규인지 갈린다.

## 8. 검증 기준

| 항목 | 방법 |
| --- | --- |
| `publicView` 가 `completedRounds` 를 싣는다 | 서버 단위 테스트 |
| resync 가 `roundHistory` 를 덮어쓴다 | `tichuStore` 테스트 |
| ROUND_ENDED append 후 resync 로 교정된다 | `tichuStore` 테스트 (중복 누적 0 확인) |
| 칩 클릭 → 모달 | RTL |
| 내 좌석이 B팀일 때 "우리" 열이 `teamBScore` | RTL — 관점 스왑 회귀 고정 |
| `MatchEndedPanel` 표 무변경 | 추출 전후 렌더 DOM 비교 (D-87 방식) |
| 375px 4열 유지 | 브라우저 실측 |

`./scripts/check.sh` 로 서버·클라 전량 그린.

## 9. 문서 동기화

- `docs/api.md:344` — resync 응답 예시의 `tableView` 에 `completedRounds` 추가
- `docs/stomp-protocol.md` — `TableView` 언급부
- `docs/implementation-status.md` — 기능 표
- `docs/decisions.md` — D-108 (기재 완료)

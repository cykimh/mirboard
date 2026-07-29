# GameTable 컴포넌트 분해 계획

> 상태: **완료** · 작성 2026-07-29 · 착수·완료 2026-07-29 · 트랙 외 기술부채
> D-87 기재 완료 (`docs/decisions.md`). 브랜치 `refactor/gametable-decompose`.
> 결과 요약은 아래 §9.

## 1. 배경

`client/src/features/tichu/GameTable.tsx`의 `GameTable` 함수가 **78~992줄, 약 915줄**이다. 파일 전체는 1,030줄.

| 항목 | 수 |
| --- | --- |
| `useState` | 6 |
| `useEffect` | 10 |
| `useMemo` | 7 |
| `useRef` | 6 |
| 핸들러 함수 | 10 |

M1의 A6/A7 게임판 UX 폴리시가 연속으로 이 파일에 쌓인 결과다. 서버(`domain`/`infra`)와 클라의 나머지 구획(`features`/`pages`/`components/ui`/`lib`/`api`/`ws`)은 건전하며, 이 파일만 확연한 이상치다. 서버 최대 파일도 `TichuEngine.java` 536줄로 정상 범위다.

**직접 테스트가 없다.** 클라 테스트 19파일 124건이 모두 통과하지만(2026-07-29 기준선 확인, `tsc --noEmit`도 통과) `GameTable`을 검증하는 테스트는 하나도 없다. 인접 단위 테스트만 있다 — `CardChip` · `SeatCardStack` · `useHandOverlap` · `tichuStore` 계열.

크기와 무테스트가 겹쳐 다음 클라이언트 UX 변경 시 회귀 위험이 가장 큰 지점이다.

## 2. 목표 / 비목표

**목표**

- `GameTable`을 조립 루트(150줄 안팎)로 축소한다
- 추출된 프레젠테이션 컴포넌트에 렌더 테스트를 붙일 수 있는 상태로 만든다

**비목표 (이 작업에서 하지 않는다)**

- **동작·시각 변경 일절 금지.** A6/A7에서 다듬은 결과가 그대로 유지되어야 한다
- 스타일시트(`.game-table-*`, `.table-arena`, `.my-hand`, `.match-ended`) 클래스명 변경
- STOMP 프로토콜 · `tichuStore` 구조 · 서버 계약 변경
- 성능 최적화(`useCallback` 추가, memo 도입 등) — 별건으로 다룬다
- 새 기능 추가

## 3. 현재 구조 지도

줄 번호는 착수 시점에 이미 어긋날 수 있으므로 **앵커(클래스명·식별자) 기준**으로 적는다.

| 구획 | 앵커 | 대략 분량 |
| --- | --- | --- |
| 훅·상태·핸들러 | 함수 시작 ~ `return (` 직전 | ~330줄 |
| 레이아웃 래퍼 | `div.game-table-layout` > `section.game-table` | ~5줄 |
| 헤더 | `header.game-table-header` | ~105줄 |
| 아레나 | `div.table-arena` | ~178줄 |
| 내 손패·액션 | `{!spectator && <div className="my-hand">}` | ~120줄 |
| 에러·모달 | `errorMessage`, `PassReceivedModal`, `MakeWishModal`, `GiveDragonTrickModal` | ~25줄 |
| 매치 종료 | `div.match-ended` | ~130줄 |
| 채팅 | `{chatOpen && <RoomChat>}` | ~10줄 |
| 순수 함수 | `getSelectedKeys` | ~19줄 |
| 카운트다운 | `TurnCountdown` (이미 독립 함수) | ~17줄 |

## 4. 분해 계획

같은 디렉토리(`features/tichu/`) 아래 파일을 나눈다. 새 하위 폴더는 만들지 않는다 — 기존 구획 관례를 따른다.

| 새 파일 | 내용 | 성격 |
| --- | --- | --- |
| `GameTableHeader.tsx` | `header.game-table-header` | 프레젠테이션 |
| `TableArena.tsx` | `div.table-arena` (좌석·트릭·스택) | 프레젠테이션 |
| `MyHandPanel.tsx` | `div.my-hand` + 액션 버튼 | 프레젠테이션 |
| `MatchEndedPanel.tsx` | `div.match-ended` | 프레젠테이션 |
| `TurnCountdown.tsx` | 기존 함수 이동 | 프레젠테이션 |
| `useGameTableEffects.ts` | `useEffect` 6개 (턴 전환·이펙트 트리거·플라이 애니메이션) + `prevMyTurnRef`·`flyIdRef` | 훅 |
| `useGameActions.ts` | 핸들러 10개 (`handlePlay`·`handlePass`·`handleCardClick` 등) | 훅 |
| `gameTableSelection.ts` | `getSelectedKeys` | 순수 함수 |

`GameTable.tsx`에는 스토어 구독, 파생 `useMemo`, 위 컴포넌트 조립만 남는다.

## 5. 작업 순서

**순서를 지킨다. 테스트 없이 추출부터 하지 않는다.**

1. **특성화 테스트 추가** — `GameTable.test.tsx` 신규. 현재 동작을 고정한다
   - 관전자 모드에서 `div.my-hand` 미렌더
   - 딜링 단계 준비 버튼 렌더 및 클릭 시 `handleReady` 경로 호출
   - 매치 종료 시 `div.match-ended` 렌더
   - 카드 클릭 → 선택 토글 반영
   - 이 시점에 `npx vitest run` 그린 확인 (124 + 신규)
2. **순수 함수·독립 함수 이동** — `getSelectedKeys`, `TurnCountdown`. 가장 안전한 것부터
3. **훅 추출** — `useGameActions` → `useGameTableEffects` 순. 훅 하나 뺄 때마다 테스트 실행
4. **프레젠테이션 추출** — `MatchEndedPanel` → `MyHandPanel` → `GameTableHeader` → `TableArena` 순.
   의존이 적은 것부터. **컴포넌트 하나 뺄 때마다 커밋**한다
5. **최종 검증** — §6

각 단계는 독립 커밋으로 남긴다. 되돌릴 지점을 촘촘히 둔다.

> TaskCreate로 위 5단계를 분해하고 단계 시작 시 `in_progress`, 종료 즉시 `completed` 처리한다 (CLAUDE.md 규약).

## 6. 검증 기준

| 항목 | 명령 | 기준 |
| --- | --- | --- |
| 타입 | `npx tsc --noEmit` (client) | 에러 0 |
| 단위 테스트 | `npx vitest run` (client) | 기존 124건 + 신규 전부 통과 |
| 빌드 | `npm run build` (client) | 성공 |
| 사전 게이트 | pre-commit `check:fast` | 통과 |
| 육안 | 좁은폭(600) / 와이드(1366) 게임판 | A6/A7 결과와 **픽셀 동일** |
| 크기 | `wc -l GameTable.tsx` | 200줄 이하 |

육안 검증은 A6/A7에서 쓴 Playwright 시나리오(딜링·패스·플레이)를 재사용한다.

## 7. D-87 초안 (착수 시 `docs/decisions.md`에 먼저 추가)

```markdown
## D-87 (YYYY-MM-DD) — GameTable 컴포넌트 분해

M1 A6/A7 게임판 UX 폴리시가 연속으로 쌓이며 `GameTable.tsx`의 단일 컴포넌트가
915줄까지 커졌고, 직접 테스트가 없어 다음 UX 변경 시 회귀 위험이 가장 높은
지점이 되었다. 동작·시각 변경 없이 프레젠테이션 4개(`GameTableHeader`·
`TableArena`·`MyHandPanel`·`MatchEndedPanel`)와 훅 2개(`useGameTableEffects`·
`useGameActions`)로 분해하고, 추출 전에 특성화 테스트를 먼저 붙여 안전망을
확보한다. 상세는 `docs/plans/gametable-refactor.md`.
```

## 8. 주의사항

- **이 작업은 상용화 트랙(M0~M5) 밖의 기술부채 정리다.** 마일스톤 번호를 붙이지 않는다
- M2 잔여(C1·C3·C5)와 M3는 전부 서버 작업이라 **충돌하지 않는다.** 병행 가능하다
- 다만 **다음 클라이언트 UX 작업이 들어오기 전에** 끝내는 편이 낫다. 미루면 파일은 더 커진다
- 라우트 가드 관련 동기 복원 규약(`main.tsx`의 렌더 전 `init()`/`loadFromStorage()`)은 이 파일과 무관하지만, 스토어 구독을 옮길 때 실수로 초기화 시점을 바꾸지 않도록 주의

---

## 9. 실행 결과 (2026-07-29)

### 산출물

| 파일 | 줄 | 성격 |
| --- | --- | --- |
| `GameTable.tsx` | 278 | 조립 루트 (원본 1030) |
| `TableArena.tsx` | 258 | 프레젠테이션 |
| `MyHandPanel.tsx` | 203 | 프레젠테이션 |
| `GameTableHeader.tsx` | 167 | 프레젠테이션 |
| `MatchEndedPanel.tsx` | 164 | 프레젠테이션 |
| `TurnCountdown.tsx` | 27 | 프레젠테이션 |
| `useGameTableModel.ts` | 198 | 훅 (**계획에 없던 추가분**) |
| `useGameTableEffects.ts` | 182 | 훅 |
| `useGameActions.ts` | 125 | 훅 |
| `gameTableSelection.ts` | 26 | 순수 함수 |
| `GameTable.test.tsx` | 287 | 특성화 테스트 (신규 11건) |

### §6 검증 결과

| 항목 | 기준 | 결과 |
| --- | --- | --- |
| 타입 | 에러 0 | ✅ |
| 단위 테스트 | 124 + 신규 | ✅ 135건 (124 + 11) |
| 빌드 | 성공 | ✅ |
| 사전 게이트 | `check:fast` 통과 | ✅ (단계마다) |
| 시각 무변경 | A6/A7과 픽셀 동일 | ✅ **DOM 덤프 비교로 대체·강화** (아래) |
| 크기 | 200줄 이하 | ❌ **278줄 — 기준 보정** (아래) |

### 계획 대비 차이 두 가지

**(1) `useGameTableModel` 추가.** 컴포넌트 4개를 빼고도 GameTable 이 405줄이라
스토어 구독 23개 + 파생값 + `phaseLabel`/`arenaTint` 를 세 번째 훅으로 분리했다.
사용자 승인 후 진행.

**(2) 200줄 기준 미달성 → 보정.** 최종 278줄. 내역은 import 22 / props 인터페이스 23
/ 훅 플러밍 45 / JSX prop 나열 161 로 **전부 조립 코드**다. 더 줄이려면 자식이
`useTichuStore` 를 직접 구독해야 하는데, 이는 §2 의 "프레젠테이션 컴포넌트에 렌더
테스트를 붙일 수 있는 상태" 목표와 정면으로 충돌한다. 기준을 **"조립 루트만 남을 것
— `useEffect`/`useRef`/핸들러/마크업 0"** 으로 보정하며, 이 기준은 충족한다.

### 시각 무변경 검증 방법

Playwright 시나리오는 레포에 커밋돼 있지 않아 그대로 재사용할 수 없었다. 대신 더
객관적인 방법을 썼다 — 리팩토링 직전 커밋(`c46db7a`)을 git worktree 로 띄우고,
**17개 시나리오**(로딩/딜링 8·14·선언·준비/패스/제출완료/플레이 빈트릭·트릭있음·
끊김/관전/라운드종료/매치종료 호스트·게스트/에러/패스받음/드래곤양도/마작소원)의
렌더 DOM 을 전후 양쪽에서 덤프해 비교했다.

`class` 속성 **내부 공백 개수**만 정규화하면(HTML 이 공백을 클래스 토큰 구분자로만
취급하므로 렌더 영향 없음) **바이트 단위로 완전 일치**했다. 이 공백 차이는 좌석
`className` 템플릿 리터럴이 컴포넌트로 옮겨가며 들여쓰기 폭이 줄어든 결과다.

CSS 는 브랜치 전체에서 **0건** 변경됐다.

### 커밋 순서

`c46db7a` D-87+문서 → `f833316` 특성화 테스트 → `fadfdaa` 순수함수 →
`d88d922` useGameActions → `a4995e1` useGameTableEffects → `d11ed35` MatchEndedPanel →
`dab5b2e` MyHandPanel → `ec0035b` GameTableHeader → `988ea13` TableArena →
`15ee4dc` useGameTableModel

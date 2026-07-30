# 멀티게임 — 세션 분할 실행 계획

> 작성 2026-07-30 · 설계 근거: `docs/game-port.md`(D-97) · `docs/plans/multi-game.md`
> 각 세션은 **독립적으로 시작 가능**하도록 썼다. 이 파일과 §의 "읽을 것"만 열면 된다.

## 사용법

1. 새 세션을 열고 `/clear`
2. 아래 세션 블록 하나를 골라 **"시작 프롬프트"를 그대로 붙여넣기**
3. 세션 끝에 Phase Gate(변경 요약 + 다음 진입 동의)

**순서 의존을 무시하지 마라.** S2 는 S1 의 포트가 있어야 하고, S5 는 S2 의 인원 가변이
있어야 스컬킹 4인 방을 만들 수 있다(없으면 항상 8인 방이 된다). 각 블록의 "선행" 참조.

## 전체 지도

| 세션 | 범위 | 선행 | D 번호 | 코드 변경 | 병렬 |
| --- | --- | --- | --- | --- | --- |
| ~~S0~~ | 포트 설계 | — | D-97 | 0 (문서) | ✅ **완료** |
| **S1** | 포트 추출 (티츄를 포트 뒤로) | S0 | D-98 | 서버 | ❌ 단독 |
| **S2** | 인원 가변 (계약 변경) | S1 | D-99 | 서버+클라+docs | ❌ 단독 |
| **S3** | 스컬킹 룰 명세 | — | D-100 | 0 (문서) | ✅ 병행 가능 |
| **S4** | 스컬킹 도메인 (카드·트릭·입찰·점수) | S1·S3 | D-101 | 서버(신규 패키지) | ✅ 병행 가능 |
| **S5** | 스컬킹 통합 (엔진·봇·디스패치) | S2·S4 | D-102 | 서버 | ❌ 단독 |
| **S6** | 스컬킹 클라 | S5 | D-103 | 클라 | ❌ 클라 1트랙 |

**병렬 가능 조합**: S3 는 언제든(문서 전용). S4 는 신규 패키지라 S2 와 병행 가능.
그 외는 공유 파일(`GameStompController`·`RoomService`·`styles/parts`)이 겹쳐 단독.

## 공통 규약 (모든 세션)

- 착수 전 `docs/decisions.md` 에 **D-NN 스텁 선행 커밋** (CLAUDE.md Phase Gate)
- 작업 브랜치 사용, 단계별 독립 커밋
- 검증: `./scripts/check.sh fast`(커밋 게이트) → 서버 작업은 `check.sh server`
- 계약 변경은 **서버 DTO + 클라 미러 + docs 를 한 커밋**으로 (parallel-tracks 직렬화 4)
- **티츄 룰을 손대지 않는다** — 룰 변경이 섞이면 회귀 원인 분리가 불가능해진다

---

## S1 — 포트 추출 (티츄를 포트 뒤로)

**선행**: 없음(S0 완료). **D-98**. **단독 세션**(공유 파일 다수).

### 시작 프롬프트
```
docs/game-port.md 를 읽고 S1(포트 추출)을 진행해줘.
docs/plans/multi-game-sessions.md 의 S1 블록이 범위다.
티츄 룰은 손대지 말고, 동작 무변경 리팩토링으로.
```

### 범위
`docs/game-port.md` §1 의 인터페이스를 실제로 만들고 티츄를 그 뒤로 옮긴다.

- `GameEngine`·`GameAction`·`GameEvent` 빈 마커 → 실제 표면
- `GameDefinition.newEngine()` 을 **실제 디스패치에 사용**(현재 호출부 0건)
- `GameStompController` 의 `@Payload TichuAction` 하드타입 제거 → 게임별 액션 역직렬화
- `MatchProgressService`·`BotScheduler`·`TurnTimeoutScheduler` 를 포트 뒤로
- 매치 종료 판정을 인프라 → 엔진으로 이동

### 하지 않을 것
- 티츄 룰 변경 · 새 게임 추가 · 인원 가변(S2) · 팀/칩/ELO 일반화

### 검증
서버 391건 전량 그린. 특히 `BotMatchSimulationIT`·`TurnTimeoutSchedulerIT`·
`TichuInvariantChecker`. 라이브 봇 솔로 풀매치 1판.

### 완료 기준
`infra` 에서 `Tichu` 직접 참조가 **0에 수렴**(현재 10파일 123회). 남으면 그 이유를 기록.

---

## S2 — 인원 가변 (계약 변경)

**선행**: S1. **D-99**. **단독 세션**(`RoomService` + 클라 모달 + docs).

### 시작 프롬프트
```
docs/game-port.md §3 을 읽고 S2(인원 가변)를 진행해줘.
docs/plans/multi-game-sessions.md 의 S2 블록이 범위다.
계약 변경이니 서버 DTO + 클라 미러 + docs 를 한 커밋으로 묶어줘.
```

### 왜 필요한가
스컬킹이 **2~8인**이라 `capacity = def.maxPlayers()` 고정이면 항상 8인 방이 된다.

### 범위
- `POST /api/rooms` 에 `capacity` 선택 필드 (미지정 시 `maxPlayers()` — 현행 호환)
- 검증 `minPlayers() <= capacity <= maxPlayers()`, 위반 시 `INVALID_CAPACITY`
- `RoomService.createRoom` / `fillWithBots` 좌석 계산이 capacity 를 따르도록
- 클라 방 만들기 모달에 인원 선택 (가변 게임일 때만 노출)
- `docs/api.md` 갱신

### 검증
티츄(min=max=4)는 **UI·동작 무변경**이어야 한다 — 기존 방 생성 IT 전량 그린이 그 증거.
가변 게임은 스컬킹이 아직 없으므로 테스트용 fake `GameDefinition` 으로 검증.

---

## S3 — 스컬킹 룰 명세 (문서 전용)

**선행**: 없음. **D-100**. **언제든 병행 가능**(코드 0).

### 시작 프롬프트
```
docs/plans/multi-game-sessions.md 의 S3 를 진행해줘.
볼트 inbox/clippings/스컬킹.md 를 근거로 docs/rules-skullking.md 를 써줘.
docs/rules-tichu.md(D-56) 와 같은 형식으로.
```

### 왜 먼저 쓰는가
D-56 의 `rules-tichu.md` 가 "룰 코드 1:1 매핑의 단일 진실원"으로 잘 작동한 선례.
구현 중 룰 해석을 놓고 헤매는 비용이 문서 쓰는 비용보다 크다.

### 반드시 담을 것
- **인원 2~8** (BGG 추천 4~6), 10라운드, 라운드 N = N장 배분
- 입찰(예측) 단계와 점수 계산 (적중/실패, 0 예측 특례)
- 특수 카드 **비추이적 삼각관계**: 해적 > 색상 · 스컬킹 > 해적 · 인어 > 스컬킹
- 티그리스(해적/탈출 **선언 선택**), 동일 카드 복수 시 "먼저 낸 사람" 판정
- 리드 수트 규칙(캐릭터 카드로 리드 시 리드 수트 없음)
- 보너스 점수(색상 14, 검정 14, 해적으로 잡은 인어 등)
- **범위 밖 명시**: 상급자 룰(약탈·크라켄·흰고래)은 기본 룰 안정화 후

---

## S4 — 스컬킹 도메인 (신규 패키지)

**선행**: S1(포트) · S3(명세). **D-101**. **S2 와 병행 가능**(신규 패키지라 충돌면 없음).

### 시작 프롬프트
```
docs/rules-skullking.md 와 docs/game-port.md 를 읽고 S4 를 진행해줘.
docs/plans/multi-game-sessions.md 의 S4 블록이 범위다.
domain/game/skullking 신규 패키지에 룰만 구현하고, 통합(S5)은 하지 마.
```

### 범위 (티츄와 같은 순서 — Phase 3 선례)
1. `card/` — 카드 모델 + 덱 (색상 4종 + 특수 카드)
2. `trick/` — 트릭 승자 판정 (**비추이적 삼각관계가 최대 난관**)
3. `bid/` — 입찰 단계
4. `scoring/` — 예측 적중 기반 점수 + 보너스
5. `SkullKingEngine` — 포트 구현

### 검증
티츄와 같은 밀도로 단위 테스트. 특히 트릭 판정은 **양성/음성 각 ≥3**, 삼각관계 전 조합.
`TichuInvariantChecker` 에 대응하는 `SkullKingInvariantChecker`(카드 보존·좌석 순서).

### 하지 않을 것
STOMP·봇·클라·디스패치 — 전부 S5.

---

## S5 — 스컬킹 통합

**선행**: S2(인원 가변) · S4(도메인). **D-102**. **단독 세션**.

### 시작 프롬프트
```
docs/plans/multi-game-sessions.md 의 S5 를 진행해줘.
S4 의 SkullKingEngine 을 GameDefinition 으로 등록하고 인게임 디스패치에 연결해줘.
```

### 범위
- `SkullKingGameDefinition` `@Component` 등록 (id `SKULL_KING`, min 2 / max 8)
- 액션 역직렬화 seam 에 스컬킹 액션 등록
- 봇 정책(`LegalActionEnumerator` 대응) + 타임아웃 안전 액션
- 상태 저장/뷰 매퍼
- 4인·6인 봇 풀매치 시뮬레이션 IT

### 완료 기준
**봇만으로 스컬킹 10라운드 완주**가 IT 로 통과. 티츄 회귀 전량 그린.

---

## S6 — 스컬킹 클라

**선행**: S5. **D-103**. **클라는 언제나 동시 1트랙**(parallel-tracks 직렬화 5).

### 시작 프롬프트
```
docs/plans/multi-game-sessions.md 의 S6 를 진행해줘.
스컬킹 게임판 UI 를 만들어줘. 티츄 컴포넌트를 재사용하되 억지로 공유하진 말고.
```

### 범위
- `features/skullking/` — 게임판·입찰 UI·트릭 표시
- 좌석 배치가 **2~8 가변**이라 티츄의 4좌석 고정 기하를 그대로 못 쓴다 → 이게 최대 난관
- CSS 는 `styles/parts/` 에 신규 파일 + `index.css` 에 @import 추가
  (⚠ 폭 미디어는 `17-responsive.css` 에만 — D-94)

### 주의
- `tichuStore` 는 **미분리 상태**(T5 2차 이월). 스컬킹 스토어는 별도로 만들고
  티츄 스토어를 건드리지 않는다.
- i18n 은 하드코딩으로 시작해도 된다(D-94 결론: 대량 이관은 별건).

---

## 이월 / 별건

| 항목 | 상태 |
| --- | --- |
| 요트 | 포트 검증용 3번째 게임. 스컬킹 완료 후 재평가 |
| 할리갈리 | **실시간 판정 모델 결정 선행** — 별도 마일스톤 (`multi-game.md` §2) |
| T5 2차 (styles.css 조각 병합 · tichuStore 분리) | D-95 예약 |
| T2 (M2-C3 Sentry+Grafana) | D-91 예약 |

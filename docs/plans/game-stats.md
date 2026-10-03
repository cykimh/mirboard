# 게임별 전적·레이팅 (M6-1, D-115)

> 마일스톤 M6 "스컬킹 완성도"의 1단계. 결정 근거는 `docs/decisions.md` D-115.
> M6 나머지(스컬킹 라운드 점수표 · 봇 휴리스틱 · 튜토리얼)는 이 단계 승인 후 별도 계획.

## 목표

스컬킹 매치도 티츄처럼 **기록·전적·ELO·랭킹**에 반영한다. 지금은 `users.rating` 등
단일 컬럼이라 두 번째 게임을 넣을 자리가 없어 D-102 에서 보류됐다(보류 ①).

## 범위

| # | 작업 | 위치 |
|---|---|---|
| 1 | `V11__user_game_stats.sql` — 게임별 전적 테이블 + 티츄 기존 값 이관 + 스컬킹 매치 테이블 2개 | `db/migration` |
| 2 | `UserGameStats` 엔티티·리포지토리(`domain.game.scoring`) | 서버 |
| 3 | `EloCalculator.applyFreeForAll` — 개인전 쌍대(pairwise) ELO | 서버 (순수 함수, 단위 테스트) |
| 4 | 티츄 `MatchResultRecorder` 를 새 테이블로 전환 (`users` 레이팅 컬럼 쓰기 중단) | 서버 |
| 5 | `SkullKingMatchCompleted` 이벤트 + `SkullKingMatchRecorder` | 서버 |
| 6 | API: `GET /api/users/ranking?gameType=` · `GET /api/users/{id}/stats` 에 `games[]` · `/api/me` 승패 = 전 게임 합 | 서버 + `docs/api.md` |
| 7 | 클라: 랭킹 게임 탭(카탈로그에서 목록 — 하드코딩 없음) · 프로필 게임별 전적 · 헤더 티어 | 클라 |

## 설계 요점

- **테이블**: `user_game_stats(user_id, game_type, rating, win_count, lose_count, desert_count, updated_at)`,
  PK `(user_id, game_type)`. 행이 없으면 "아직 안 해 본 게임" = 기본값(1000/0/0/0)으로 읽는다.
  게임 활동 집계라 식별·연락 정보가 아니고, `users` 화이트리스트(D-02)는 **건드리지 않는다**.
- **이관**: V11 이 `users` 의 rating/win/lose/desert 를 `TICHU` 행으로 복사한다(활동 있는
  사람만). `users` 쪽 컬럼은 **이번엔 남겨 두고 쓰기만 멈춘다** — 운영 DB 에서 이관이 맞는지
  확인한 뒤 별도 마이그레이션으로 DROP 한다(되돌리기 어려운 변경을 한 번에 하지 않는다).
- **개인전 ELO**: 각 쌍 (i, j) 를 한 판으로 보고 `S_ij`(점수 높으면 1, 같으면 0.5, 낮으면 0),
  `E_ij = 1/(1+10^((R_j−R_i)/400))`, `Δ_i = round(K_i/(n−1) · Σ_j (S_ij − E_ij))`.
  K 는 티츄와 같은 규칙(30판 미만 40, 이후 32). 2인이면 일반 ELO 와 같다.
  탈주 좌석은 점수와 무관하게 **최하위**(탈주끼리는 동률).
- **승패**: `winners`(공동 승리 가능) = 승, 나머지 = 패. 탈주자는 패 + `desert_count+1`.
- **봇**: 봇이 낀 매치는 승패만 기록하고 ELO 는 제외(D-71 과 같음). 봇 계정 자신은 기록 안 함.
- **이벤트 발행 지점**: `SkullKingGameEngine` 이 매치 종료를 판정하는 두 경로(`advance` 의
  10라운드 완주, `desert` 의 조기 종료) 모두에서 한 번만 발행한다.
- **랭킹 API 기본값**: `gameType` 생략 시 `TICHU` — 기존 클라·문서 호환.

## 검증

- `EloCalculatorTest` — 개인전: 2인=일반 ELO, 동점 0.5, 탈주 최하위, 합계 보존(같은 K 일 때 Σ≈0)
- `UserGameStatsIT`(신규) — V11 이관: 활동 있던 유저의 TICHU 행 생성, 봇 제외
- `MatchResultRecorderIT` — 티츄가 새 테이블에 쓰고 `users` 는 그대로
- `SkullKingMatchRecorderIT`(신규) — 사람끼리 매치 ELO 반영, 봇 매치 ELO 제외, 탈주자 패+desert
- `SkullKingBotMatchSimulationIT` — 봇 풀매치가 기록 경로를 지나도 정상 종료
- 클라: 랭킹 탭 전환, 프로필 게임별 전적 렌더
- 마지막에 CI 재현 환경(깨끗한 clone, compose 없음)에서 서버 전량

## 배포 주의

CD 가 연결되면 main push = 운영 배포 = 운영 DB 에 V11 적용이다. 이 브랜치는 사용자 검토 후
병합하고, 병합 직전에 운영 DB 볼륨 스냅샷을 새로 뜬다.

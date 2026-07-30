# 병렬 작업 트랙 분리 계획

> 작성 2026-07-30 · 근거: 코드 결합도 실측(서버 도메인 / 클라 피처 / 계약 / 로드맵 4축)
> 상태: **제안 — 사용자 승인 대기**

## 1. 결론: 레이어 3분할("공통 / 게임 로직 / UI·UX")은 이 레포에서 안 된다

세 가지가 실측으로 확인됐다.

**(1) 클라에서 게임 로직과 UI는 물리적으로 안 갈라진다.**
`client/src/features/tichu/tichuStore.ts`(539줄)에 서버 이벤트 리듀서(`applyEvent` 19케이스)와
손패 선택·정렬·패스슬롯 같은 순수 UI 상태가 같이 산다. `client/src/styles.css`(1522줄)는
게임판 기하 전체를 담은 단일 전역 시트다. "게임 로직 세션"과 "UI 세션"을 동시에 돌리면
이 두 파일에서 100% 충돌한다. **분리는 트랙 경계가 아니라 선행 작업이다**(→ T5).

**(2) "공통" 트랙은 정의상 모든 트랙의 대기열이 된다.**
`application.yml` · `SecurityConfig` · `WebSocketConfig` · `RedisConfig` ·
`GlobalExceptionHandler` · `decisions.md` · `mvp-roadmap.md` 는 어느 작업이든 마지막에
건드린다. 별도 트랙으로 세우면 나머지가 항상 대기하거나, 대기하지 않고 각자 고쳐서
충돌한다. **공통은 트랙이 아니라 락(직렬화 지점)으로 다뤄야 한다.**

**(3) 남은 백로그가 레이어 축과 매핑되지 않는다.**
M2 잔여(C1·C3·C5·채팅신고)·M3·M4는 거의 전부 infra 횡단 작업이고, 순수 게임 룰
변경은 사실상 0이다. 게임 로직 트랙은 지금 할 일이 없고 공통 트랙이 90%를 먹는다.

**대안 절단면: 레이어가 아니라 "마일스톤 단위 수직 슬라이스".**
각 트랙이 서버 + 클라 + docs 를 세로로 소유하고, 공유 파일은 소유자를 1명으로 못 박거나
직렬화한다.

## 2. 트랙

| 트랙 | 로드맵 | 병렬 | 충돌 대상 |
| --- | --- | --- | --- |
| **T0** 계약 문서 기준선 정정 | (선행) | ❌ 단독 | 사실상 전부 |
| **T1** 엣지 하드닝 | M2-C1 레이트리밋 | ✅ | T2·T4·T6 (배선 파일만) |
| **T2** 관측 스택 | M2-C3 Sentry+Grafana | ✅ | T1·T3·T5·T6 |
| **T3** 운영 런북·부하 | M2-C5 백업+k6 | ✅ | T2·T8 |
| **T4** 채팅 신고 수직 슬라이스 | M2 D-86 후속 | ✅ | T0·T1·T5 |
| **T5** 클라 UX 부채 | styles.css/tichuStore 분해 + i18n | ✅ | T2·T4·T7·T8 |
| **T6** 수평확장 | M3 | ❌ 단독 | T1·T2·T7 |
| **T7** 멀티게임 포트화 | M5 | ❌ 단독 | T4·T5·T6 |
| **T8** 쇼케이스 마감 | M4 | ❌ 단독 | T2·T3·T6 |

**순서(협상 불가)**: T0 → M2 4종(T1·T2·T3·T4) → T6(M3) → T8(M4)/T7(M5).
M3는 `TurnTimeoutScheduler`/`BotScheduler` 를 분산 재작성하고 M5는 같은 파일을
포트화한다 — 전면 재작성 2건이 같은 파일에 겹치므로 M3 선행. M4는 서사의 핵심이
M3의 멀티 인스턴스 증명이라 그 전에 쓰면 재작성.

### T0을 먼저, 단독으로 해야 하는 이유

CLAUDE.md가 `docs/*.md` 를 계약 정본으로 못 박았는데 실제로는 드리프트가 있다.

- 문서에만 있고 서버에 없는 이벤트 5종: `GAME_STARTED` `HAND_DEALT_COUNT`
  `WISH_FULFILLED` `BOMB_USED` `GAME_ENDED`
- 서버에만 있고 문서에 없는 5종: `REACTION` `ROOM_META_UPDATED` `ROOM_DESTROYED`
  `PLAYER_FINISHED` `CARDS_RECEIVED`
- payload 식별자 `userId` ↔ `seat` 불일치, 판별자(`@action`/`@event`) 미기재,
  누락 토픽(`/topic/room/{id}/meta`, `/reaction`), `RESYNC` 가 REST 전용인 점 미반영

기준선이 틀린 상태로 병렬화하면 각 트랙이 서로 다른(둘 다 틀린) 정본을 갖고
문서 충돌의 시비를 가릴 수 없다. 코드 변경 0의 문서 작업이라 짧다.

## 3. 직렬화 지점 (병렬 금지)

1. **`docs/decisions.md` D-NN 추가** — 최신순 삽입(상단 ~101행)이라 동시 삽입 시 같은
   위치 충돌 + 번호 겹침. → **번호 사전 예약 + 스텁 커밋**으로 직렬화.
2. **공유 설정 4종** `application.yml`(`mirboard.*` 말단 append) · `SecurityConfig`
   (단일 `securityFilterChain` 메서드) · `WebSocketConfig`(인터셉터 체인) ·
   `RedisConfig`(RedisScript 빈) + `GlobalExceptionHandler`. → 각 트랙은 본체를 먼저
   완성하고 **배선 커밋만 순서대로**.
3. **Flyway 신규 마이그레이션 + JPA 엔티티** — 버전 번호 단일 네임스페이스, `ddl-auto:
   validate` 라 엔티티와 같은 커밋 강제. → 번호 사전 배정(현재 V8 소진 → T4=V9).
4. **계약 변경은 서버 DTO + 클라 미러 + docs 를 한 커밋에** — OpenAPI/코드생성기가
   없어 100% 수동 미러다. `check:fast`(tsc + vitest + 서버 compile)는 교차검증을 못 하므로
   서버 필드명을 바꾸고 클라를 안 고쳐도 컴파일은 통과하고 런타임에 조용히 깨진다.
   (이미 `chips` 가 서버 `Map<Long,Long>` ↔ 클라 `Record<number,number>` 로 타입 수준
   불일치 상태.)
5. **`styles.css` · `tichuStore.ts` 편집** → **클라는 언제나 동시 1트랙**.
6. **D-82 테이블 칩/판돈** — 클라 13파일 · 서버 4파일을 관통하는 cross-cut. 단독 직렬.
7. **서버 통합테스트 실행** — Testcontainers + docker compose postgres/redis 가 한 벌뿐.
   worktree 로 나눠도 동시 실행 불가(소스 충돌이 아니라 실행 환경 충돌). → 동시 진행 시
   한쪽은 `check.sh rules`(Docker 불필요) / `check.sh client` 로만 회귀, IT는 교대.
8. **`mvp-roadmap.md` 표 + `implementation-status.md`** — M2 한 줄에 4개 작업 상태가 섞임.
   → 트랙 진행 중 손대지 말고 **Phase Gate 승인 직전 통합자 1인이 한 번에**.

## 4. 세션 분리 vs 서브에이전트

- **기본값은 세션 분리(직렬) + `/clear`.** 이 레포는 이미 Phase Gate(마일스톤마다 사용자
  승인)를 쓰므로 트랙 하나 끝내고 승인받고 넘어가는 리듬이 규약과 정확히 맞는다.
  병렬화의 진짜 비용은 CPU가 아니라 컨텍스트 오염이다.
- **서브에이전트는 읽기 전용으로만.** 같은 워킹트리를 공유하므로 동시 편집은 즉시 손상되고
  git이 중간 상태를 못 잡는다. 반면 (a) 영향도 스캔, (b) 계약 드리프트 대조(서버 record
  vs `client/src/types/*.ts` vs docs), (c) `check.sh` 결과 수집 같은 읽기 작업은 확실히 이득
  — 코드생성기가 없어 수동 미러 대조가 상시 필요하다.
- **worktree 격리는 "서버 장기 트랙 1 + 클라 트랙 1" 조합에만.**(T6 + T5) 그 외는
  브랜치 + 세션 분리로 충분하다. worktree 는 파일만 격리하고 postgres/redis·5173 포트·
  gradle 데몬은 여전히 공유다.
- **동시 트랙 최대 2개.** 그 이상은 공유 config 4종 + docs 3종 병합 비용이 이득을 넘는다.

## 5. 운영 수칙 (기존 규약만 사용, 새 도구 없음)

1. 트랙 착수 전 `decisions.md` 에 **D 번호 사전 예약 + 제목만 있는 스텁을 main 에 먼저
   커밋**(예: T1=D-88, T2=D-89, T4=D-90, T6=D-91). 본문은 트랙 브랜치에서 채운다.
2. 트랙 전환 시 반드시 `/clear`, 트랙 종료 시 Phase Gate(변경 요약 + 진입 동의).
3. 트랙별 검증 명령 고정 — 서버 룰 `check.sh rules`(Docker 불필요, ~3s) / 클라
   `check.sh client` / 서버 인프라 `check.sh server` / 통합 직전 `check.sh all`.
   커밋 게이트는 기존 pre-commit(`check.sh fast`) 그대로.
4. 계약이 걸린 변경은 한 커밋으로 묶고 **어떤 계약(rest-api / tichu-events / room-meta /
   table-chips)을 건드렸는지 커밋 메시지에 명시**. 자동 게이트가 없는 자리를 커밋 단위로 메운다.
5. 공유 설정 4종 + `GlobalExceptionHandler` 는 트랙 중 건드리지 않고 **말미 배선 커밋 1개**로
   main 에 리베이스하며 넣는다. 충돌 창을 며칠 → 몇 분으로 줄인다.
6. **클라 작업은 언제나 동시 1트랙.** 두 번째 클라 작업이 필요해지면 그건 T5를 먼저 하라는
   신호다.

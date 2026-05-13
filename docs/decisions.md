# 설계 결정 이력 (Decision Log)

본 문서는 Mirboard 의 주요 설계 결정을 시간 순으로 남기는 ADR-lite 로그다.
**중요한 설계 변경/번복은 코드 작업 전에 본 문서에 항목을 추가**하고, 같이 변경되는
`docs/*.md` / `CLAUDE.md` / `README.md` 와의 정합성을 사용자 승인 단계에서 검토한다.

## 작성 규칙

- 항목 ID: `D-YYYYMMDD-NN` (같은 날 여러 건이면 NN 증가).
- 폐기된 결정은 **삭제하지 않는다**. `상태: 폐기됨 → [후속 ID]` 마커를 추가.
- 형식:
  ```
  ## D-YYYYMMDD-NN  제목
  **상태**: 채택됨 | 폐기됨 → [후속 ID] | 수정됨 → [후속 ID]
  **결정**: 한 문장 요약.
  **이유**: 왜 이 선택을 했는지 (대안 비교 또는 제약).
  **영향**: 변경/생성된 파일, 후속 작업.
  ```

---

## D-20260513-01  Modular Monolith + Server-Authoritative + State Hiding 채택
**상태**: 채택됨
**결정**: 단일 Spring Boot 서버 안에서 도메인 패키지로 경계를 나누고, 모든 룰 연산을
서버에서 처리하며, 본인 손패는 STOMP 사용자 큐로만 전송한다.
**이유**: MVP 범위에 마이크로서비스는 과잉. 클라이언트 신뢰 시 치팅 위험. 보드게임
특성상 정보 비대칭이 핵심 메커니즘.
**영향**: `quiet-strolling-platypus.md` 플랜 본문, `docs/api.md`,
`docs/stomp-protocol.md`, `CLAUDE.md` 의 "절대 원칙" 절.

## D-20260513-02  사용자 식별 정보 최소화 (스키마 레벨 강제)
**상태**: 채택됨
**결정**: `users` 테이블에는 `id`, `username`, `password_hash`, `win_count`,
`lose_count`, `created_at` 만 둔다. `email`, `phone`, `real_name`, `birth_date`,
`address` 컬럼 추가를 **금지**한다.
**이유**: 개인정보보호 노출 면적 최소화. MVP에서 이메일 검증/SMS 인증 등 기능 불필요.
**영향**: `server/src/main/resources/db/migration/V1__init.sql` 주석으로 못박음.
`docs/api.md` 의 회원가입/로그인은 username/password 외 입력을 받지 않음.

## D-20260513-03  Redis Lua 스크립트로 방 입장 원자화
**상태**: 채택됨
**결정**: `room:{id}` 의 capacity 체크 → `players` push → 메타 갱신을 한 Lua
스크립트(`room_join.lua`) 안에서 처리한다.
**이유**: 4명 동시 입장 시 capacity 위반(5명 입장) 가능성을 0으로. 분산 락 대신 단일
인스턴스 Redis의 단일 스레드 원자성을 활용.
**영향**: `docs/redis-keys.md` 의 "원자성 보증" 절. Phase 2 검증 항목.

## D-20260513-04  Phase Gate 작업 흐름
**상태**: 채택됨
**결정**: 작업을 Phase 1 (설계) → Phase 2 (로비) → Phase 3 (티츄 룰) → Phase 4
(통합) 로 분할하고, 각 Phase 종료 시 사용자 검토/승인을 받아야 다음 Phase로
진입한다.
**이유**: 설계가 빠르게 변하는 초기 단계에서 한꺼번에 코드를 짜면 재작업 비용이
높음. 각 단계 산출물의 contract 가 확정된 뒤 다음 단계 시작.
**영향**: `CLAUDE.md` 의 "Phase Gate 작업 규칙" 절.

## D-20260513-05  Backend = Java 25 + Spring Boot 4.0.1 (초기 Java 17 / SB 3.x 폐기)
**상태**: 채택됨
**결정**: JDK는 Java 25 (LTS), Spring Boot 4.0.1 (Jakarta EE 11 / Spring
Framework 7) 을 사용한다. `javax.*` import 금지.
**이유**: 사용자 요청. Java 21+ 의 sealed types / 패턴 매칭 / 가상 스레드를
표준 코드 스타일로 활용 가능. SB 4.0 의 Jakarta 전환 완료로 javax 부담 없음.
**영향**: 플랜 기술 스택 표, `README.md` 의 "런타임 요구사항", `CLAUDE.md` 의 "기술
스택 결정". Phase 2 빌드 파일 생성 시 적용.

## D-20260513-06  단일 게임 가정 폐기 → 플랫폼 카탈로그 (Game Hub)
**상태**: 채택됨 (D-20260513-01 의 범위 확장, 폐기 아님)
**결정**: 로그인 직후 `Game Hub` 화면에서 게임을 선택한 뒤 해당 게임 전용 Lobby로
이동한다. 카탈로그의 진실 공급원은 `domain.game.core.GameRegistry` 와
`GameDefinition` Bean 집합. 새 게임은 `domain.game.{newgame}` 패키지 + Bean
등록만으로 자동 노출되어야 한다.
**이유**: 사용자 요청. 미래의 게임 추가를 위해 로비/허브 코드를 재작성하지 않도록
모듈러 모놀리스의 확장성을 형식화.
**영향**: 플랜에 `## 0. 사용자 플로우`, `## 2.1 GameRegistry / GameDefinition
패턴` 절 추가. `docs/api.md` 에 `GET /api/games`, `GET /api/games/{gameId}` 신설.
`/api/rooms` 의 `gameType` 필터를 권장 항목으로 격상. `CLAUDE.md` 의 "도메인
경계" / "사용자 플로우" 절.

## D-20260513-07  설계 이력 로그 도입
**상태**: 채택됨
**결정**: 본 `docs/decisions.md` 파일과 `CLAUDE.md` 의 "Plan / Memory / 이력관리
워크플로우" 절을 도입하여 결정/번복을 추적한다.
**이유**: 초기 설계 단계에서 결정이 빠르게 갱신됨 (D-05, D-06). 코드/문서가 흩어져
있어 "왜 이렇게 결정했는가" 가 휘발될 위험. 미래의 Claude 세션과 사용자 본인의
재방문을 위해 단일 진실 공급원이 필요.
**영향**: `docs/decisions.md` 신설, `CLAUDE.md` 의 워크플로우 절 추가,
`README.md` 의 결정 로그 링크 추가.

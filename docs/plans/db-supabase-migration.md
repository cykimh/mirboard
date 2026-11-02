# 운영 DB 이전 — Fly Postgres → sideinfra Supabase (D-169)

## 왜

Fly.io 10월 청구 $6.84(처음으로 $5 초과). 항상 켜진 머신 두 대(`mirboard-db` 약 $2.2, `mirboard-redis` 약 $2)가 고정비다.
사용자 원칙은 "운영은 무료 범위". DB 를 이미 쓰는 sideinfra Supabase 무료 프로젝트로 옮겨 `mirboard-db` 를 없앤다(월 약 −$2.2).
Redis 를 앱에 합치는 건 별도 작업(이번 범위 아님).

## 현재 (2026-11-02 확인)

- Fly `mirboard-db`: postgres-flex 17.2, shared-cpu-1x 256MB, 볼륨 1GB. mirboard 데이터는 DB `postgres` 의 `public` 스키마, 약 8MB
  (users 11 · user_game_stats 12 · onecard 결과 15판 · 아바타 1 · flyway_schema_history V1~V12)
- 앱 연결은 시크릿 `MIRBOARD_DB_URL`·`MIRBOARD_DB_USER`·`MIRBOARD_DB_PASSWORD` 만. 엔티티·마이그레이션 SQL 은 스키마 이름을 쓰지 않는다
- Supabase `sideinfra`(ref `bonkcrwavdzqaedlktzi`, 서울, Postgres 17). 앱마다 스키마, DB 변경은 sideinfra `supabase/migrations` 에서만

## 목표 / 비목표

- 목표: 데이터 손실 0, 정지 10분 이내, 코드 동작 변화 0(로컬·테스트·CI 는 지금처럼 `public`), 되돌리기 쉬움
- 비목표: Redis 이전, Fly 앱 리전 변경, Supabase Auth·Data API 사용
- 실패로 볼 것: mirboard 테이블이 Data API(anon/authenticated)로 읽히는 것, 다른 앱 스키마·마이그레이션 이력을 건드리는 것

## 설계

### Supabase 쪽 (sideinfra 저장소, 마이그레이션 1개)

- 스키마 `mirboard`(소유 postgres, `mirboard_app` 에 usage·create) + 로그인 역할 `mirboard_app`(슈퍼유저 아님, 연결 15, search_path mirboard).
  **비밀번호는 마이그레이션에 넣지 않는다** — 이전 스크립트가 운영자 셸에서 만들어 SCRAM 해시만 Supabase 로, 평문은 Fly 시크릿으로만
- `infra.app_schemas` 에 등록 → 새 테이블에 RLS 가 자동으로 켜진다. `ensure_rls` 는 postgres 로 도는 security definer 라
  `grant mirboard_app to postgres` 가 있어야 남의 테이블을 바꿀 수 있다(리허설에서 발견). 테이블 소유자 `mirboard_app` 은 RLS 를 우회하므로
  앱은 영향 없음. anon·authenticated·service_role 에는 권한을 주지 않는다
- **Data API 에 노출하지 않는다** — `config.toml [api] schemas` 에 넣지 않음(템플릿의 grant·healthcheck 도 쓰지 않음)
- 테이블은 mirboard Flyway 가 관리한다 → sideinfra 문서에 "예외: mirboard 는 스키마·역할만 여기, 테이블은 앱 Flyway" 한 줄
- 작업은 sideinfra 별도 워크트리·브랜치에서(현재 체크아웃은 `feat/hub-admin` 진행 중이라 건드리지 않음)

### mirboard 쪽 (코드 변경 = 설정 한 줄씩)

- `application.yml`: `spring.flyway.schemas`·`default-schema`, `spring.jpa.properties.hibernate.default_schema` 를
  `${MIRBOARD_DB_SCHEMA:public}` 로. 기본값 `public` 이라 로컬·테스트·현재 운영은 그대로
- 운영 연결은 Supabase **세션 풀러**(IPv4, 5432, 세션 모드라 prepared statement 그대로): 사용자 `mirboard_app.<ref>`, `sslmode=require`.
  직접 연결 호스트는 IPv6 전용이라 쓰지 않는다. 풀러 기본 풀 15 > Hikari 최대 8
- `docs/decisions.md` D-169, `docs/deploy.md`(DB 절, 백업, 되돌리기), CLAUDE.md 현황 한 줄

### 데이터 이전 (정지 몇 분)

1. 사전: 이 코드 먼저 배포(동작 변화 없음). Supabase 마이그레이션 적용, 역할 비밀번호 설정, Fly 머신에서 풀러 접속 확인
2. 정지: 지금 이미지를 적어 두고 `flyctl scale count 0`(멈추기만 하면 접속이 올 때 옛 접속 정보로 다시 켜진다 — auto_start)
3. Fly DB `pg_dump -n public --no-owner --no-acl` → **임시 로컬 컨테이너**(개발용 `mirboard-postgres` 는 안 씀)에 복원 →
   `alter schema public rename to mirboard` → `pg_dump -n mirboard --no-owner --no-acl` → Supabase 에 `mirboard_app` 으로 복원
   (객체 소유자가 `mirboard_app`). 행 수 대조
4. 시크릿 교체(`MIRBOARD_DB_URL`·`USER`·`PASSWORD`·`MIRBOARD_DB_SCHEMA=mirboard`) → 같은 이미지로 `flyctl deploy --image … --ha=false`
   → Flyway validate 통과(V12, 새 적용 없음)
5. 확인: `/actuator/health` UP, 랭킹·전적 숫자가 이전과 같음, 게스트로 원카드 한 판 → 기록 저장(운영 로그인 금지 규칙 → 사용자가 직접 확인)

### 되돌리기 / 정리

- 1주일 동안 `mirboard-db` 를 그대로 둔다. 문제 시 시크릿을 옛 값으로 되돌리면 끝(그 사이 Supabase 에 쌓인 기록은 손으로 옮겨야 함)
- 1주일 뒤 사용자가 `flyctl apps destroy mirboard-db` (되돌릴 수 없음 — 직접 실행). 그 전에 마지막 덤프를 보관

## 알려진 비용·위험

- 서울(Supabase) ↔ 도쿄(Fly) 왕복 약 30ms/쿼리. 게임 진행은 Redis 라 체감 없음, 로그인·전적·랭킹·매치 기록만 약간 느려짐
- Supabase 무료: 자동 백업 없음(`sideinfra/scripts/backup.sh`), 7일 비활성 일시정지(다른 앱과 공유라 사실상 낮음), 500MB 전 앱 합산
- 대기실이 사람마다 `GET /api/users/{id}/stats` 를 부른다 → 왕복 늘어남. 체감되면 후속으로 묶기

## 리허설

본 이전 전에 같은 절차를 로컬 Supabase(OrbStack `supabase_*_sideinfra`, 포트 54322)에 한 번 돌린다: 마이그레이션 → 역할 → 덤프 복원 →
앱을 `MIRBOARD_DB_SCHEMA=mirboard` 로 띄워 Flyway validate·로그인·랭킹 확인.

## 리허설 결과 (2026-11-02)

- `MIRBOARD_DB_SCHEMA=mirboard` 로 DB 통합 테스트 22개 통과(Flyway 가 `mirboard` 에 V1~V12 적용, 네이티브 쿼리 포함)
- 운영 덤프(66KB) → 임시 컨테이너 → 로컬 Supabase 복원: 행 수 12개 테이블 전부 일치, RLS 12/12, anon·authenticated·service_role 접근 없음,
  시퀀스 값 유지, Flyway CLI `validate` 12개 통과
- 첫 시도는 `must be owner of table`(ensure_rls) 로 중간에 실패 → 마이그레이션에 `grant mirboard_app to postgres`, 복원은 `--single-transaction`
- Fly 앱 머신 → 풀러 5432 TCP 연결 확인. 원격 마이그레이션 적용 후 역할·권한 확인(로그인 가능, 연결 15, 외부 역할 권한 없음)
- 실행 절차는 `ops/db/migrate-to-supabase.sh`(운영자가 직접 — 비밀번호·접속 정보를 다룬다)

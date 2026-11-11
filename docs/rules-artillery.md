# 씨앗포 룰 명세 (Artillery Rules)

본 문서는 Mirboard 씨앗포 룰의 **단일 진실 공급원**이다(D-220). 코드(S1 순수 엔진)는 이 문서를 따르고, 어긋나면 코드를 고치거나
이 문서와 결정(`docs/decisions.md`)을 함께 고친다. 각 절 끝의 **코드:** / **테스트:** 는 S1 이후 채운다. 경로는
`server/src/{main,test}/java/com/mirboard/domain/game/artillery/` 기준이다. 클라는 판정하지 않으며 서버가 보낸 결과(궤적·구멍·피해)를 재생만 한다.

> **출처와 우리가 정한 것.** 장르(턴제 포격 — 각도·힘·바람·지형 파괴)만 따르고 이름·유닛·탄·맵·그림은 새로 정했다. 수치는 전부
> 우리가 정한 것이다. 설계 배경은 `docs/plans/artillery.md`.

---

## 1. 전장

- **격자** 640×360칸. 좌표 `(x, y)` — x 는 오른쪽, **y 는 아래로** 커진다. 칸 하나는 흙(`solid`)이거나 빈칸이다.
- 전장 밖: 왼쪽(x<0)·오른쪽(x≥640)·아래(y≥360)는 끝이다. **위(y<0)는 열린 하늘** — 빈칸으로 본다.
- **구름 수면** `cloudLevel`: 처음 360(전장 바로 아래). 발밑이 수면 이상(y ≥ cloudLevel)이면 구름 바다에 빠진 것이다(§6). 수면은 §8 에서 오른다.
- 흙은 떨어지지 않는다(떠 있는 섬). 지형은 폭발로만 바뀐다(v1 은 지우기만).

**좌표 정밀도.** 탄의 위치·속도는 칸 × 65536(Q16) 정수다. 칸 좌표는 `floor(q / 65536)`(산술 오른쪽 시프트 16). 계산 전체에 부동소수점이 없다.

## 2. 맵 생성 (결정적)

모든 무작위는 매치 시드(64비트) 하나에서 **SplitMix64**(§10)로 뽑는다. 같은 시드·같은 인원이면 같은 맵·같은 출발 자리다.

1. **섬 개수** K = 2 + (난수 mod 3) → 2~4개. 섬은 **좌우 대칭**으로 놓는다: 왼쪽 절반에 ⌊K/2⌋개를 만들고 x → 639 − x 로 비춘다. K 가 홀수면 가운데 섬 하나를 더한다(그 자체가 대칭).
2. **섬 하나** = 중심 `cx`, 반폭 `hw`(60~110), 윗면 기준 `top`(150~250), 두께 `depth`(40~80). 열 x(|x − cx| ≤ hw)마다:
   - 윗면 `t(x) = top − bump(x) + noise(x)` — `bump` 는 가운데가 최대 20칸 솟는 포물선, `noise` 는 진폭 ±8칸의 2옥타브 정수 값 노이즈.
   - 아랫면 `b(x) = top + depth × (1 − ((x − cx)/hw)²)` — 아래로 처진 그릇 모양.
   - `t(x) ≤ y ≤ b(x)` 인 칸을 흙으로 칠한다(섬끼리 겹치면 합집합).
3. **출발 자리** N개(N = 인원): 전장을 가로로 N등분해 띠마다 **띠 가운데에 가장 가까운 평평한 열**을 고른다. 평평함 = 그 열과 좌우 3칸의 윗면 높이 차가 2칸 이하, 윗면부터 아래로 흙이 12칸 이상 이어짐(얇은 섬 끝 판자 제외), x 는 16~623, 윗면 y 는 20~339. 발 위치는 그 열 윗면 바로 위(§5.1).
4. 어느 띠에도 자리가 없으면 시드를 SplitMix64 로 한 번 더 굴려 1부터 다시 만든다(최대 32번 — 그래도 없으면 마지막 맵에서 띠 제약 없이 가장 평평한 N열. 결정적).
5. 좌석 → 출발 자리 배정은 시드로 섞는다(좌석 0 이 늘 왼쪽 끝이 아니게). **첫 차례 좌석**도 시드로 고른다.

## 3. 포대

| 항목 | 값 |
| --- | --- |
| 체력 | 포대 종류의 최대 체력(아래 표) — 시작도 최대 |
| 위치 | 발 칸 `(x, footY)` — 정수 칸. 몸 중심은 `(x, footY − 5)` |
| 맞는 범위 | 몸 중심에서 반경 6칸 원 |
| 받침 | 발밑 줄(footY + 1)의 x − 2 ~ x + 2 중 흙이 하나라도 있으면 서 있다 |
| 포구 | 몸 중심에서 조준 각도 방향으로 10칸 |
| 이동 연료 | 차례마다 포대 종류의 연료로 다시 찬다 |

**포대 종류 (S4, D-224)** — 판 시작 고르기(§4.0)에서 고른다. 맞는 범위·몸 크기·포신은 셋 다 같다.

| 종류 | 체력 | 연료 | 바람 영향 | 고유 특수탄(판당 2발) |
| --- | --- | --- | --- | --- |
| `SPROUT` 새싹포 | 100 | 100 | 100% | `VINE` 덩굴탄 |
| `ACORN` 도토리포 | 120 | 70 | 60% | `CHESTNUT` 알밤탄 |
| `DANDELION` 민들레포 | 85 | 130 | 140% | `SPORE` 홀씨탄 |

- 바람 영향은 그 포대가 쏜 씨앗의 가로 가속에 곱한다: `ax = wind × 46 × 영향 / 100`(정수, 0 쪽으로 버림 — 좌우 대칭).
- 판마다 전원이 **호박탄 2발 · 고유 특수탄 2발 · 아이템 세 가지 1개씩**(§6.4)을 갖고 시작한다. S4 이전 상태(종류 없음)는 새싹포로 읽는다.

## 4. 한 차례

### 4.0 판 시작 — 포대 고르기 (`PICKING`, D-224)

- 판은 `PICKING` 단계로 시작한다. 살아 있는 **모두가 동시에** `PICK(unit)` 을 한 번 보낸다(기다리는 좌석 = 아직 안 고른 좌석 전부).
- 마감은 시작부터 **15초**(엔진 타이머 — 인프라 턴 데드라인은 고를 때마다 다시 걸려 쓰지 않는다). 마감까지 안 고른 좌석은 새싹포.
  인프라 시간 초과가 먼저 오면 그 좌석은 새싹포를 고른 것으로 한다.
- 고른 종류는 **다 고를 때까지 숨긴다** — 공개 뷰에는 "골랐다"만, 비공개 뷰에 내 선택만. 마지막 선택(또는 마감) 순간 `UNITS_REVEALED` 로 전원을
  공개하고 체력·특수탄·아이템을 채운 뒤 첫 차례(§9.1)를 연다.
- 봇은 시드로 고른다(`splitmix(seed, 7000 + 좌석) mod 3`). 고르는 중 탈주하면 탈락시키고, 남은 사람이 다 골랐으면 바로 공개한다.
- 고르기 중 `PICK` 밖의 액션·두 번째 `PICK` 은 `NOT_YOUR_TURN`.

### 4.1 차례

1. **차례 시작**: 바람을 새로 정하고(§7), 연료를 포대 종류만큼 채우고, 서든데스면 수면을 올린다(§8). 차례 시작 시각 `turnStartedAt` 을
   상태에 적는다(D-228 — 차례 전체 마감의 기준).
2. 차례 주인은 `MOVE` 를 **여러 번** 보낸 뒤 `FIRE`·`SKIP` 중 하나로 차례를 끝낸다(D-228 — 걸으면 바로 확정, 되돌리기 없음). `FIRE.moveDx` 도
   그대로 유효하다("이 자리에서 더 걷고 쏜다" — 봇·옛 클라는 `MOVE` 없이 `FIRE` 하나로 걷고 쏜다).

| 액션 | 내용 |
| --- | --- |
| `MOVE` | `dx`(−130~130 정수, 0 아님) — 그 자리에서 걷고(§5.1) 확정한다. 공개 `UNIT_MOVED`(순번 있음). 차례는 계속된다(걷다 탈락하면 끝, §5.1) |
| `FIRE` | `moveDx`(−130~130 정수, 0 = 안 움직임 — 실제 걸음은 **남은** 연료가 정한다) · `angle`(0~180 정수, 0 = 오른쪽, 90 = 위, 180 = 왼쪽) · `power`(0~100 정수) · `shell`(`PEA`·`PUMPKIN`·내 고유 특수탄) · `item`(없음 또는 남은 아이템 하나, §6.4) |
| `SKIP` | 차례 넘기기 |

3. `FIRE` 는 순서대로 해소한다: **(물뿌리개) → 이동(§5) → 탄도(§6.1) → 폭발(§6.2) → 낙하·탈락(§6.3)**, 겹씨앗이면 탄도~낙하를 한 번 더(§6.4). 해소가 끝나면 **연출 대기**(§9.2)에 들어가고, 대기가 끝나면 다음 차례.
   같은 차례에 `MOVE` 로 쓴 연료와 `FIRE.moveDx` 걸음은 포대 연료 하나를 나눠 쓴다 — 상태의 `moveFuelUsed`(이번 차례에 `MOVE` 로 쓴 연료)를 뺀 나머지로 걷는다.
4. 다음 차례는 **누적 딜레이가 가장 작은 살아 있는 포대**다(§4.2, D-226 — 그 전에는 좌석 +1).
5. 시간 초과는 `SKIP` 과 같다(이미 `MOVE` 로 걸은 것은 남는다 — 딜레이에 들어간다, §4.2). 턴 제한 '끔'은 없다 — 선택지 20·30·45·60초, 기본 30초(D-220).
   마감은 **차례 전체**다(D-228): 차례 시작 + 턴 제한에 엔진 타이머가 시간 초과를 낸다 — `MOVE` 를 해도 시간이 다시 차지 않는다(인프라 턴 데드라인은
   액션마다 다시 걸리므로 늘 더 늦은 폴백). 공개 뷰 `turnRemainingMs` 가 엔진이 센 남은 시간이다. `turnStartedAt` 이 없는 판(D-228 이전에 시작해 Redis
   에 남은 판)은 엔진 마감이 없다가 첫 `MOVE` 가 그 순간으로 채운다(늘어나는 시간은 많아야 한 번의 턴 제한).
6. 조준 미리보기(`/preview` 의 `PREVIEW`, D-229)는 액션이 아니다 — 상태를 바꾸지 않고 차례도 끝내지 않는다(§15).

**거절**(상태 무변경, 비공개 `ERROR`):

| 사유 | 경우 |
| --- | --- |
| `NOT_YOUR_TURN` | 내 차례가 아님(연출 대기 중 포함 — 대기 중에는 아무의 차례도 아니다) |
| `MATCH_OVER` | 끝난 판 |
| `OUT_OF_RANGE` | `moveDx`·`dx`·`angle`·`power` 가 범위 밖, `MOVE.dx` 가 0 |
| `NO_AMMO` | 그 탄이 남지 않았음(남의 특수탄 포함) |
| `NO_ITEM` | 그 아이템이 없음(이미 씀) |
| `CANNOT_MOVE` | `MOVE` 로 한 걸음도 못 감 — 남은 연료 0, 또는 첫 걸음이 벽·전장 끝(D-228 — 빈 `UNIT_MOVED` 로 순번을 쓰지 않으려고) |
| `INVALID_ACTION` | 미리보기 입력 `PREVIEW` 를 `/action` 으로 보냄, 고르기 밖의 `PICK` |

### 4.2 차례 순서 — 딜레이 (D-226)

포트리스식이다. 포대마다 **누적 딜레이**가 있고, 차례가 끝날 때 그 차례 주인에게 쌓인다. 다음 차례는 살아 있는 포대 중 딜레이가
가장 작은 포대 — 같으면 방금 차례였던 좌석 **다음부터 좌석 순서**(방금 쏜 좌석이 맨 뒤). 개인전·팀전 모두 같다.

| 한 차례 | 딜레이 |
| --- | --- |
| 콩알 / 호박탄 / 고유 특수탄 | 100 / 130 / 140 |
| 이동 | 쓴 연료 2칸마다 +1(나머지 버림 — 연료 = 포대 연료 − 남은 연료) |
| 아이템 | +30 (겹씨앗은 한 발이 더 나가 +60) |
| 넘기기·시간 초과 | 80 + 이번 차례 `MOVE` 로 쓴 연료의 이동 딜레이(2칸마다 +1, D-228 — 걸어 놓고 넘겨 딜레이를 아끼지 못하게). 걷지 않았으면 80 |

- 판 시작(고르기 공개 뒤): 시드로 고른 첫 좌석부터 좌석 순서로 0·1·2… 를 깐다 — 첫 바퀴는 좌석 순서와 같다(팀전이면 팀이 번갈아).
- 탈락한 포대는 순서에서 빠진다. 딜레이는 공개 정보다 — 공개 뷰에 포대별 `delay`, 앞으로의 순서 `queue`(지금 차례 주인부터),
  딜레이 표 `delays` 를 싣고 `TURN_CHANGED` 에도 `delays`·`queue` 를 싣는다.
- 상태의 `turnOrder` 가 `SEAT` 인 판(D-226 이전에 시작해 Redis 에 남은 판 — JSON 에 필드 없음)은 좌석 +1 로 끝까지 가고 딜레이를 쌓지 않는다.
- 구름 밀물(§8)은 여전히 **차례 번호**로 센다 — 차례 상한도 그대로다.

## 5. 이동

### 5.1 걷기

`MOVE.dx`·`FIRE.moveDx` 의 부호 방향으로 한 칸씩 걷는다(둘 다 같은 걷기 — 연료는 이번 차례에 남은 만큼). 한 걸음:

1. 다음 열 `nx = x ± 1`. nx 가 3~636 밖이면 멈춘다.
2. **오를 높이** k = 0, 1, 2, 3 중 가장 작은 값으로, 칸 `(nx, footY − k)` 부터 위로 8칸(몸 높이)이 모두 빈칸인 k 를 찾는다. 없으면 벽 — 멈춘다.
3. 연료 비용 = 1 + k. 연료가 모자라면 멈춘다.
4. `(nx, footY − k)` 로 옮긴다. 받침이 없으면 **내리막**으로 본다 — 3칸까지는 받침이 생기는 곳까지 걸어 내려간다(낙하 아님, 추가 연료 없음).
5. 그래도 받침이 없으면 **떨어진다**(§6.3 의 낙하와 같음 — 피해·탈락 포함) 그리고 걷기를 멈춘다. 걸어서 발이 구름 수면 이상이 되어도 구름 바다 탈락이다.
6. `|moveDx|` 걸음을 다 걸으면 끝.

- 멈춘 뒤 남은 걸음은 버린다(거절이 아니다 — 단 `MOVE` 가 한 걸음도 못 가면 `CANNOT_MOVE`). 실제 도착점은 대본(`move.to`)·`UNIT_MOVED`
  (`toX`·`toY`·`fuelLeft`)에 싣는다 — 클라의 예측이 달라도 서버 값이 이긴다.
- 이동 중 구름 바다에 빠지거나 낙하 피해로 체력이 0 이 되면 그 차례는 거기서 끝난다(발사 없음). `MOVE` 도 같다(D-228): 탈락 → 끝났나 → 아니면
  연출 대기(걸음 × 2틱 + 떨어진 칸, 하한 600ms — 재생할 대본이 없어 `lastShot` 은 null) → 다음 차례. 치명적이지 않은 낙하면 피해만 반영하고
  차례가 계속된다 — 그 자리에서 더 걷거나 쏠 수 있다.
- 같음(D-228): 떨어짐이 없는 한 `MOVE(a) → MOVE(b) → FIRE(moveDx=c)` 와 `FIRE(moveDx=a+b+c)` 는 도착점·남은 연료·딜레이·탄도가 같다(같은 방향일 때 —
  걷기는 위치·연료만 보고 앞 걸음을 기억하지 않는다). 대본 `move` 는 `FIRE` 안에서 걸은 부분만 싣는다.

## 6. 발사

### 6.1 탄도

정수 적분, 틱 = 1/60초(재생 기준 시간). 상수(Q16):

| 상수 | 값 | 뜻 |
| --- | --- | --- |
| `G` | 3072 | 중력 — 틱당 아래로 0.046875칸/틱² |
| `V_PER_POWER` | 4096 | 힘 1 당 초속 — 힘 100 = 6.25칸/틱 |
| `WIND_PER_UNIT` | 46 | 바람 1 당 가로 가속 |
| `MAX_TICKS` | 900 | 15초 — 넘으면 소멸(폭발 없음) |

- 처음 속도: `v = power × V_PER_POWER`, `vx = v × COS[angle] >> 16`, `vy = −(v × SIN[angle] >> 16)` (`SIN`·`COS` 는 0~180° 의 × 65536 정수 표, 소스에 상수로 박는다. `>>` 는 산술 시프트).
- 처음 위치: 포구(§3).
- 틱마다 (반암시 오일러): `vx += ax; vy += G; x += vx; y += vy` — `ax = wind × WIND_PER_UNIT × 바람 영향 / 100`(§3, 바람개비면 0).
- **홀씨탄**은 세로 속도가 처음 0 이상이 된 틱(꼭대기)의 끝에서 갈라진다(`end = SPLIT`): 그 자리·속도에서 가로 속도만 −49152·0·+49152
  (±0.75칸/틱) 바꾼 씨앗 셋이 같은 지형 위를 이어 난다(틱 번호는 발사부터 이어 센다). 꼭대기 전에 무엇에 닿으면 그 자리에서 씨앗 하나로 터진다.
- **충돌 검사**: 틱의 이동 선분을 `n = max(|Δx|, |Δy|) / 65536 + 1` 등분한 점마다 차례로 본다(시작점 제외, 끝점 포함). 처음 걸린 점이 충돌점이다.
  1. 칸 x < 0 또는 x ≥ 640 → **밖으로 나감**(폭발 없음).
  2. 칸 y ≥ min(360, cloudLevel) → **구름에 빠짐**(폭발 없음).
  3. 칸 y ≥ 0 이고 흙 → **지형 충돌**.
  4. 어떤 포대의 맞는 범위(§3) 안 → **직격**(그 포대). 단 쏜 포대 자신은 처음 10틱 동안 검사하지 않는다.
  - 같은 점에서 3·4 가 함께면 직격이 먼저다.
- 궤적 점열: 2틱마다 한 점 + 마지막 점(충돌점 또는 소멸점), 칸 정수.

### 6.2 폭발

| 탄 | 구멍 반경 r | 피해 반경 R | 최대 피해 | 수량 |
| --- | --- | --- | --- | --- |
| `PEA` 콩알 | 14 | 18 | 30 | 무제한 |
| `PUMPKIN` 호박탄 | 24 | 30 | 45 | 매치당 2발 |
| `VINE` 덩굴탄(새싹포) | 16 — **쌓는다** | 18 | 15 | 매치당 2발 |
| `CHESTNUT` 알밤탄(도토리포) | 34 | 30 | 25 | 매치당 2발 |
| `SPORE` 홀씨탄(민들레포) | 10 (씨앗마다) | 14 | 16 (씨앗마다) | 매치당 2발 |

- 충돌점 칸 `(cx, cy)` 중심으로 `(x − cx)² + (y − cy)² ≤ r²` 인 칸을 지운다(**구멍**).
- **덩굴탄**은 같은 원을 흙으로 **채운다**(둔덕). 단 그 순간 살아 있는 포대마다 몸 중심에서 반경 **7칸**(맞는 범위 + 1) 안은 비워 둔다 —
  포대가 흙에 묻히지 않는다. 비운 자리(몸 중심 목록)는 지형 연산에 함께 저장하므로 (시드, 연산 목록)으로 언제든 같은 지형을 다시 만든다.
- 씨앗이 여럿이면(홀씨탄) 모두 내려앉은 뒤 구멍을 대본 순서대로 지우고, 포대마다 **씨앗 피해의 합**을 한 번에 깎는다(직격은 그 씨앗만 최대).
- 피해: 포대마다 몸 중심과 충돌점의 거리 `d = isqrt((x − cx)² + (y_body − cy)²)`(칸, 정수 제곱근 내림). `d < R` 이면 `피해 = 최대 × (R − d) / R`(정수 나눗셈). **직격당한 포대는 최대 피해.** 쏜 사람 자신도 맞는다.
- 피해는 좌석 순으로 계산하고 체력은 0 아래로 내려가지 않는다(0 이면 §6.3 에서 탈락).
- 밖으로 나감·구름·소멸이면 구멍도 피해도 없다. 쏜 탄은 그래도 소비한다.

### 6.3 낙하와 탈락

폭발 뒤(또는 걷다가 받침을 잃으면) **좌석 순으로** 받침 없는 포대를 떨어뜨린다:

- 받침이 생길 때까지 footY 를 1씩 내린다. 발밑이 구름 수면 이상이 되면(footY ≥ cloudLevel) **구름 바다 탈락**.
- 떨어진 칸 수 f 가 20 을 넘으면 `(f − 20) / 2` 피해(정수 나눗셈).
- 낙하가 끝나면 체력 0 인 포대를 **탈락**(사유 `KNOCKED_OUT`)시킨다. 같은 해소에서 탈락한 포대는 **함께** 탈락한 것이다.
- 탈락한 포대는 전장에서 사라진다(지형 변화 없음). 차례에서 빠지고 맞는 범위도 없다.

### 6.4 아이템 (S4, D-224)

판 시작 때 전원이 세 가지를 1개씩 받는다. 한 차례에 하나를 `FIRE.item` 에 싣고, 쓰는 순간 없어진다. `SKIP`·`MOVE` 에는 실을 수 없다.

| 아이템 | 효과 |
| --- | --- |
| `WATERING_CAN` 물뿌리개 | `FIRE` 를 해소할 때 걷기 전에 체력 +25(최대 체력까지) — 이미 `MOVE` 로 걸은 뒤, `FIRE.moveDx` 걷기 전(D-228. `MOVE` 로 걷다 떨어지는 피해는 막지 못한다). 대본 `heal` 에 오른 양 |
| `PINWHEEL` 바람개비 | 이번 발은 바람을 받지 않는다(`ax = 0`) |
| `TWIN_SEED` 겹씨앗 | 첫 발의 폭발·낙하·탈락까지 끝난 뒤, 그 자리(낙하했으면 내려간 자리)에서 같은 각도·힘·탄으로 한 발 더. 탄 수는 한 발만 쓴다. 쏜 포대가 탈락했거나 살아 있는 포대가 하나뿐이면 쏘지 않는다 |

- 두 발이어도 **한 순간**이다 — 두 발 사이에 탈락한 포대도 같은 탈락 번호(§6.3)를 받는다.
- 연출 대기(§9.2)는 단계마다 (비행 + 폭발 36틱 + 가장 긴 낙하)를 더한다.

## 7. 바람

- 차례 시작마다 −10~+10 정수(양수 = 오른쪽). `wind = (splitmix(seed, 1000 + 차례 번호) mod 21) − 10` — 차례 번호는 0부터 센다.
- 공개 정보다(지금 차례의 바람). 시드는 비공개라 다음 바람은 아무도 모른다.

## 8. 구름 밀물 (서든데스)

- 매치가 끝나지 않고 **차례 번호가 10 × 인원** 이상이 되면, 그 뒤 차례 시작마다 수면이 **15칸** 오른다(cloudLevel −= 15).
- 수면이 오른 뒤 발밑이 수면 이상인 포대는 구름 바다 탈락(차례 시작 단계에서, 같은 순간 함께 탈락).
- 수면은 0 이하까지 오르므로 판은 반드시 끝난다 — **차례 상한 10 × 인원 + 24**(§12 불변식).

## 9. 시작·연출 대기·종료

### 9.1 시작

라운드 스타터가 시드(`SecureRandom`)를 뽑아 순수 엔진에 넘긴다. 맵(§2) → 출발 자리 → 포대 고르기(§4.0) → 첫 차례 시작(§4.1-1). 시작 이벤트는 없다 — 시작 상태(고르기 단계)는 resync 로 받는다.

### 9.2 연출 대기 (`RESOLVING`)

- `FIRE` 해소 직후 상태는 이미 결과를 담고 있고 단계만 `RESOLVING` 이다. 아무의 차례도 아니다(`pendingSeats` 비어 있음).
- 대기 길이 = (이동 걸음 수 × 2틱 + 걷다 떨어진 칸 + 단계마다[가장 늦게 내려앉는 씨앗의 비행 틱 + 낙하 칸 수의 최댓값 + 폭발 36틱]) × 1000 / 60 ms, 하한 600ms·상한 18,000ms. `SKIP`·시간 초과는 대기 없이 바로 다음 차례.
- 대기가 끝나면(엔진 타이머) 매치 종료를 판정하고 다음 차례를 연다.

### 9.3 종료와 순위

- 해소 직후(또는 차례 시작의 밀물 직후·탈주 직후) **살아 있는 포대가 1개 이하**면 매치 종료. 사람이 있던 판에서 사람 포대가 하나도 안 남으면(봇만 남음) 그 자리에서 종료(`NO_HUMANS`). 처음부터 봇만인 판은 끝까지 간다.
- 종료 판정은 `FIRE` 해소 직후에 한다 — 끝난 판은 연출 대기 없이 바로 `ENDED`(클라는 마지막 대본을 재생한 뒤 결과를 보인다).
- **순위**:
  1. 살아 있는 포대 — 체력 높은 순(같으면 동순위). 보통은 1명이다.
  2. 탈락한 포대 — **늦게 탈락한 순**. 같은 순간 탈락하면 그 해소 **직전 체력**이 높은 쪽이 위, 같으면 동순위.
  3. 탈주자 — 맨 아래(동순위).
- 사유: `LAST_STANDING`(생존 1) · `ALL_DOWN`(같은 순간 모두 탈락) · `NO_HUMANS`.
- 2:2 팀전의 종료·순위는 §14.

## 10. 난수 (SplitMix64)

```
splitmix(seed, k):  z = seed + (k + 1) × 0x9E3779B97F4A7C15
                    z = (z ^ (z >>> 30)) × 0xBF58476D1CE4E5B9
                    z = (z ^ (z >>> 27)) × 0x94D049BB133111EB
                    return z ^ (z >>> 31)          (64비트 부호 없는 연산)
```

- 맵 생성은 같은 함수를 스트림 번호 0, 1, 2… 로 차례로 부른다. 바람은 1000 + 차례 번호, 봇 오차는 2,000,000 + 차례 번호 × 8 + 좌석.
- `mod` 는 부호 없는 나머지(`Long.remainderUnsigned`).

## 11. 탈주

- 탈주가 확정되면 그 포대를 **탈락**(사유 `DESERTED`)시키고 판은 계속한다. 살아 있는 포대가 1개 이하가 되면 종료.
- 그 사람의 차례(`AIMING`)였다면 바로 다음 차례를 연다. 연출 대기 중이면 대기는 그대로 끝난다.
- 이미 탈락한 좌석의 탈주는 해당 없음.

## 12. 검증 기준 (S1)

- **결정성**: 같은 (시드, 인원, 액션 열)이면 상태·이벤트가 같다 — 골든 해시 테스트.
- **불변식** (매 전이 뒤): 체력 0~최대 체력(고르기 중엔 자리 표시 100) · 특수탄 0~2 · 아이템은 겹치지 않음 · 고르기가 끝나면 모두 종류가 있음 · 고르기 중엔 차례·구멍·무장 없음 · 탈락 포대는 차례·맞는 범위에 없음 · 살아 있는 포대는 받침이 있고 발이 수면 위 · 호박탄 0~2 · 연료 0~100 · `AIMING` 이면 차례 좌석이 살아 있음 · 끝나면 순위가 좌석 전부를 덮음 · (D-228) 차례 시계·`moveFuelUsed` 는 `AIMING` 에만 있고(그 밖은 0), `moveFuelUsed` 는 0~포대 연료(차례 안에서 연료는 줄기만 한다). 서버 경로로 연 판은 `AIMING` 이면 `turnStartedAt > 0`(시계 없이 연 테스트 판·D-228 이전 판만 0).
- **상한**: 2~6인 무작위 행동 시뮬레이션 1,000판이 전부 차례 상한(§8) 안에서 끝남. 모든 발이 900틱 안에 끝남.
- **비용**: 한 발 해소 ≤ 6ms(로컬 측정값을 `docs/plans/artillery.md` §4.5 에 기록).

## 13. 코드와 테스트 (S1, D-221)

| 절 | 코드 | 테스트 |
| --- | --- | --- |
| §1·§6.1 정수·삼각표 | `physics/Fixed` | `physics/PhysicsTest`(`trig_table_and_isqrt`) |
| §10 난수 | `physics/SplitMix64` | `PhysicsTest`(공개 참조값) |
| §1·§6.2 지형·구멍 | `physics/Terrain`(`carve`·`columnRuns`) | `PhysicsTest`, `MapGeneratorTest.column_runs_round_trip` |
| §2 맵 생성 | `physics/MapGenerator`, 캐시 `physics/TerrainCache` | `MapGeneratorTest`(같은 시드 같은 맵, 2~6인 × 300시드 대칭·출발 자리) |
| §3·§6.3 몸·낙하 | `physics/Body` | `PhysicsTest`(절벽·구름) |
| §5 걷기 | `physics/Walker` | `PhysicsTest`(연료·계단·벽·절벽) |
| §6.1 탄도 | `physics/Ballistics` | `PhysicsTest`(45° 사거리·바람·거울·직상·밖·구름) |
| §6.2 탄 | `rules/Shell` | `ArtilleryEngineTest` |
| §4·§7~§11 차례·바람·밀물·종료·순위·탈주 | `ArtilleryEngine` | `ArtilleryEngineTest`(12건) |
| §12 불변식 | `invariant/ArtilleryInvariantChecker` | `ArtillerySimulationTest`(2~6인 × 200판) |
| §12 결정성 | — | `ArtilleryGoldenTest`(두 번 같음·고정 해시·JSON 왕복) |
| §12 비용 | — | `ArtilleryCostTest`(구멍 60개 판 한 발·맵 생성) |

### S2 (D-222) — 서버 경로

| 무엇 | 코드 | 테스트 |
| --- | --- | --- |
| 포트 어댑터(연출 대기 = 엔진 타이머, 비공개 뷰 없음, 봇 안전망) | `ArtilleryGameEngine` | `ArtilleryGameEngineTest`(5건) |
| 카탈로그(2~4인·턴 20/30/45/60·기본 `DISABLED`) | `ArtilleryGameDefinition` | `ArtilleryGameDefinitionTest` |
| 공개 뷰(시드 없음·지형 RLE) | `state/ArtilleryPublicView` | `ArtilleryGameEngineTest` |
| 봇 "보통" | `bot/ArtilleryBotPolicy` | `bot/ArtilleryBotPolicyTest`(결정성·무작위 상대 60/60·한 수 ≤200ms) |
| 시작 | `lifecycle/ArtilleryRoundStarter`(`SecureRandom` 시드, 봇 좌석은 계정으로) | `infra.bot.ArtilleryBotMatchSimulationIT`(2·4봇·손 놓은 사람, Docker) |
| 기록·상대 전적 | `persistence/ArtilleryMatchRecorder`·`ArtilleryHeadToHead`, `V16__artillery_match.sql` | `persistence/ArtilleryMatchRecorderIT`(Docker), `infra.ws.ArtilleryRecorderFailureTest` |

### S3 (D-223) — 클라

| 무엇 | 코드 | 테스트 |
| --- | --- | --- |
| 지형 마스크·구멍·걷기 미리보기(서버 미러) | `client/src/features/artillery/terrain.ts` | `artilleryLogic.test.ts`(서버 `PhysicsTest` 와 같은 수) |
| 대본 재생 타임라인 | `replay.ts` | `artilleryLogic.test.ts` |
| 스토어(순수 리듀서)·sink | `artilleryStore.ts`·`artilleryRoomSink.ts` | `artilleryLogic.test.ts` |
| Canvas 그리기·전장 | `draw.ts`·`ArtilleryField.tsx` | 브라우저 실측(데스크톱·375px) |
| 조작부·게임판·결과·튜토리얼 | `ArtilleryControls.tsx`·`ArtilleryTable.tsx`·`ArtilleryMatchEnd.tsx`·`tutorial/` | `ArtilleryTable.test.tsx`·`artilleryTutorial.test.tsx`, e2e `practice.e2e.ts` |
| CSS `.ar-` 네임스페이스 | `styles/parts/23-artillery-table.css` | `styles/artilleryCssNamespace.test.ts` |

### S4 (D-224) — 포대 3종·특수탄·아이템

| 무엇 | 코드 | 테스트 |
| --- | --- | --- |
| 포대 종류·탄·아이템 수치 | `rules/UnitKind`·`rules/Shell`·`rules/Item` | `ArtilleryUnitsAndItemsTest` |
| 고르기 단계(동시·15초·숨김·공개) | `ArtilleryEngine`(`pick`·`reveal`·`expire`), 어댑터 `privateView`·`timeoutAction` | `ArtilleryUnitsAndItemsTest.Picking`, `ArtilleryGameEngineTest` |
| 바람 영향·홀씨 갈라짐 | `physics/Ballistics`(`windAccel`·`fly`·`SPLIT`) | `ArtilleryUnitsAndItemsTest.Units`·`Specials` |
| 둔덕(채우기, 몸 자리 비움) | `physics/Terrain.fill`, `state/Crater`(`fill`·`spare`) | `ArtilleryUnitsAndItemsTest.Specials`, 클라 `artilleryLogic.test` |
| 아이템·겹씨앗 두 단계 대본 | `ArtilleryEngine`(`volley`), `state/ShotReport`(`stages`·`heal`·`item`) | `ArtilleryUnitsAndItemsTest.Items`, `ArtilleryGoldenTest`(S4 골든) |
| 봇 — 시드 고르기·바람개비 격자·탄/아이템 변형(비용 차감) | `bot/ArtilleryBotPolicy` | `ArtilleryBotPolicyTest`(섞인 유닛 무작위 상대 60/60·유닛별 봇끼리 승률) |
| 시뮬레이션(고르기·특수탄·아이템 섞음) | — | `ArtillerySimulationTest`(2~6인 × 200판) |
| 클라 — 고르기 화면·탄/아이템 칩·단계 재생·포대 그림 | `ArtilleryPicker.tsx`·`ArtilleryControls.tsx`·`replay.ts`·`draw.ts` | `ArtilleryTable.test.tsx`·`artilleryLogic.test.ts`·`artilleryTutorial.test.tsx` |

### S5 (D-225·D-226) — 2:2 팀전·딜레이 차례

| 무엇 | 코드 | 테스트 |
| --- | --- | --- |
| 판 설정(팀전·차례 규칙) | `ArtilleryEngine.Setup`, `state/TurnOrder`, 상태 `teams`·`turnOrder`, 포대 `delay` | `ArtilleryTeamsAndDelayTest` |
| 딜레이 쌓기·다음 차례·순서 줄 | `ArtilleryEngine`(`pickNext`·`turnQueue`), `rules/Shell.delay`·`rules/Item.delay` | `ArtilleryTeamsAndDelayTest`, `ArtilleryGoldenTest`(S5 골든) |
| 팀 종료·순위 | `ArtilleryEngine`(`teamOutcome`·`teamPlacements`), `state/MatchResult`(`teams`·`winnerTeam`) | `ArtilleryTeamsAndDelayTest` |
| 좌석 순서 판의 물리 그대로 | — | `ArtilleryGoldenTest.physics_fingerprint_unchanged_since_s4`(main 037963e 와 같은 지문) |
| 방 설정·시작 인원 | `ArtilleryGameDefinition`(`TEAM_MODE`·`playersToStart`), `lifecycle/ArtilleryRoundStarter` | `ArtilleryGameDefinitionTest`, `ArtilleryBotMatchSimulationIT`(2:2 봇 완주) |
| 팀 기록·ELO·상대 전적 | `persistence/ArtilleryMatchRecorder`·`ArtilleryHeadToHead`, `V18__artillery_teams.sql` | `ArtilleryMatchRecorderIT` |
| 봇 — 아군 겨냥 안 함·아군 피해 감점 | `bot/ArtilleryBotPolicy` | `ArtilleryTeamsAndDelayTest` |
| 시뮬레이션 | — | `ArtillerySimulationTest`(개인전 2~6인 + 팀전 4·6인, 각 200판) |
| 클라 — 팀 칩·딜레이·다음 차례·⏱ +N·팀 결과 | `delay.ts`·`ArtilleryTable.tsx`·`ArtilleryControls.tsx`·`ArtilleryMatchEnd.tsx` | `ArtilleryTable.test.tsx`·`artilleryLogic.test.ts` |

### S6a (D-228·D-229) — 걷기 확정·차례 전체 마감·조준 미리보기 (서버·포트)

| 무엇 | 코드 | 테스트 |
| --- | --- | --- |
| `MOVE`·`CANNOT_MOVE`·`UNIT_MOVED`·걷다 떨어지기 | `ArtilleryEngine.move`, `action/ArtilleryAction.Move`, `event/ArtilleryEvent.UnitMoved`, 상태 `moveFuelUsed` | `ArtilleryMoveTest`(같음 검사 200표본·거절·낙하 3갈래·넘기기 딜레이) |
| 차례 전체 마감(엔진 타이머)·공개 뷰 `turnRemainingMs`·옛 JSON | 상태 `turnStartedAt`, `ArtilleryEngine.turnRemainingMillis`, 어댑터 `timer`·`onTimer`, `state/ArtilleryPublicView` | `ArtilleryTurnClockTest` |
| 조준 미리보기 | `ArtilleryEngine.preview`, `action/ArtilleryAction.Preview`, `event/ArtilleryEvent.AimPreview`, 어댑터 `preview` | `ArtilleryPreviewTest` |
| 포트 ⑧ 기본 empty·다른 여섯 게임 override 0 | `core/GameEngine.preview` | `core/GameEnginePreviewDefaultTest` |
| 게임 중립 미리보기 컨트롤러·버킷 | `infra.ws.GamePreviewController`, `infra.ratelimit`(`game-preview` 60/10s, `game-action` 30 → 60) | `infra.ws.GamePreviewControllerTest`, `RateLimitRoutingTest` |
| 골든 — 물리 무변경·JSON 두 필드만 | — | `ArtilleryGoldenTest`(`physics_fingerprint_unchanged_since_s4`·`without_the_turn_clock_fields_the_s5_bytes_are_unchanged`, 해시 재고정) |
| 시뮬레이션(MOVE 조각 섞음) | — | `ArtillerySimulationTest`(차례의 3분의 1 이 1~3번 걷고 쏨) |
| 서버 경로 | — | `infra.ws.ArtilleryLiveMoveIT`(STOMP 두 클라: MOVE×3 연속 순번·중간 resync·순번 없는 AIM_PREVIEW·FIRE, Docker) |

## 14. 2:2 팀전 (S5, D-225)

- 대기실 방 설정 **모드**(`TEAM_MODE` — 개인전 / 2:2 팀전)로 고른다. 2:2 는 **정확히 4명**(정원 4, 3명으로 시작 못 함). 엔진은 6인 3:3 도 검증한다.
- **팀 = 좌석 홀짝**(좌석 0·2 대 1·3). 좌석 순서는 대기실 팀 배정(`teamPolicy`)이 정한다. 출발 자리는 개인전처럼 시드로 섞는다.
- **아군 피해 있음** — 물리는 개인전과 같다(자기 피해도 그대로).
- **종료**: 살아 있는 팀이 하나가 되면 그 팀 승(`LAST_STANDING`). 한 해소로 두 팀이 함께 전멸하면(`ALL_DOWN`) 그 마지막 순간 탈락한
  포대들의 **탈락 직전 체력 합**이 큰 팀 승, 같으면 **무승부**. 사람이 있던 판에서 사람이 모두 탈락하면(`NO_HUMANS`) 살아 있는 포대 수 →
  남은 체력 합 → 무승부.
- **순위**: 이긴 팀 1위(동순위) → 진 팀 → 탈주자. 무승부면 탈주하지 않은 모두 1위이고 승자는 없다. 탈주자는 팀이 이겨도 패.
- **기록**: ELO 는 팀 평균 방식(오목 2:2 와 같음). 이긴 팀의 탈주자는 상대 팀에 혼자 진 것으로 따로 잰다. 무승부는 ELO·승패 변화 없이
  행만. 참가자 행 `team`(V18, 개인전 NULL) — 상대 전적은 상대 팀만 센다.
- 봇은 같은 팀을 겨냥하지 않고 아군 피해를 자기 피해처럼 뺀다(아군 탈락 −3000).
- 두 번째 겹씨앗 발은 한 팀만 남으면 나가지 않는다(개인전의 "한 명만 남으면"과 같은 규칙).

## 15. 조준 미리보기 (S6a, D-229)

- 차례 주인은 쏘기 전 포신 각도·고른 탄을 `/app/room/{id}/preview` 로 `PREVIEW { angle, shell, n }` 를 보내 남에게 보여 줄 수 있다. 상태를 바꾸지
  않는다 — 락·저장·순번·턴 시계와 무관하다(포트 ⑧ `preview`, `docs/game-port.md`).
- 서버는 `AIMING` 의 차례 주인이 각도 0~180 을 보낼 때만 공개 `AIM_PREVIEW { seat, turn, angle, shell, n }`(**순번 없음**)를 방 토픽으로 낸다. 그 밖은
  조용히 버린다(거절 `ERROR` 없음). `shell` 은 그 포대가 지금 쏠 수 있는 탄일 때만 싣고 아니면 null(없는 특수탄으로 허세 못 부리게).
- **힘은 받지도 내보내지도 않는다**(입력 타입에 필드가 없다 — 실어 보내도 버린다). 위치도 싣지 않는다 — 위치는 `UNIT_MOVED` 가 확정값으로 준다.
- `turn`·`n` 은 받는 쪽이 낡은 프레임을 버리는 기준이다(지금 차례이고 지금까지 본 최대 `n` 보다 클 때만 쓴다). 서버는 락 없이 읽으므로
  `SHOT_RESOLVED` 뒤에 도착하는 미리보기가 있을 수 있다.
- 봇은 미리보기를 보내지 않는다. 미리보기 입력을 `/action` 으로 보내면 `INVALID_ACTION`, `legalActions`·봇에는 나오지 않는다.

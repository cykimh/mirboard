# 씨앗포 스킨 (D-227)

씨앗포 전장의 그림은 **스킨**이 그린다. 룰·물리·차례·맞는 범위는 서버 대본 그대로이고, 스킨은 "무엇을 어떤 상태로 어디에"를 받아 그리기만 한다.
디자이너는 **PNG 한 장과 JSON 한 개**만 바꾸면 된다.

## 구조

| 파일 | 하는 일 |
| --- | --- |
| `client/src/features/artillery/skins/index.ts` | **스킨 고르기 상수 `ACTIVE_SKIN`**(`'clay'` 기본 / `'vector'` / `'sprite'`)·시트 폴더 `SKIN_DIRS` |
| `skins/types.ts` | `ArtillerySkin` 계약 — 하늘·구름·흙, 포대(종류 × 상태), 씨앗, 폭발·둔덕, 이름표·체력바, 팔레트 |
| `skins/vectorSkin.ts` | 도형 스킨 — D-223 부터의 그림 그대로. 시트를 읽는 중·실패하면 이것으로 그린다 |
| `skins/spriteSkin.ts`·`skins/atlas.ts` | 시트 스킨 — `atlas.json` 을 검사하고 프레임을 골라 그린다 |
| `unitPose.ts` | 포대 상태 판정(스킨 밖 순수 함수) — 대기·조준·발사 반동·피격·쓰러짐 |
| `client/public/artillery/skins/{이름}/atlas.png`·`atlas.json` | 시트(빌드가 `/artillery/skins/{이름}/` 으로 그대로 복사) |
| `scripts/generate-artillery-sprites.mjs` | 임시 시트 `doodle` 생성기(`node scripts/generate-artillery-sprites.mjs`) |
| `skins/claySkin.ts`·`effects.ts` | **말랑 클레이 스킨(D-252, 기본)** — 아래 "clay 스킨" 절 |
| `scripts/generate-artillery-clay.mjs`·`scripts/artillery-clay/art.js` | clay 시트 생성기(Playwright 크로미움에서 Canvas 그림 코드를 돌린다) |

하늘·구름·이름표 색은 테마(라이트/다크)의 CSS 변수 `--ar-sky-top`·`--ar-sky-bottom`·`--ar-cloud`·`--ar-cloud-edge`·`--ar-label`
(`styles/parts/23-artillery-table.css`)이 정한다 — 시트 스킨도 이 부분은 도형 스킨 것을 쓴다.

## 포대 상태

| 상태 | 언제 | 길이 |
| --- | --- | --- |
| `idle` 대기 | 그 밖 | 반복 |
| `aim` 조준 | 차례 주인(재생 밖) · 쏜 포대가 첫 씨앗을 내기 전(재생 중) | 반복 |
| `recoil` 발사 반동 | 단계마다 첫 씨앗이 나가는 순간부터 | 200ms(12틱) |
| `hit` 피격 | 피해·낙하 피해가 들어간 순간부터 | 400ms(24틱) |
| `down` 쓰러짐 | 체력 0(그 단계가 끝나 사라지기 전까지) | 한 번 |

우선순위는 쓰러짐 > 피격 > 반동 > 조준 > 대기. 모든 창은 서버 대본 시간 안에 있다 — 연출이 재생을 늘리지 않는다.

## 시트 형식 (`atlas.json`, version 1)

```json
{
  "format": "mirboard-artillery-skin",
  "version": 1,
  "image": "atlas.png",
  "pxPerCell": 4,
  "labelLift": 15,
  "recoilCells": 2,
  "soil": { "grass": "#60a848", "grassDark": "#468438", "topsoil": "#96663e", "subsoil": "#704a2e" },
  "anims": {
    "unit.SPROUT.idle": {
      "frames": [[0, 0, 88, 56], [89, 0, 88, 56]],
      "tint": [[178, 0, 88, 56], [267, 0, 88, 56]],
      "anchor": [44, 48], "mount": [44, 26], "frameMs": 520, "loop": true
    }
  }
}
```

- **좌표는 전부 시트 픽셀.** 프레임은 `[x, y, w, h]`. `pxPerCell` = 시트 픽셀 / 전장 칸(전장은 640×360칸, 포대 화분은 폭 약 11칸).
- `anchor` = 프레임 안 기준점. **포대 = 발(화분 바닥 가운데)**, 포신 = 회전축(줄기 밑동), 씨앗·폭발 = 가운데. 없으면 프레임 가운데.
- `frameMs` = 한 프레임 길이(0 이면 첫 프레임만), `loop` = 반복(기본 true, 아니면 마지막 프레임에서 멈춤).
- `tint` = 좌석 색으로 칠할 **흰 실루엣** 프레임(`frames` 와 같은 수·크기). 그림 아래에 좌석 색으로 깔리므로, 그림 쪽은 그 자리를
  투명하게 두고 음영·광택만 반투명으로 그린다. 좌석 색은 6가지(`draw.ts` `SEAT_COLORS`).
- `labelLift` = 이름표를 발에서 몇 칸 위에(기본 15), `recoilCells` = 반동 때 포신이 뒤로 밀리는 칸(기본 2), `soil` = 흙 단면 색(없으면 기본 흙).

### 애니메이션 이름

| 이름 | 내용 | 없으면 |
| --- | --- | --- |
| `unit.{SPROUT\|ACORN\|DANDELION}.{idle\|aim\|recoil\|hit\|down}` | 포대 몸(화분+잎). **오른쪽을 보고 그린다** — 포신이 왼쪽이면 좌우로 뒤집는다. `mount` = 포신 회전축이 붙는 자리, `"barrel": false` 면 그 상태에서 포신을 안 그린다(쓰러짐) | 같은 종류 `idle` → 새싹포 같은 상태 → `unit.SPROUT.idle` |
| `barrel.{종류}` | 포신 — **오른쪽(0°)을 가리키게** 그린다. 각도대로 돌린다 | `barrel.SPROUT` |
| `seed.{PEA\|PUMPKIN\|VINE\|CHESTNUT\|SPORE}` | 날아가는 씨앗 | `seed.PEA` |
| `explosion` / `mound` | 폭발 / 덩굴탄 둔덕. 프레임은 시간이 아니라 **진행도**(폭발 36틱 = 0.6초)로 고른다. `radius` = 프레임에 그린 원의 반경(시트 픽셀) — 서버 구멍 반경에 맞춰 늘린다 | `mound` → `explosion` |

**필수**: `unit.SPROUT.idle`·`barrel.SPROUT`·`seed.PEA`·`explosion`. 정의가 틀리거나 프레임이 PNG 밖이면 그 시트는 쓰지 않고 도형 스킨으로 그린다
(콘솔 경고 한 줄).

## 새 스킨 넣기

1. `client/public/artillery/skins/{새이름}/` 에 `atlas.png`·`atlas.json` 을 둔다(위 형식, 임시 시트 `doodle` 을 복사해 고쳐도 된다).
2. `skins/index.ts` 의 `SPRITE_SKIN_DIR = '{새이름}'`, `ACTIVE_SKIN = 'sprite'`.
3. `npm --prefix client run test -- artillerySkin` — 고른 폴더의 시트가 형식에 맞고 프레임이 PNG 안에 있는지, 포대 3종 × 상태 5개·씨앗 5종이 다 있는지 검사한다.
4. 화면 확인: 봇과 연습 한 판(쏘기·맞기·쓰러지기·덩굴탄), 휴대폰 세로(작은 화면 보정 `uiBoost`), 라이트/다크.

기본은 `'clay'`(D-252). `doodle` 은 형식 견본으로 남긴 임시 그림이다.

## clay 스킨 — 말랑 클레이 장난감 (D-252)

D-185 시안 B. 시트(`skins/clay/`, 1024×473, 약 139KB, `pxPerCell` 6)는 **그림**만 담고 **움직임은 코드**(`claySkin.ts`)가 만든다 — 점토 인형을
한 장씩 바꿔 찍는 대신 눌리고 늘어나게 한다. 되돌리기는 `ACTIVE_SKIN = 'vector'` 한 줄.

| 시트 묶음 | 내용 |
| --- | --- |
| `unit.{종류}.{상태}` | 화분 몸 + 표정(대기 = 뜬 눈·깜빡, 조준 = 눈썹, 반동 = 꾹 감은 눈, 피격 = X 눈·동그란 입, 쓰러짐 = X 눈·물결 입). 몸통·테는 좌석 색 실루엣(`tint`) |
| `leaves.{종류}` *(clay 전용)* | 잎(싱싱·시듦). 기준점 = 테 위 가운데 — 코드가 까딱이고, 시들면 아래로 접는다 |
| `barrel.{종류}` | 포신(띠·새싹 봉오리가 좌석 색) |
| `droop.{종류}` *(clay 전용)* | 쓰러졌을 때 축 처진 포신(기준점 = 몸의 발) |
| `flash` *(clay 전용)* | 피격 번쩍용 흰 실루엣 |
| `seed.*`·`explosion`(6장)·`mound`(4장) | 씨앗 5종, 12각 별 폭발(흰 섬광 → 별 → 구멍 → 점토 뭉치로 흩어짐), 초록 점토 둔덕(납작 → 길쭉 → 잎이 뿅) |

코드가 그리는 것: 하늘(해·달, 먼 섬, 떠가는 구름, 바람 줄, 흩날리는 꽃잎·홀씨 — 밤에는 별·반딧불), 구름 바다, 흙(마스크를 매끈한 윤곽으로 바꿔
미리 그린 점토 무늬를 오리고 풀·그늘 띠를 얹는다 — 큰 화면은 2배 해상도), 구슬 줄 궤적과 반짝이, 알약 이름표(체력이 스르르 줄고 깎인 만큼 흰색으로
남는다, 팀전이면 테두리가 팀 색), 통통 튀는 차례 화살표, 위 가운데 바람개비.

| 연출 | 내용 | 길이 |
| --- | --- | --- |
| 대기·조준 | 숨 쉬듯 눌렸다 늘어남, 깜빡임, 잎 까딱, 차례 주인 발밑 빛 고리 | 반복 |
| 걷기·낙하 | 걷는 쪽으로 기울어 통통, 떨어질 때 길쭉 → 닿으면 납작 | 걸음·낙하 동안 |
| 발사 | 포신 찌그러짐 + 화분 납작, 포구 별 섬광, 연기 고리 셋, 별 조각, 연기 | 200ms + 꼬리 |
| 착탄 | 섬광 → 충격파 고리 둘 → 별 폭발 → 흙덩이·점토 조각(포물선·회전)·가장자리 부스러기 → 먼지 연기(바람에 밀림), 화면 흔들림 | 0.6초 + 꼬리 |
| 피격 | 흰 번쩍, 눌렸다 튕김, 사방 흰 선, 튀어 오르는 피해 숫자(직격은 더 크게), 회복은 초록 `+N` | 400ms / 숫자 1초 |
| 쓰러짐 | 옆으로 넘어가 한 번 통, 머리 위를 도는 별 셋, 사라질 때 퐁 | 0.6초 남짓 |
| 구름 풍덩 | 구름 덩이가 솟고 물결 고리 | 0.75초 |
| 2:2 | 발밑 팀 색 아우라, 이름표 테두리 | 반복 |
| 승리 | 이긴 포대가 폴짝, 하늘에 축포, 색종이 | 9초 |

- **재생 길이는 그대로** — 사건(`effects.ts` `fxScript`)은 모두 대본 타임라인 안이고, 입자·숫자의 꼬리(`FX_TAIL_MS` 0.9초)는 재생이 끝난 뒤
  화면에서만 사라진다(조작·차례를 막지 않는다).
- **움직임 줄이기** — 재생 자체를 건너뛰므로 입자·흔들림·축포가 없고, 화면 시계를 0 으로 세워 대기 몸짓·구름·꽃잎도 멈춘다.
- **성능** — 입자는 상태 없이 사건 시각의 닫힌 식으로 그린다(사건마다 개수 고정 = 상한, 프레임마다 할당 없음). 하늘·흙 무늬·구름·좌석 색 실루엣은
  캔버스에 미리 그려 붙인다. 테마 색은 0.4초에 한 번만 읽는다.
- **크기** — 포대 그림만 1.25배(`unitScale`). 맞는 범위·물리는 서버 그대로다.
- **시트 다시 뽑기** — `node scripts/generate-artillery-clay.mjs` 후 `npm --prefix client run test -- artillery`(150KB 상한·묶음 검사).

### 스킨 계약의 선택 항목 (D-252)

`unitScale`·`smooth`·`shakeCells`·`terrainCanvas`·`drawEffects`·`drawCelebration`·`drawWind`, `drawSky(ctx, p, time, wind)`,
`Palette.night`(CSS `--ar-night`), `SkinUnit.team`·`cheer`, `SkinLabel.seat`·`color`·`team`·`isTurn`·`time`. 전부 선택이라 도형·`doodle` 스킨은
그대로 돈다. 하늘 색은 `.ar-field-clay` 가 `--ar-sky-*` 를 덮어쓴다(전장 요소에 `ar-field-{스킨 id}` 클래스가 붙는다).

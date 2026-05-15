# Phase 8F — 카드 / 캐릭터 / 보드 AI 이미지 생성 가이드

본 문서는 D-40 의 결정에 따라 사전 생성된 정적 이미지로 카드 비주얼을 교체할
때 사용하는 prompt 템플릿과 명명 규칙. 56장 카드 + 4종 캐릭터 + 1장 보드 배경을
정적 번들에 동봉한다 (Phase 7-4 `bundleClient` flag 가 자동 동봉).

## 핵심 일관성 전략 (D-40)

1. **마스터 prompt 1개** + slot 변수 (`{suit}`, `{rank}`, `{rank_visual}`)
2. **seed 고정** (가능한 모델)
3. **PoC 4장 우선** — 각 슈트 A 1장씩 → 사용자 검토 → 톤 OK 면 나머지 52장 생성
4. **suit 별로 13장 일괄** (한 번 생성으로 같은 톤 보장)
5. **WebP 출력** — PNG 대비 40~60% 용량 감소, jar 동봉 시 부담 ↓

## 카드 (56장)

### 마스터 prompt 템플릿

```
A single playing card, 5:7 aspect ratio, white background with rounded corners,
flat 2D design, soft drop shadow.

Top-left corner: small "{rank_visual}" character in {color}.
Bottom-right corner: same "{rank_visual}" mirrored upside-down.
Center: large "{suit}" symbol in {color}, around 60% of card width.

Style: minimalist, casino playing card, clean lines, no texture, no embellishments.
Color palette: white background (#ffffff), {color} accents, soft gray border (#d0d0d0).
```

### Slot 변수

| Suit | `{suit}` | `{color}` | 의미 |
| --- | --- | --- | --- |
| JADE | 다이아몬드 (◆) | 녹색 #2e8b57 | 동양풍 옥 |
| SWORD | 검 (⚔) | 파란색 #4169e1 | 무사풍 검 |
| STAR | 별 (★) | 빨간색 #dc143c | 별 모양 |
| PAGODA | 탑 (⛩) | 주황색 #ff8c00 | 동양풍 사찰 |

| Rank | `{rank_visual}` |
| --- | --- |
| 2~10 | 숫자 그대로 |
| 11 | J |
| 12 | Q |
| 13 | K |
| 14 | A |

### 명명 규칙

- 일반 카드: `client/public/cards/{suit-lowercase}-{rank-visual}.webp`
  - 예: `jade-2.webp`, `sword-10.webp`, `pagoda-J.webp`, `star-A.webp`
- 특수 카드 4종: 별도 일러스트
  - `client/public/cards/mahjong.webp` — 1점, 마작 일패 모티프 (소원 카드)
  - `client/public/cards/dog.webp` — 0점, 충실한 강아지 (파트너 양도)
  - `client/public/cards/phoenix.webp` — -25점, 보라색 불사조 (와일드 카드)
  - `client/public/cards/dragon.webp` — +25점, 황금 용 (최강 단일 카드)
- 카드 뒷면: `client/public/cards/back.webp` — 다이아몬드 패턴, 짙은 파랑/녹색

### 특수 카드 prompt 예

```
A vertical playing card, 5:7 aspect ratio, white background with rounded corners.
Center: a stylized purple phoenix bird rising from flames, art deco style.
Corner labels: "P" in purple at top-left, mirrored at bottom-right.
Soft drop shadow, clean minimalist illustration.
```

## 캐릭터 (4종, 좌석별)

마스터 prompt:
```
Vertical portrait, anime-style avatar of a Tichu player, 1:1 aspect ratio,
flat dark background ({team_color}).
{seat_index} 의 자리에 앉은 인물 — {personality} 표정.
Style: clean lineart, soft shading, no text.
```

좌석/팀 색:
- Seat 0 (Team A — 청): `seat-0.webp` — 침착한 표정 / 푸른 의상
- Seat 2 (Team A — 청): `seat-2.webp` — 진지한 표정 / 푸른 의상
- Seat 1 (Team B — 적): `seat-1.webp` — 자신감 있는 표정 / 붉은 의상
- Seat 3 (Team B — 적): `seat-3.webp` — 미소 표정 / 붉은 의상

저장 위치: `client/public/characters/seat-{0,1,2,3}.webp`

## 보드 배경 (선택)

`client/public/board/felt.webp` — 카지노 펠트 또는 동양풍 자개 텍스처. 1920×1080
또는 1080×1920 (모바일 세로). 8E 에서 이미 CSS radial gradient 로 대체했으므로
필수 아님 — 이미지가 있으면 `.table-arena` 의 `background` 를 `url(/board/felt.webp)`
로 교체할 수 있음.

## 빌드 통합 확인

```bash
# 자산 추가 후 정적 번들 빌드 (Phase 7-4 bundleClient flag)
./gradlew :server:bootJar -PbundleClient

# jar 안에 자산 동봉 확인
unzip -l server/build/libs/server-*.jar | grep -E 'static/(cards|characters|board)/' | head -10

# 로컬 실행 + 카드 1장 확인
./gradlew :server:bootRun -PbundleClient
curl -I http://localhost:8080/cards/jade-2.webp
# → 200 OK + Content-Type: image/webp
```

## 자산이 없을 때의 동작

`CardChip` 은 `<img onError>` 로 graceful fallback: 이미지 로드 실패 시 기존
텍스트 글리프 (◆ 2) 로 표시. 사용자가 자산을 점진적으로 채워도 게임이 깨지지
않는다.

---

## PoC 4장 — 즉시 복붙 prompt (각 슈트 A 1장)

본 prompt 4개를 그대로 AI 이미지 생성기에 붙여넣어 PoC 4장을 만들어 톤 일관성을
검증한다. **반드시 같은 모델 + 같은 seed (가능한 경우) + 같은 스타일 키워드**.
완성된 PNG/WebP 를 `client/public/cards/{name}.webp` 에 저장하면 `CardChip` 가
자동으로 텍스트 글리프 대신 이미지를 렌더한다.

### PoC #1 — `jade-A.webp`

```
A vertical playing card, 5:7 aspect ratio (280x392px), pure white background,
rounded corners (12px radius), soft drop shadow.
Top-left corner: small "A" in jade green (#2e8b57), bold sans-serif, 14pt.
Bottom-right corner: same "A" rotated 180 degrees.
Center: large jade-green diamond ◆ symbol, 60% of card width, glossy.
Style: minimalist casino playing card, flat 2D, clean lines, no texture.
Border: 1px solid soft gray (#d0d0d0).
```

### PoC #2 — `sword-A.webp`

```
A vertical playing card, 5:7 aspect ratio (280x392px), pure white background,
rounded corners (12px radius), soft drop shadow.
Top-left corner: small "A" in royal blue (#4169e1), bold sans-serif, 14pt.
Bottom-right corner: same "A" rotated 180 degrees.
Center: large royal-blue crossed-swords ⚔ symbol, 60% of card width, metallic
sheen.
Style: minimalist casino playing card, flat 2D, clean lines, no texture.
Border: 1px solid soft gray (#d0d0d0).
```

### PoC #3 — `star-A.webp`

```
A vertical playing card, 5:7 aspect ratio (280x392px), pure white background,
rounded corners (12px radius), soft drop shadow.
Top-left corner: small "A" in crimson red (#dc143c), bold sans-serif, 14pt.
Bottom-right corner: same "A" rotated 180 degrees.
Center: large crimson five-pointed star ★, 60% of card width, glossy.
Style: minimalist casino playing card, flat 2D, clean lines, no texture.
Border: 1px solid soft gray (#d0d0d0).
```

### PoC #4 — `pagoda-A.webp`

```
A vertical playing card, 5:7 aspect ratio (280x392px), pure white background,
rounded corners (12px radius), soft drop shadow.
Top-left corner: small "A" in dark orange (#ff8c00), bold sans-serif, 14pt.
Bottom-right corner: same "A" rotated 180 degrees.
Center: large dark-orange East Asian pagoda silhouette, 60% of card width,
clean ink-style.
Style: minimalist casino playing card, flat 2D, clean lines, no texture.
Border: 1px solid soft gray (#d0d0d0).
```

### 추천 도구별 호출법

| 도구 | 추천 |
| --- | --- |
| **OpenAI gpt-image-1** | 4번 별도 호출 + reference_images 로 PoC #1 결과를 #2~#4 의 참조로 사용 → 톤 일관성 ↑ |
| **Midjourney v6** | `--ar 5:7 --style raw --seed 12345` 고정. 첫 카드 결과의 `--seed` 를 나머지 3장에도 적용 |
| **Imagen 3 (Vertex AI)** | `aspectRatio="9:16"` (가장 가까운 비율) 후 5:7 crop. 같은 prompt prefix + suit 만 변경 |
| **Stable Diffusion XL** | seed 고정 + prompt prefix 동일. CFG=7, steps=30 권장 |

### 검토 기준

생성된 4장을 나란히 비교했을 때:
1. 4장의 흰 배경 톤이 동일한가 (어느 하나만 그레이 끼지 않았는가)
2. 슈트 심볼이 정확히 같은 크기/위치인가
3. 코너 "A" 폰트 두께/크기가 일관되는가
4. 모서리 둥글기/그림자 정도가 같은가

위 4개가 OK 면 마스터 prompt 의 slot 만 바꿔 나머지 52장 (rank 2~10, J/Q/K)
+ 특수 4종 + 캐릭터 4종 + 보드 1장 생성으로 확장.

NG 항목이 있으면 prompt 의 부족한 부분을 강화 (예: "consistent off-white
background #fefefe") 후 PoC 재생성.

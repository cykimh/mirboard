# 요트 "미드나잇 살롱" — 출처·라이선스 (D-203)

**외부 자원 0건.** 이 테마의 그림·질감·연출은 전부 이 저장소 안에서 코드로 만들었다.

| 무엇 | 어떻게 만들었나 | 파일 |
| --- | --- | --- |
| 상아 주사위(큰 것·작은 것·굴러오는 정육면체) | 직접 그린 인라인 SVG + CSS 3D 변환 | `client/src/features/yacht/YachtDie.tsx` |
| 펠트 결·마호가니 레일·황동·장부 | CSS 그라데이션, 인라인 SVG `feTurbulence` 노이즈(data URI) | `client/src/styles/parts/20-yacht-table.css` |
| 금가루 폭발·칩이 꽂히는 불티 | 직접 짠 Canvas 2D 입자(라이브러리 없음) | `client/src/features/yacht/GoldBurst.tsx` |
| 빛줄기·섬광·현수막·칩 궤적·조명 이동 | CSS `@keyframes`(transform·opacity) | `20-yacht-table.css` |
| 글꼴 | 웹 글꼴 없음 — 기기에 있는 명조 계열 스택(없으면 기본 글꼴) | `--yt-s-serif` |

- npm 의존성 추가 없음(번들에 새 라이브러리 0).
- 이미지·소리 파일 추가 없음.
- 다른 게임·앱의 그림·캐릭터·상표를 쓰지 않았다(D-185 "독창").

나중에 외부 에셋(CC0/CC-BY)이나 오픈소스 라이브러리를 넣게 되면 이 표에 이름·출처 URL·라이선스·저작자 표기를 한 줄씩 추가한다.

## 2차 (D-204)

| 무엇 | 어떻게 만들었나 | 파일 |
| --- | --- | --- |
| 가죽 주사위 컵 | 직접 그린 인라인 SVG | `client/src/features/yacht/DiceTray.tsx` (`DiceCup`) |
| 샴페인 잔 · 금 리본 | 직접 그린 인라인 SVG | `client/src/features/yacht/YachtTable.tsx` (`Coupe` · `Ribbon`) |
| 기포 · 금 띠 조각 · 착지 고리 · 잠김 빛 · 순위 뒤집기 | CSS `@keyframes` | `20-yacht-table.css` |

외부 자원·그림 파일·npm 의존성 추가는 이번에도 0건이다.

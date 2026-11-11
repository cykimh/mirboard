# 씨앗포 그림 출처 (D-252)

clay 스킨의 그림·연출은 **전부 이 저장소의 코드로 새로 그린 것**이다 — 외부 그림·글꼴·효과 라이브러리를 가져오지 않았다.

| 무엇 | 어디서 |
|---|---|
| 시트(`client/public/artillery/skins/clay/atlas.png`) | `scripts/artillery-clay/art.js`(Canvas 2D 도형)를 `scripts/generate-artillery-clay.mjs` 로 구움 |
| 하늘·흙·입자·이름표·바람개비·축포 | `client/src/features/artillery/skins/claySkin.ts` |
| 생성 도구 | Playwright(`@playwright/test`, Apache-2.0) — 이미 있던 개발 의존성, 빌드 결과물에 들어가지 않는다 |
| 글꼴 | 기기에 있는 둥근 글꼴 이름만 지정(`Arial Rounded MT Bold` → 시스템 글꼴), 내려받는 글꼴 없음 |

다른 게임·작품의 캐릭터·그림·상표는 쓰지 않았다. 외부 자원을 들이게 되면 이 표에 출처·라이선스를 적는다.

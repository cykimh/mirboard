# Tichu 카드 이미지 자산

M1/A1 (D-40 / D-45) — `CardChip` 이 이 자산을 `<img>` 로 렌더하고, 로드 실패 시
onError 로 텍스트 글리프 모드(문양 + 랭크/이모지)로 폴백한다
(`client/src/features/tichu/CardChip.tsx` 의 `imageFailed` 분기).

## 명명 규칙 (현행: `.svg`)

> 경로는 `client/src/types/tichu.ts` 의 `cardAssetSrc()` 가 생성한다(정본).
> (구 README 는 `.webp` 로 적혀 있었으나 실제 자산/코드는 `.svg`.)

- 일반 카드: `{suit}-{rank}.svg`
  - 슈트: `jade`, `sword`, `pagoda`, `star`
  - 랭크: `2`~`10`, `J`, `Q`, `K`, `A` (예: `jade-2.svg`, `pagoda-A.svg`)
- 특수 카드: `mahjong.svg`, `dog.svg`, `phoenix.svg`, `dragon.svg`
- 뒷면: `back.svg`

총 56장 + back 1장. (일반 52 + 특수 4 + back)

특수카드 4종은 M1/A1 에서 250×350 흰 카드 포맷으로 신규 작성(단순 엠블럼).
추후 고해상도 일러스트로 교체 가능 — `cardAssetSrc` 명명 규칙만 유지하면 무코드 교체.
생성 가이드: [`docs/assets/card-prompts.md`](../../../docs/assets/card-prompts.md)

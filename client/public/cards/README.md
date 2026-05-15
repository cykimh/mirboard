# Tichu 카드 이미지 자산

Phase 8F (D-40 / D-45) — AI 생성 이미지를 여기에 두면 `CardChip` 이 텍스트 글리프
대신 이미지를 렌더한다. 자산 부재 시에는 onError fallback 으로 기존 텍스트 모드
유지 (`client/src/features/tichu/CardChip.tsx` 의 `useState<failed>` 분기).

## 명명 규칙

- 일반 카드: `{suit}-{rank}.webp`
  - 슈트: `jade`, `sword`, `pagoda`, `star`
  - 랭크: `2`~`10`, `J`, `Q`, `K`, `A` (예: `jade-2.webp`, `pagoda-A.webp`)
- 특수 카드: `mahjong.webp`, `dog.webp`, `phoenix.webp`, `dragon.webp`
- 뒷면: `back.webp`

총 56장 + back 1장.

생성 가이드: [`docs/assets/card-prompts.md`](../../../docs/assets/card-prompts.md)

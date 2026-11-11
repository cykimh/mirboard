# 오목 "결" 테마 — 출처·라이선스 (D-192)

외부 자원을 쓰지 않았다. 판·돌·연출은 모두 이 저장소에서 직접 만든 SVG·CSS 다.

| 조각 | 만든 방법 | 위치 |
|---|---|---|
| 나뭇결 | 씨앗 고정 난수로 그린 SVG 경로 3개를 data URI 로(필터·사진 없음, 약 2.7KB) | `client/src/styles/parts/21-omok-table.css` 의 `--om-grain` |
| 흑석·조개 백돌·그림자·빛·잉크 | SVG 그라데이션 + 경로 | `client/src/features/omok/OmokArt.tsx` |
| 연출(내려앉기·파문·먼지·잉크 번짐·금 상감·금가루·현판·놋쇠 고리·등불) | CSS keyframes(transform·opacity) + SVG 원·선 | 같은 CSS, `OmokBoard.tsx` |
| 글꼴 | 시스템 글꼴만(명조 계열 대체 목록) | — |

npm 의존성 추가 없음. 다른 게임·앱의 그림·상표를 가져오지 않았다. 나중에 사진 질감이나 라이브러리를 넣으면 이 표에 이름·출처·라이선스를 적는다.

# 원카드 "네온 파티" 그림 출처 (D-260)

외부 소재는 **없다**. 그림은 전부 이 저장소에서 직접 만든 SVG·CSS 다.

| 그림 | 위치 | 만든 방식 |
|---|---|---|
| 무늬 넷(♠♥♦♣) | `client/src/features/onecard/OneCardFace.tsx` `SUIT_PATH` | 직접 그린 SVG 경로(24×24) |
| 숫자 카드·A(폭죽 살)·7(도는 화살) | `OneCardFace.tsx` | 직접 그린 SVG(62×88) |
| J(고깔모자 파티꾼)·Q(티아라 여왕)·K(왕관·콧수염 임금) | `OneCardFace.tsx` | 직접 그린 선 그림 |
| 조커 둘(방울 모자 어릿광대 — 흑백·컬러) | `OneCardFace.tsx` `JokerArt` | 직접 그린 SVG, 색만 CSS 로 가른다 |
| 카드 뒷면(마름모 네온 격자) | `client/src/styles/parts/19-onecard-table.css` | CSS 그라데이션 |
| 건너뛰기·방향 바뀜·한 번 더 아이콘 | `client/src/features/onecard/OneCardFx.tsx` `SpecialIcon` | 직접 그린 SVG |
| 불꽃·충격·폭죽·색종이·파티 조명 | `19-onecard-table.css` 의 `.oc-fx*`·`.oc-party*` | CSS(그림 파일 없음) |

글꼴은 페이지 글꼴을 그대로 쓴다(카드 숫자는 SVG `<text>`). 그림 파일(png·jpg·woff)은 추가하지 않았다 — 늘어난 용량은 코드뿐이다.

`shots/` 는 실제 판 확인 화면이다(로컬, 봇과 6인). 연출 장면 일부는 순간을 잡으려고 조각을 멈춰 찍어 여러 조각이 겹쳐 보인다.

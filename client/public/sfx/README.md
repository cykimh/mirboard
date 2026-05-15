# Tichu 효과음 자산

Phase 8G (D-47) — BOMB / STRAIGHT_FLUSH_BOMB 플레이 시 재생되는 mp3 효과음.
자산 부재 시 `useSfx.play()` 가 silent (오디오 로드 실패는 무시).

## 파일

- `bomb.mp3` — BOMB (4 of a kind) 플레이 시. 폭발/타격 짧은 효과음 (~1s).
- `straight-flush.mp3` — STRAIGHT_FLUSH_BOMB 플레이 시. 더 화려한 효과음 (~1.5s).

## 라이선스

저작권 위반 없도록 CC0 / royalty-free 소스 추천:
- https://freesound.org/ (CC0 필터)
- https://mixkit.co/free-sound-effects/

볼륨은 코드에서 0.6 으로 attenuate. 원본은 normalize 된 상태 권장.

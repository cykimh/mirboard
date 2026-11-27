# 낱말 온도 데이터 도구 (S1a)

게임 "낱말 온도"(`WORD_TEMP`)의 **정답 은행**과 **유사도 표**를 만드는 재현 기록입니다.
설계 `docs/plans/wordtemp.md` §5 · 룰 `docs/rules-wordtemp.md` §5~§7 · 결정 D-312(채점 방식)·D-313(표 v1)·**D-322(표 v2 — 기준문 `score-v3`, 지금 실린 표)**.

> 1~3·5b 단계는 표 v1 때 돌린 그대로이고(캐시에 있어 다시 묻지 않는다), 표 v2 는 3b·4·5·6 단계를 새로 돌렸다.
> v1 의 채점 스크립트(100개 묶음·`score-v1`)는 git 이력에, 그 결과는 `cache/score.jsonl`·`data/scores-v1.tsv.gz` 에 남아 있다.

- 모델 `claude-sonnet-5-5` · `output_config.effort: low` · 구조화 출력(`output_config.format`) · 큰 일은 Message Batches(반값).
- 낱말 목록은 전부 **직접 만든 것**(Claude 생성 + 거름)입니다. 사전 데이터를 싣지 않습니다.
- 서버 Java 코드는 여기 없습니다. 실린 파일을 읽는 것은 S2 의 일입니다.

## 준비

```bash
cd scripts/wordtemp-data
npm install                      # @anthropic-ai/sdk 1.14.0 (Node 20 이상, 만들 때 v26.0.0)
export ANTHROPIC_API_KEY=...     # 없으면 ~/.config/mirboard/anthropic.key 를 읽는다. 키는 찍지도 적지도 않는다
```

`node_modules/` 는 저장소에 없습니다(루트 `.gitignore`).

## 순서

| # | 명령 | 하는 일 | 읽는 것 → 쓰는 것 | Claude |
| --- | --- | --- | --- | --- |
| 1 | `node 01-bank.mjs` | 범주별 정답 초안(낱말·뜻풀이·같은 말) → 다른 프롬프트로 검토(동음이의어·고유명사·욕설·드문 말) → 범주당 24개까지 | `data/categories.json`·`bank-exclude.txt`·`bank-alias-exclude.txt` → `data/bank-draft.tsv`(판정·까닭 포함)·`data/bank.tsv` | 바로 30건 |
| 2 | `node 02-vocab.mjs` | 힌트 어휘: 범주 15개 + 흔한 명사 주제 10개 | → `data/vocab/*.txt` | 바로 25건 |
| 3 | `node 03-candidates.mjs --pilot 6` 뒤 `node 03-candidates.mjs` | 정답마다 낼 법한 힌트 명사(가까움·강함·느슨함) | `bank.tsv` → `data/candidates.tsv` | 배치 정답당 1건 |
| 3b | `node 03b-creative.mjs --pilot 5` 뒤 `node 03b-creative.mjs` | (D-322) 정답마다 **창의적 연상·고유명사** 힌트 약 45개 — 인물·캐릭터·작품·상표·지명·속담·비유·감각 | `bank.tsv` → `data/candidates-creative.tsv` | 배치 정답당 1건 |
| 4 | `node 04-score.mjs --pass A --pilot 10` → `--pass A` → `--pass B` → `--pass C --pilot 6` → `--pass C` → `--merge` | **채점**(D-322) — 정답 1 × 힌트 20개 묶음, 기준문 `prompts/score-v3.md`. A·B = 모든 쌍을 서로 다르게 섞어 두 번, C = 벌어졌거나(10 초과)·판정이 갈렸거나·평균 70 이상인 쌍을 10개 묶음으로 한 번 더. 확정 = 가운데값(셋) 또는 평균(둘) | v1 때 채점한 낱말(`data/scores-v1.tsv.gz`) + 3b 후보 − 비하 표현·명사 아님 → `cache/score-v3{a,b,c}.jsonl`·`data/scores-v3.tsv.gz`(확정·A·B·C) | 배치 약 5,700 × 2 + 2,300건 |
| 5 | `node 05-consistency.mjs` | 일관성 — A·B 두 번의 차이를 전 쌍에서 잰다 | `data/scores-v3.tsv.gz` → `data/consistency.txt` | 없음 |
| 5b | `node 05b-classify.mjs --pilot 3` 뒤 `node 05b-classify.mjs` | **어휘 청소**(v1 때 한 번 — 표 v2 는 그 결과 가운데 비하 표현·명사 아님만 쓴다) — 낱말 하나씩 갈라 고유명사·비하 표현·명사 아님을 찾는다(200개 묶음) | `data/scores-v1.tsv.gz`·`flagged-add.txt`·`flagged-keep.txt` → `data/flagged.tsv`·`data/blocked-derogatory.txt` | 배치 58건 |
| 6 | `node 06-build.mjs` | 은행 기준으로 걸러 서버 자원으로 묶기(5b 가 뺀 낱말·금칙어 제외) | `data/scores-v3.tsv.gz`·`data/bank.tsv`·`data/flagged.tsv` → `server/src/main/resources/wordtemp/*`·`data/stats.txt`·`data/bank-dropped.tsv`·`data/vocab-clean.txt`·`data/top-neighbors.txt`(정답마다 위쪽 25개) | 없음 |
| 7 | `node 07-review-sample.mjs` | 사람 검수 표본(띠별 150쌍) + 96 이상 전부 | 실린 표 → `review/*.csv` | 없음 |
| 8 | `node validate.mjs` | **실린 파일만** 읽어 불변식 확인·통계(의존성 없음) | `server/src/main/resources/wordtemp/*` | 없음 |

만든 날을 고정하려면 `WORDTEMP_CREATED=2026-11-27 node 06-build.mjs`.

## 다시 돌려도 다시 묻지 않는다

- 요청마다 `id + 내용 해시` 로 `cache/<단계>.jsonl` 에 응답을 쌓습니다(저장소에 실음). 같은 요청은 캐시에서 읽고, 프롬프트나 입력이 바뀐 요청만 다시 묻습니다.
- 배치를 보내면 곧바로 `cache/<단계>.pending.json` 에 배치 id 를 적습니다. 도중에 끊겨도 다시 돌리면 새로 보내지 않고 이어서 기다립니다(이 파일은 저장소에 싣지 않음).
- 배치 결과는 순서가 보장되지 않아 `custom_id` 로 맞춥니다. 형식이 어긋난 응답(번호 빠짐·범위 밖)은 버리고 다음 바퀴(최대 3)에 그 건만 다시 묻습니다.
- 6~8단계는 Claude 를 부르지 않습니다. **정답을 빼거나 같은 말을 빼는 것**은 1 → 6 → 8 만 다시 돌리면 됩니다.
  정답을 **더하거나** 후보를 늘리면 3·4단계가 새 요청만 묻습니다(4단계는 묶음이 다시 섞여 그 범주의 요청이 새로 나갈 수 있음 — 먼저 `--pilot` 어림을 볼 것).

## 예산

- `lib.mjs` 가 응답의 `usage`(프롬프트 캐시 쓰기·읽기 포함)로 실제 비용을 장부에 적고, 보내기 전 어림(시험 실측 평균 × 남은 건수 × 1.15)과 더해 상한(`WORDTEMP_BUDGET_USD`, 기본 50)을 넘으면 **보내지 않고 멈춥니다.**
- 장부는 일감마다 따로입니다: `cache/spend.json` = 표 v1(실제 $9.24), `cache/spend-v3.json` = 표 v2(실제 $12.06). 다른 장부를 쓰려면 `WORDTEMP_LEDGER`.
- 채점(4단계)은 같은 기준문을 수천 번 보내므로 시스템 프롬프트를 **프롬프트 캐시**에 올립니다(`buildParams({cacheSystem: true})`). 처음 300건으로 실제 적중률·비용을 본 뒤 나머지를 보냅니다 — v2 는 적중률이 거의 100% 라 요청당 약 $0.0009 였습니다(캐시가 안 맞으면 네 배쯤 듭니다).
- 단가는 `lib.mjs` 의 `PRICE`(Sonnet 5.5 입력 $2 · 출력 $10 / 100만 토큰, 배치 절반). 모델을 바꾸면 같이 고칠 것.

## 프롬프트 버전

| 파일 | 버전 | 쓰는 곳 |
| --- | --- | --- |
| `prompts/score-v3.md` | `score-v3` | 4단계의 시스템 프롬프트 앞부분, 그리고 **실시간 채점** — 6단계가 `server/src/main/resources/wordtemp/score-v3.md` 로 복사한다. 바꾸면 버전을 올린다(표 머리 줄·실시간 캐시 키·`mirboard.wordtemp.live-scoring.prompt-version`) |
| `prompts/score-v1.md` | `score-v1` | 표 v1 을 만든 기준문(기록) |
| `prompts/score-v2.md` | `score-v2` | D-317~D-321 의 실시간 채점 기준문(고유명사 금지) — 기록. D-322 로 `score-v3` 가 대신한다 |
| 묶음 입출력 껍데기 | `04-score.mjs` 의 `BATCH_IO` | 표 만들기에만(여러 힌트를 번호로 받아 `[번호, 온도]` 로 돌려줌, 쓸 수 없는 힌트 = -1) |
| 은행 초안·검토·어휘·후보 | 각 스크립트의 `*_SYSTEM` 상수 | 그 단계에만. 바꾸면 요청 해시가 달라져 그 단계가 새로 나간다 |

## 실리는 파일 (`server/src/main/resources/wordtemp/`)

| 파일 | 형식 |
| --- | --- |
| `table-v2.tsv.gz` | gzip 텍스트. 첫 줄 `#wordtemp-table⇥version=v2⇥promptVersion=score-v3⇥model=…⇥created=…⇥answers=N⇥pairs=M`. 그다음 정답 한 줄씩 `정답⇥힌트:온도,힌트:온도,…` |
| `bank.tsv` | `낱말⇥범주⇥뜻풀이⇥같은 말(쉼표)` — 표의 정답과 1:1 |
| `blocked.txt` | 금칙어(부분 일치), 한 줄에 하나 — 손으로 쓴 욕설 목록 + 5b 가 찾은 비하 표현(멀쩡한 낱말의 일부가 되는 것은 제외: 거지 ⊂ 설거지) |
| `score-v3.md` | 채점 기준문(`score-v1`·`score-v2` 는 예전 실시간 캐시 키의 기록으로 남겨 둠) |

- **정렬**: 정답 줄도, 줄 안의 힌트도 **UTF-16 코드 단위 오름차순**(Java `String.compareTo` 와 같은 순서), 중복 없음.
  서버는 줄을 읽으며 힌트를 정렬된 어휘 `String[]` 의 인덱스로 바꾸면 정답마다 `int[]`(이미 오름차순) + `byte[]` 가 됩니다(설계서 §5.4).
- 온도는 정수 0~100. **표에 없는 쌍 = 모름**(낮은 점수가 아님 — 실시간 채점 또는 거절, S2).
- 쓸 수 없다고 판정된 낱말(명사 아님·비하 표현 — 두세 번 채점의 과반)은 표에 넣지 않았습니다(실시간 채점이 다시 판정).
- **고유명사(사람·캐릭터·상표·작품·지명)는 D-322 부터 표에 있습니다.** 5b 단계가 찾은 것 가운데 비하 표현·명사 아님(`data/flagged.tsv` 의 그 두 갈래)만 계속 뺍니다.
  잘못 걸린 낱말은 `data/flagged-keep.txt`, 놓친 낱말은 `data/flagged-add.txt` 에 적고 5b → 6 → 8.
- 표의 힌트는 룰 §5.2 를 통과한 것만: 정답·같은 말과 같거나 포함하지 않고, 2자 이상이면 정답·같은 말 안에 들어 있지 않음.

## 결과

`data/stats.txt` · `data/consistency.txt` · `data/top-neighbors.txt` · `node validate.mjs` 출력이 정본입니다.
표 v2 요약은 `docs/decisions.md` D-322, 표 v1 은 D-313.

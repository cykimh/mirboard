#!/usr/bin/env node
/**
 * D-94 — CSS 분할 무손실 검증.
 *
 * `styles/index.css` 의 `./parts/*` @import 를 선언 순서대로 이어붙인 결과가
 * 기준 파일과 **바이트 동일**한지 확인한다. 분할이 "순수 이동"임을 논증이 아니라
 * 기계로 증명하기 위한 것 — 이게 성립하면 캐스케이드 보존은 동어반복이 된다.
 *
 * 사용법:
 *   node client/scripts/verify-css-split.cjs [기준파일]
 *   기준파일 기본값: /tmp/styles-presplit.css (분할 직전 스냅샷)
 *
 * CI/회귀용으로는 기준 파일 대신 parts 를 이어붙인 해시를 고정하는 편이 낫지만,
 * 지금 필요한 건 "이번 분할이 무손실인가" 라는 일회성 증명이다.
 */
const fs = require('fs');
const path = require('path');

const CLIENT_SRC = path.resolve(__dirname, '..', 'src');
const INDEX = path.join(CLIENT_SRC, 'styles', 'index.css');
const baselinePath = process.argv[2] || '/tmp/styles-presplit.css';

if (!fs.existsSync(baselinePath)) {
  console.error(`기준 파일이 없습니다: ${baselinePath}`);
  console.error('분할 직전 styles.css 사본을 인자로 넘기세요.');
  process.exit(2);
}

const index = fs.readFileSync(INDEX, 'utf8');
// './parts/xx.css' 만 순서대로 뽑는다(tailwind/tokens/theme 은 분할 대상이 아님).
const partImports = [...index.matchAll(/@import\s+'\.\/(parts\/[^']+)'/g)].map((m) => m[1]);

if (partImports.length === 0) {
  console.error('index.css 에서 parts @import 를 찾지 못했습니다.');
  process.exit(2);
}

const joined = partImports
  .map((rel) => fs.readFileSync(path.join(CLIENT_SRC, 'styles', rel), 'utf8'))
  .join('\n');

const baseline = fs.readFileSync(baselinePath, 'utf8');

if (joined === baseline) {
  console.log(`✅ 바이트 동일 — parts ${partImports.length}개 concat == ${path.basename(baselinePath)}`);
  console.log(`   ${joined.length}B / ${joined.split('\n').length}줄`);
  process.exit(0);
}

console.error('❌ 불일치 — 분할이 무손실이 아닙니다.');
console.error(`   concat  : ${joined.length}B / ${joined.split('\n').length}줄`);
console.error(`   baseline: ${baseline.length}B / ${baseline.split('\n').length}줄`);

const a = joined.split('\n');
const b = baseline.split('\n');
for (let i = 0; i < Math.max(a.length, b.length); i++) {
  if (a[i] !== b[i]) {
    console.error(`   첫 불일치 ${i + 1}행:`);
    console.error(`     concat  : ${JSON.stringify(a[i])}`);
    console.error(`     baseline: ${JSON.stringify(b[i])}`);
    break;
  }
}
process.exit(1);

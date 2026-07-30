// Mirboard REST 스모크 부하 (M2-C5 — D-92)
//
// 목적: "정상 사용 수준에서 서버가 멀쩡한가"를 짧게 확인하는 회귀 가드.
// 최대 처리량을 재는 벤치마크가 아니다 — 그건 실제 운영 트래픽을 본 뒤에.
//
// 시나리오: setup 에서 VU 수만큼 계정을 만들어 토큰을 받아두고, 각 VU 는 그 토큰으로
// 카탈로그/방목록/랭킹을 조회한다.
//
// ⚠️ 토큰은 반드시 setup 에서 한 번만 받는다. 매 반복 로그인하면 D-90 의 `auth`
// 버킷(IP 키, 20/분)에 걸려 부하가 아니라 레이트리밋을 측정하게 된다 — 초기 버전이
// 실제로 그랬고(429 48%), 실 클라이언트도 12h JWT 를 재사용하지 매초 재발급하지 않는다.
//
// 실행: ./scripts/check.sh load
import http from 'k6/http';
import { check, sleep, fail } from 'k6';

const BASE = __ENV.MIRBOARD_BASE_URL || 'http://localhost:8080';
const VUS = Number(__ENV.MIRBOARD_LOAD_VUS || 5);
const TAG = __ENV.K6_RUN_TAG || '';
const JSON_HEADERS = { 'Content-Type': 'application/json' };

export const options = {
  scenarios: {
    browse: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: VUS },
        { duration: '20s', target: VUS },
        { duration: '5s', target: 0 },
      ],
      gracefulRampDown: '5s',
    },
  },
  thresholds: {
    // 임계값은 추측이 아니라 로컬 실측에서 역산했다 — 5 VU 기준 p95 ≈ 37ms, 실패 0건.
    // 8배 남짓 여유를 둬 노트북 부하로는 안 깨지되, 회귀는 잡히게 한다.
    // 여기가 빨개지면 "조금 느려졌다"가 아니라 "뭔가 깨졌다"로 읽는다.
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<300'],
    checks: ['rate>0.99'],
  },
};

/**
 * VU 수만큼 계정을 만들고 토큰을 받아 각 VU 에 나눠준다.
 * setup 은 1회만 실행되므로 auth 버킷 소비가 VUS×2 회로 끝난다.
 */
export function setup() {
  // 인증 없이 401 이 나와야 정상 — 200 이면 인증이 열린 것이라 즉시 중단.
  // 401 을 기대 응답으로 선언해야 http_req_failed 가 부풀지 않는다(의도된 4xx).
  const probe = http.get(`${BASE}/api/games`, {
    responseCallback: http.expectedStatuses(401),
    tags: { name: 'setup_probe' },
  });
  if (probe.status !== 401) {
    fail(`서버 상태 이상: GET /api/games 가 401 이어야 하는데 ${probe.status} (BASE=${BASE})`);
  }

  const tokens = [];
  for (let i = 1; i <= VUS; i++) {
    const creds = { username: `k6u${i}${TAG}`.slice(0, 18), password: 'k6loadtest1' };
    // 이미 있으면 409 — 재실행을 위해 정상으로 취급한다.
    const reg = http.post(`${BASE}/api/auth/register`, JSON.stringify(creds), {
      headers: JSON_HEADERS,
      // 재실행 시 409(이미 존재)는 정상 — 기대 응답으로 선언해 지표를 왜곡하지 않는다.
      responseCallback: http.expectedStatuses(201, 409),
      tags: { name: 'setup_register' },
    });
    if (![201, 409].includes(reg.status)) {
      fail(`setup 가입 실패: ${reg.status} ${reg.body}`);
    }
    const login = http.post(`${BASE}/api/auth/login`, JSON.stringify(creds), {
      headers: JSON_HEADERS,
      tags: { name: 'setup_login' },
    });
    if (login.status !== 200) {
      fail(`setup 로그인 실패: ${login.status} ${login.body} — auth 레이트리밋(20/분)일 수 있다`);
    }
    tokens.push(login.json('accessToken'));
  }
  return { tokens };
}

export default function (data) {
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  const auth = { headers: { Authorization: `Bearer ${token}` } };

  const games = http.get(`${BASE}/api/games`, { ...auth, tags: { name: 'games' } });
  check(games, { 'games 200': (r) => r.status === 200 });

  const rooms = http.get(`${BASE}/api/rooms`, { ...auth, tags: { name: 'rooms' } });
  check(rooms, { 'rooms 200': (r) => r.status === 200 });

  const ranking = http.get(`${BASE}/api/users/ranking?limit=20`, {
    ...auth,
    tags: { name: 'ranking' },
  });
  check(ranking, { 'ranking 200': (r) => r.status === 200 });

  sleep(1);
}

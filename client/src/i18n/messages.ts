/**
 * 사용자에게 노출되는 모든 문자열을 한 곳에 모은다 (Phase 5e). 향후 다국어 토글이
 * 필요해지면 본 객체와 동일한 키를 갖는 EN/JA 사전을 추가하고, currentLocale 에 따라
 * 해당 사전을 가리키도록 `messages` 를 분기하면 된다.
 *
 * 키 이름 규칙: `<area>.<element>` — 예: `game.action.play`. 화면 별로 묶지 말고
 * 의미 단위로 묶어 재사용을 유도한다.
 */
const KO = {
  // --- 공통 ---
  'common.loading': '로드 중...',
  'common.cancel': '취소',
  'common.close': '닫기',

  // --- 인증 ---
  'auth.login.title': '로그인',
  'auth.register.title': '회원가입',
  'auth.username': '아이디',
  'auth.password': '비밀번호',

  // --- 허브 / 로비 / 방 ---
  'hub.title': '게임 허브',
  'hub.logout': '로그아웃',
  'lobby.title': '대기실',
  'lobby.create': '방 만들기',
  'lobby.empty': '대기 중인 방이 없습니다.',
  'room.leave': '나가기',
  'room.waiting.title': '참가자',
  'room.waiting.hint': '4/4 모이면 자동으로 게임이 시작됩니다.',
  'room.finished': '게임이 종료되었습니다.',
  'room.loading': '방 정보 불러오는 중...',

  // --- 게임 헤더 / 상태 ---
  'game.header.stomp': 'STOMP',
  'game.header.mySeat': '내 시트',
  'game.header.round': '라운드',
  'game.header.matchScore': '누적',
  'game.header.currentTurn': '현재 차례',
  'game.header.activeWish': '활성 소원',
  'game.phase.dealing': '분배 단계',
  'game.phase.passing': '카드 패스',
  'game.phase.playing': '플레이',
  'game.phase.roundEnd': '라운드 종료',

  // --- 좌석 표시 ---
  'seat.handCount': '손패',
  'seat.handCardsSuffix': '장',
  'seat.ready': '대기 완료',
  'seat.submitted': '제출 완료',

  // --- 트릭 / 손패 ---
  'trick.title': '현재 트릭',
  'trick.leadWaiting': '(리드 대기)',
  'hand.title': '내 손패',
  'hand.loading': '(손패 로드 중...)',
  'hand.sort.byRank': '랭크순 정렬',
  'hand.sort.restore': '원본 순서로',

  // --- 패스 픽커 ---
  'pass.picker.title': '패스 카드 선택',
  'pass.slot.left': '왼쪽',
  'pass.slot.partner': '파트너',
  'pass.slot.right': '오른쪽',
  'pass.slot.empty': '(미선택)',
  'pass.submit': '패스 제출',
  'pass.clear': '선택 초기화',
  'pass.waiting': '다른 좌석의 패스 제출을 대기 중...',

  // --- Dealing 액션 ---
  'dealing.declareGrand': 'Grand Tichu 선언 (+200/-200)',
  'dealing.declareTichu': 'Tichu 선언 (+100/-100)',
  'dealing.skip.noDeclare': '선언 안 함 — 다음으로',
  'dealing.skip.declared': '확인 — 다음으로',
  'dealing.waiting': '다른 좌석을 대기 중...',

  // --- Playing 액션 ---
  'play.action.play': '내기',
  'play.action.pass': '패스',
  'play.action.declareTichu': '티츄 선언',
  'play.action.clearSelection': '선택 해제',
  'play.error.pickCard': '카드를 한 장 이상 선택하세요',
  'play.error.passSlots': '3장(왼쪽/파트너/오른쪽) 모두 선택해야 합니다',

  // --- 라운드/매치 종료 ---
  'round.ended.title': '라운드 종료',
  'round.ended.firstFinisher': '첫 완주: 시트',
  'round.ended.continue': '다음 라운드로',
  'match.ended.titleSuffix': '승리',
  'match.ended.finalScore': '최종 누적',
  'match.ended.roundsPlayed': '라운드 진행',
} as const;

export type MessageKey = keyof typeof KO;

/** 사용자 노출 문자열 단일 조회. 미정의 키는 런타임에 키 자체를 반환 (debug 가시성). */
export function t(key: MessageKey): string {
  return KO[key] ?? key;
}

/** 다국어 토글 확장 시 진입점 — 현재는 KO 고정. */
export function setLocale(_: 'ko' | 'en'): void {
  // Phase 5e+ : 사전 추가 시 분기.
}

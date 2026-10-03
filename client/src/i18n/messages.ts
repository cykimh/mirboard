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
  'hub.title': '미르보드카페',
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
  'game.header.round': '라운드',
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
  'play.action.clearSelection': '취소',
  'play.error.pickCard': '카드를 한 장 이상 선택하세요',
  'play.error.passSlots': '3장(왼쪽/파트너/오른쪽) 모두 선택해야 합니다',

  // --- Mahjong 소원 모달 ---
  'wish.title': '마작을 냅니다 — 소원을 지정할까요?',
  'wish.body': '지정한 랭크를 다른 플레이어가 가능한 한 포함하도록 강제합니다. 소원은 마작을 내는 순간 함께 정해집니다.',
  'wish.skip': '소원 없이 내기',
  'wish.confirm': '소원 지정하고 내기',

  // --- Dragon 트릭 양도 모달 ---
  'dragon.title': 'Dragon 트릭 양도',
  'dragon.body': 'Dragon 으로 이긴 트릭은 상대 팀 두 좌석 중 한 명에게 양도해야 합니다.',
  'dragon.confirm': '양도',
  'dragon.giveTo': '시트',

  // --- Phoenix 단독 SINGLE 표식 ---
  'phoenix.singleBadge': 'Phoenix +0.5',
  'phoenix.singleTooltip': 'Phoenix 단독 SINGLE — 현재 트릭 최강 +0.5. Dragon 만 못 이김.',

  // --- 라운드/매치 종료 ---
  'round.ended.title': '라운드 종료',
  'round.ended.firstFinisher': '첫 완주: 시트',
  'round.ended.continue': '다음 라운드로',
  'match.ended.titleSuffix': '승리',
  'match.ended.finalScore': '최종 누적',
  'match.ended.roundsPlayed': '라운드 진행',

  // --- 게스트 체험 (D-117) ---
  'auth.guest.or': '또는',
  'auth.guest.cta': '게스트로 바로 체험하기',
  'auth.guest.creating': '입장 중...',
  'auth.guest.caption': '가입 없이 12시간 · 전적은 랭킹에 오르지 않아요',
  'auth.guest.error.rateLimited':
    '이 네트워크에서 오늘 만들 수 있는 게스트 수를 다 썼어요. 회원가입하면 바로 이어서 즐길 수 있어요.',
  'auth.guest.error.unavailable':
    '지금은 게스트 입장이 어려워요. 회원가입하면 바로 이용할 수 있어요.',
  'auth.guest.signupCta': '회원가입하고 시작하기',
  'hub.guest.badge': '게스트',
  'hub.guest.avatarDisabled': '게스트는 아바타를 바꿀 수 없어요 — 회원가입하면 쓸 수 있어요',
  'hub.guest.logoutConfirm':
    '게스트 계정은 로그아웃하면 다시 들어올 수 없어요. 로그아웃할까요?',
  'profile.guest.noPassword': '게스트 계정은 비밀번호가 없어요 — 회원가입하면 전적이 남아요',
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

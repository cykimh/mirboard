/** 새로고침 표시의 sessionStorage 키 접두. 조각마다 따로 둔다. */
export const RELOAD_FLAG_PREFIX = 'mirboard:chunk-reload:';

/**
 * 지연 로딩 조각을 받는다. 배포하면 예전 해시의 조각 파일이 사라져, 배포 전에 연 탭은 조각을 못 받는다 — 처음 실패하면 표시를 남기고
 * 한 번만 새로고침해 새 `index.html` 을 받게 한다(돌려주는 약속은 끝나지 않는다 — 곧 페이지가 바뀐다). 표시가 이미 있으면(새로고침해도
 * 없다 — 진짜 장애) 오류를 그대로 던진다. 저장소를 못 쓰면 새로고침하지 않는다(표시를 못 남기면 무한 새로고침이 된다).
 */
export function loadWithReload<T>(
  name: string,
  load: () => Promise<T>,
  reload: () => void = () => window.location.reload(),
): Promise<T> {
  const key = RELOAD_FLAG_PREFIX + name;
  return load().then(
    (loaded) => {
      try {
        sessionStorage.removeItem(key);
      } catch {
        // 저장소를 못 써도 받은 조각은 그대로 쓴다.
      }
      return loaded;
    },
    (error: unknown) => {
      try {
        if (sessionStorage.getItem(key) === null) {
          sessionStorage.setItem(key, '1');
          reload();
          return new Promise<T>(() => {});
        }
      } catch {
        // 저장소를 못 쓴다 — 새로고침하지 않고 아래에서 던진다.
      }
      throw error;
    },
  );
}

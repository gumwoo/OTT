const KEY = 'stillcut.devtools';

// ?devtools=1로 켜고 ?devtools=0으로 끈다. 일반 사용자 화면에는 진단 도구가 보이지 않는다
export function syncDevModeFromUrl(search: string): void {
  const value = new URLSearchParams(search).get('devtools');
  try {
    if (value === '1') localStorage.setItem(KEY, '1');
    if (value === '0') localStorage.removeItem(KEY);
  } catch {
    // 저장소를 막은 브라우저에서는 개발자 모드를 쓰지 않는다
  }
}

export function isDevMode(): boolean {
  try {
    return localStorage.getItem(KEY) === '1';
  } catch {
    return false;
  }
}

// 라우터 밖(쿼리 캐시)에서도 부를 수 있게 전체 새로고침으로 이동한다
export function reloadAt(path: string): void {
  window.location.assign(path);
}

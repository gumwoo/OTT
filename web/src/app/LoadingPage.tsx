// 이미지와 같은 비율의 빈 자리만 둔다. 스피너는 플레이어 버퍼링에만 쓴다 (docs/ui.md 상태)
export function LoadingPage() {
  return (
    <div aria-busy="true" aria-label="불러오는 중">
      <div className="bg-surface aspect-video max-h-[85vh] w-full" />
      <div className="mt-12 flex gap-4 overflow-hidden px-6 md:gap-5 md:px-10">
        {Array.from({ length: 6 }, (_, i) => (
          <div key={i} className="bg-surface aspect-[2/3] w-36 shrink-0 rounded-lg md:w-44" />
        ))}
      </div>
    </div>
  );
}

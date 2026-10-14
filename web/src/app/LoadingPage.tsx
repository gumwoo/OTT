// 이미지와 같은 비율의 빈 자리만 둔다. 스피너는 플레이어 버퍼링에만 쓴다 (docs/ui.md 상태)
export function LoadingPage() {
  return (
    <div aria-busy="true" aria-label="불러오는 중">
      <div className="bg-bg h-14" />
      <div className="bg-surface aspect-video max-h-[70vh] w-full" />
      <div className="mt-10 flex gap-2 px-6 md:px-12">
        {Array.from({ length: 6 }, (_, i) => (
          <div key={i} className="bg-surface aspect-[2/3] w-32 shrink-0 rounded-sm md:w-40" />
        ))}
      </div>
    </div>
  );
}

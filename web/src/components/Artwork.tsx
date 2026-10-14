import { useState } from 'react';

type Props = {
  contentId: string;
  title: string;
  kind: 'poster' | 'still';
  className?: string;
};

const RATIO = { poster: 'aspect-[2/3]', still: 'aspect-video' } as const;

// 스틸컷 옆에는 큰 제목이 따로 있어서 대체 제목을 쓰지 않는다 (docs/ui.md 이미지)
export function Artwork({ contentId, title, kind, className = '' }: Props) {
  const [failed, setFailed] = useState(false);
  if (failed) {
    return (
      <div
        aria-hidden={kind === 'still'}
        className={`${RATIO[kind]} bg-surface-raised flex w-full items-end p-3 ${className}`}
      >
        {kind === 'poster' && (
          <span className="text-text line-clamp-3 text-sm font-semibold">{title}</span>
        )}
      </div>
    );
  }
  return (
    <>
      <img
        src={`/artwork/${encodeURIComponent(contentId)}/${kind}.jpg`}
        alt={title}
        loading="lazy"
        onError={() => setFailed(true)}
        className={`${RATIO[kind]} bg-surface-raised w-full object-cover ${className}`}
      />
      {kind === 'poster' && (
        <span
          aria-hidden="true"
          className="bg-scrim text-text absolute inset-x-0 bottom-0 truncate px-2 py-1.5 text-sm opacity-0 transition-opacity group-hover:opacity-100"
        >
          {title}
        </span>
      )}
    </>
  );
}

import { useState } from 'react';

type Props = {
  contentId: string;
  title: string;
  kind: 'poster' | 'still';
  // 첫 화면의 대표 이미지만 바로 읽는다
  priority?: boolean;
  className?: string;
};

// 이미지가 없으면 같은 자리를 빈 면으로 둔다. 제목은 부르는 쪽이 따로 쓴다 (docs/ui.md 이미지)
export function Artwork({ contentId, title, kind, priority = false, className = '' }: Props) {
  const [failed, setFailed] = useState(false);
  if (failed) {
    return <div aria-hidden="true" className={`bg-surface-raised ${className}`} />;
  }
  return (
    <img
      src={`/artwork/${encodeURIComponent(contentId)}/${kind}.jpg`}
      alt={kind === 'poster' ? title : ''}
      loading={priority ? 'eager' : 'lazy'}
      onError={() => setFailed(true)}
      className={`bg-surface-raised object-cover ${className}`}
    />
  );
}

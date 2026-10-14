import { useState } from 'react';

type Props = {
  contentId: string;
  kind: 'poster' | 'still';
  // 첫 화면의 대표 이미지만 바로 읽는다
  priority?: boolean;
  className?: string;
};

// 제목은 부르는 쪽이 늘 글자로 쓰므로 이미지는 장식으로 둔다. 없으면 같은 자리를 빈 면으로 (docs/ui.md 이미지)
export function Artwork({ contentId, kind, priority = false, className = '' }: Props) {
  const [failed, setFailed] = useState(false);
  if (failed) {
    return <div aria-hidden="true" className={`bg-surface-raised ${className}`} />;
  }
  return (
    <img
      src={`/artwork/${encodeURIComponent(contentId)}/${kind}.jpg`}
      alt=""
      loading={priority ? 'eager' : 'lazy'}
      onError={() => setFailed(true)}
      className={`bg-surface-raised object-cover ${className}`}
    />
  );
}

import type { ReactNode } from 'react';
import { Link } from 'react-router';
import { Artwork } from '../../components/Artwork';
import { PlayIcon } from '../../components/icons';
import { formatDuration, type CatalogItem } from './api';

type Props = {
  item: CatalogItem & { description?: string };
  playable: boolean;
  heading: 'h1' | 'h2';
  // 홈은 3줄까지, 상세는 전체
  clampDescription?: boolean;
  children?: ReactNode;
};

// 화면 폭 16:9 스틸컷 위에 왼쪽 아래로 제목을 둔다. 수치는 Apple TV 웹 실측 기준 (docs/ui.md)
// 좁은 화면에서는 이미지 높이가 글자보다 낮아서 글자를 이미지 아래로 내린다
export function Hero({
  item,
  playable,
  heading: Heading,
  clampDescription = true,
  children,
}: Props) {
  return (
    <section className="relative">
      <Artwork
        contentId={item.id}
        kind="still"
        priority
        className="aspect-video max-h-[85vh] w-full"
      />
      <div className="scrim-hero absolute inset-x-0 top-0 aspect-video max-h-[85vh]" />
      <div className="relative -mt-12 px-6 md:absolute md:inset-x-0 md:bottom-0 md:mt-0 md:px-10 md:pb-16">
        <Heading className="text-3xl font-bold tracking-tight md:text-6xl">{item.title}</Heading>
        <p className="text-text-muted mt-3 text-sm font-medium">
          {formatDuration(item.durationSec)}
          <span className="mx-2">·</span>
          {item.requiredPlan}
        </p>
        {item.description && (
          <p
            className={`text-text mt-3 max-w-[26rem] text-[15px] leading-relaxed ${clampDescription ? 'line-clamp-3' : ''}`}
          >
            {item.description}
          </p>
        )}
        <div className="mt-6 flex flex-wrap items-center gap-3">
          {playable ? (
            <Link
              to={`/contents/${item.id}/watch`}
              className="bg-text text-bg inline-flex items-center gap-2 rounded-md px-6 py-2.5 font-semibold hover:opacity-90"
            >
              <PlayIcon />
              재생
            </Link>
          ) : (
            <Link
              to="/account"
              className="bg-text text-bg rounded-md px-6 py-2.5 font-semibold hover:opacity-90"
            >
              PREMIUM으로 보기
            </Link>
          )}
          {children}
        </div>
      </div>
    </section>
  );
}

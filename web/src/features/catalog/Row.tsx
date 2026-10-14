import { useEffect, useRef, useState, type RefObject } from 'react';
import { Link } from 'react-router';
import { Artwork } from '../../components/Artwork';
import { ChevronIcon } from '../../components/icons';
import { formatDuration, type CatalogItem } from './api';

type Props = {
  title: string;
  items: CatalogItem[];
  shape: 'poster' | 'landscape';
};

// 포스터 줄은 2:3, 가로 줄은 16:9 스틸컷 + 아래 제목 (docs/ui.md 화면 구조)
export function Row({ title, items, shape }: Props) {
  const listRef = useRef<HTMLUListElement>(null);
  const overflowing = useOverflowing(listRef, items.length);

  function scroll(direction: 1 | -1) {
    const list = listRef.current;
    list?.scrollBy({ left: direction * list.clientWidth * 0.85, behavior: 'smooth' });
  }

  return (
    <section aria-label={title} className="mt-12">
      <div className="flex items-end justify-between px-6 md:px-10">
        <h2 className="text-[17px] font-bold">{title}</h2>
        <div className={overflowing ? 'hidden gap-1 md:flex' : 'hidden'}>
          {(['left', 'right'] as const).map((direction) => (
            <button
              key={direction}
              type="button"
              onClick={() => scroll(direction === 'left' ? -1 : 1)}
              aria-label={`${title} ${direction === 'left' ? '이전' : '다음'}`}
              className="text-text-muted hover:text-text hover:bg-surface-raised rounded-md p-1"
            >
              <ChevronIcon direction={direction} />
            </button>
          ))}
        </div>
      </div>
      <ul
        ref={listRef}
        className="mt-4 flex snap-x scroll-px-6 gap-4 overflow-x-auto scroll-smooth px-6 pb-2 md:scroll-px-10 md:gap-5 md:px-10"
      >
        {items.map((item) => (
          <li
            key={item.id}
            className={`shrink-0 snap-start ${shape === 'poster' ? 'w-36 md:w-44' : 'w-64 md:w-80'}`}
          >
            <Link to={`/contents/${item.id}`} className="group block">
              <div className="relative overflow-hidden rounded-lg">
                <Artwork
                  contentId={item.id}
                  title={item.title}
                  kind={shape === 'poster' ? 'poster' : 'still'}
                  className={`w-full transition-transform duration-300 group-hover:scale-[1.03] ${shape === 'poster' ? 'aspect-[2/3]' : 'aspect-video'}`}
                />
              </div>
              <p className="group-hover:text-text mt-2 truncate text-sm font-medium">
                {item.title}
              </p>
              <p className="text-text-muted text-xs">
                {formatDuration(item.durationSec)}
                {item.requiredPlan === 'PREMIUM' && ' · PREMIUM'}
              </p>
            </Link>
          </li>
        ))}
      </ul>
    </section>
  );
}

function useOverflowing(ref: RefObject<HTMLElement | null>, itemCount: number): boolean {
  const [overflowing, setOverflowing] = useState(false);
  useEffect(() => {
    const element = ref.current;
    if (!element) return;
    const update = () => setOverflowing(element.scrollWidth > element.clientWidth + 1);
    update();
    const observer = new ResizeObserver(update);
    observer.observe(element);
    return () => observer.disconnect();
  }, [ref, itemCount]);
  return overflowing;
}

import { useRef } from 'react';
import { Link } from 'react-router';
import { Artwork } from '../../components/Artwork';
import type { CatalogItem } from './api';

export function Row({ title, items }: { title: string; items: CatalogItem[] }) {
  const listRef = useRef<HTMLUListElement>(null);

  function scroll(direction: 1 | -1) {
    const list = listRef.current;
    list?.scrollBy({ left: direction * list.clientWidth * 0.9, behavior: 'smooth' });
  }

  return (
    <section aria-label={title} className="mt-10">
      <div className="flex items-baseline justify-between px-6 md:px-12">
        <h2 className="text-lg font-semibold">{title}</h2>
        <div className="hidden gap-1 md:flex">
          <button
            type="button"
            onClick={() => scroll(-1)}
            aria-label={`${title} 이전`}
            className="text-text-muted hover:text-text rounded-sm px-2"
          >
            ‹
          </button>
          <button
            type="button"
            onClick={() => scroll(1)}
            aria-label={`${title} 다음`}
            className="text-text-muted hover:text-text rounded-sm px-2"
          >
            ›
          </button>
        </div>
      </div>
      <ul
        ref={listRef}
        className="mt-3 flex snap-x gap-2 overflow-x-auto scroll-smooth px-6 pb-2 md:px-12"
      >
        {items.map((item) => (
          <li key={item.id} className="w-32 shrink-0 snap-start md:w-40">
            <Link to={`/contents/${item.id}`} className="group block">
              <div className="relative overflow-hidden rounded-sm">
                <Artwork
                  contentId={item.id}
                  title={item.title}
                  kind="poster"
                  className="transition-transform duration-200 group-hover:scale-105"
                />
                {item.requiredPlan === 'PREMIUM' && (
                  <span className="bg-scrim text-accent absolute top-0 left-0 px-1.5 py-0.5 text-[11px] font-semibold tracking-wide">
                    PREMIUM
                  </span>
                )}
              </div>
            </Link>
          </li>
        ))}
      </ul>
    </section>
  );
}

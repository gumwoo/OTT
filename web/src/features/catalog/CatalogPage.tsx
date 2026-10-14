import { useSuspenseQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { Artwork } from '../../components/Artwork';
import { meQueryOptions } from '../auth/api';
import { catalogQueryOptions, formatDuration, type CatalogItem } from './api';
import { Row } from './Row';

export function CatalogPage() {
  const { data: items } = useSuspenseQuery(catalogQueryOptions());
  const { data: account } = useSuspenseQuery(meQueryOptions());
  const premium = account?.subscription.plan === 'PREMIUM';
  const [featured] = items;

  if (!featured) {
    return <p className="text-text-muted px-6 py-16 md:px-12">아직 공개된 작품이 없습니다</p>;
  }

  const playable = items.filter((item) => premium || item.requiredPlan === 'BASIC');
  const premiumOnly = items.filter((item) => item.requiredPlan === 'PREMIUM');

  return (
    <main className="pb-16">
      <Featured item={featured} playable={premium || featured.requiredPlan === 'BASIC'} />
      {playable.length > 0 && <Row title="지금 볼 수 있는 작품" items={playable} />}
      {!premium && premiumOnly.length > 0 && <Row title="PREMIUM 작품" items={premiumOnly} />}
    </main>
  );
}

function Featured({ item, playable }: { item: CatalogItem; playable: boolean }) {
  return (
    <section className="relative">
      <Artwork contentId={item.id} title={item.title} kind="still" className="max-h-[70vh]" />
      <div className="bg-scrim absolute inset-x-0 bottom-0 px-6 pt-10 pb-8 md:px-12">
        <h1 className="text-3xl font-bold md:text-5xl">{item.title}</h1>
        <p className="text-text-muted mt-2 text-sm">
          {formatDuration(item.durationSec)}
          {item.requiredPlan === 'PREMIUM' && <span className="text-text ml-2">PREMIUM</span>}
        </p>
        <div className="mt-5 flex gap-2">
          {playable ? (
            <Link
              to={`/contents/${item.id}/watch`}
              className="bg-accent text-bg rounded-sm px-6 py-2 font-semibold hover:opacity-90"
            >
              재생
            </Link>
          ) : (
            <Link
              to="/account"
              className="bg-accent text-bg rounded-sm px-6 py-2 font-semibold hover:opacity-90"
            >
              PREMIUM으로 보기
            </Link>
          )}
          <Link
            to={`/contents/${item.id}`}
            className="bg-surface-raised text-text hover:bg-line rounded-sm px-6 py-2"
          >
            상세 정보
          </Link>
        </div>
      </div>
    </section>
  );
}

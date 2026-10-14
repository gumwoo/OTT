import { useQuery, useSuspenseQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { meQueryOptions } from '../auth/api';
import { catalogQueryOptions, contentQueryOptions } from './api';
import { Hero } from './Hero';
import { Row } from './Row';

export function CatalogPage() {
  const { data: items } = useSuspenseQuery(catalogQueryOptions());
  const { data: account } = useSuspenseQuery(meQueryOptions());
  const premium = account?.subscription.plan === 'PREMIUM';
  const [featured] = items;
  // 목록 응답에는 줄거리가 없다. 대표 작품만 상세를 따로 읽고, 오기 전에는 줄거리 없이 그린다
  const { data: featuredDetail } = useQuery({
    ...contentQueryOptions(featured?.id ?? ''),
    enabled: featured !== undefined,
  });

  if (!featured) {
    return <p className="text-text-muted px-6 pt-24 md:px-10">아직 공개된 작품이 없습니다</p>;
  }

  const playable = items.filter((item) => premium || item.requiredPlan === 'BASIC');
  const premiumOnly = items.filter((item) => item.requiredPlan === 'PREMIUM');

  return (
    <main>
      <Hero
        item={featuredDetail ?? featured}
        playable={premium || featured.requiredPlan === 'BASIC'}
        heading="h1"
      >
        <Link
          to={`/contents/${featured.id}`}
          className="bg-text/20 text-text hover:bg-text/30 rounded-md px-6 py-2.5 font-semibold"
        >
          상세 정보
        </Link>
      </Hero>
      {playable.length > 0 && <Row title="지금 볼 수 있는 작품" items={playable} shape="poster" />}
      {!premium && premiumOnly.length > 0 && (
        <Row title="PREMIUM 작품" items={premiumOnly} shape="landscape" />
      )}
      <Row title="전체 작품" items={items} shape="landscape" />
    </main>
  );
}

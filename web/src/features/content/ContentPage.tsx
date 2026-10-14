import { useSuspenseQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router';
import { contentQueryOptions, formatDuration } from '../catalog/api';
import { Hero } from '../catalog/Hero';
import { CREDITS } from '../credits/credits';

export function ContentPage() {
  const { id = '' } = useParams();
  const { data: content } = useSuspenseQuery(contentQueryOptions(id));
  const credit = CREDITS[content.id];

  return (
    <main>
      <Hero item={content} playable={content.playable} heading="h1" clampDescription={false}>
        {!content.playable && (
          <p className="text-text-muted text-sm">PREMIUM 구독이 필요한 작품입니다</p>
        )}
      </Hero>
      <section aria-label="작품 정보" className="mt-10 max-w-3xl px-6 md:px-10">
        <h2 className="text-[17px] font-bold">정보</h2>
        <dl className="mt-4 grid grid-cols-[7rem_1fr] gap-y-3 text-sm">
          <dt className="text-text-muted">러닝타임</dt>
          <dd>{formatDuration(content.durationSec)}</dd>
          <dt className="text-text-muted">이용 등급</dt>
          <dd>
            {content.requiredPlan}
            {!content.playable && (
              <Link to="/account" className="text-text ml-3 underline underline-offset-4">
                구독 바꾸기
              </Link>
            )}
          </dd>
          {credit && (
            <>
              <dt className="text-text-muted">출처</dt>
              <dd>
                © {credit.holder} ·{' '}
                <a href={credit.licenseUrl} className="underline underline-offset-4">
                  {credit.license}
                </a>{' '}
                ·{' '}
                <a href={credit.source} className="underline underline-offset-4">
                  원본
                </a>
              </dd>
            </>
          )}
        </dl>
      </section>
    </main>
  );
}

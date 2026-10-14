import { useSuspenseQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router';
import { Artwork } from '../../components/Artwork';
import { contentQueryOptions, formatDuration } from '../catalog/api';

export function ContentPage() {
  const { id = '' } = useParams();
  const { data: content } = useSuspenseQuery(contentQueryOptions(id));

  return (
    <main className="pb-16">
      <div className="relative">
        <Artwork
          contentId={content.id}
          title={content.title}
          kind="still"
          className="max-h-[60vh]"
        />
        <div className="bg-scrim absolute inset-x-0 bottom-0 px-6 pt-10 pb-6 md:px-12">
          <h1 className="text-3xl font-bold md:text-4xl">{content.title}</h1>
          <p className="text-text-muted mt-2 text-sm">
            {formatDuration(content.durationSec)}
            {content.requiredPlan === 'PREMIUM' && <span className="text-text ml-2">PREMIUM</span>}
          </p>
        </div>
      </div>
      <div className="max-w-3xl px-6 md:px-12">
        <div className="mt-6">
          {content.playable ? (
            <Link
              to={`/contents/${content.id}/watch`}
              className="bg-accent text-bg inline-block rounded-sm px-8 py-2 font-semibold hover:opacity-90"
            >
              재생
            </Link>
          ) : (
            <p className="text-sm">
              PREMIUM 구독이 필요한 작품입니다.{' '}
              <Link to="/account" className="text-accent underline underline-offset-4">
                구독 바꾸기
              </Link>
            </p>
          )}
        </div>
        {content.description && (
          <p className="text-text-muted mt-6 leading-relaxed">{content.description}</p>
        )}
      </div>
    </main>
  );
}

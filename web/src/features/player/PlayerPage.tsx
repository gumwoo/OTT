import { useEffect, useRef, useState } from 'react';
import { Link, useLoaderData, useParams, type LoaderFunctionArgs } from 'react-router';
import { messageFor } from '../../lib/api/messages';
import { startPlayback, type PlaybackGrant } from './api';
import { usePlayer } from './usePlayer';

const CONTROLS_HIDE_MS = 3_000;

// 재생 시작은 화면 진입 한 번에 한 번이라 loader에서 한다. 재검증으로 세션이 또 생기지 않게 막는다
export function playerLoader({ params }: LoaderFunctionArgs): Promise<PlaybackGrant> {
  return startPlayback(params.id ?? '');
}

export function shouldPlayerRevalidate() {
  return false;
}

export function PlayerPage() {
  const grant = useLoaderData<typeof playerLoader>();
  const { id = '' } = useParams();
  const video = useRef<HTMLVideoElement>(null);
  const refreshError = usePlayer(video, grant);
  const controlsVisible = useControlsVisibility();

  return (
    <main className="bg-bg fixed inset-0">
      {/* eslint-disable-next-line jsx-a11y/media-has-caption -- 자막은 범위 밖 (app-design.md §1) */}
      <video ref={video} controls playsInline aria-label="영상" className="h-full w-full" />
      <div
        className={`absolute top-0 left-0 p-4 transition-opacity duration-300 ${controlsVisible ? 'opacity-100' : 'pointer-events-none opacity-0'}`}
      >
        <Link to={`/contents/${id}`} className="bg-scrim text-text rounded-sm px-3 py-1.5 text-sm">
          나가기
        </Link>
      </div>
      {refreshError && (
        <p
          role="alert"
          className="bg-surface-raised absolute inset-x-0 bottom-20 mx-auto w-fit rounded-sm px-4 py-2 text-sm"
        >
          재생을 이어갈 수 없습니다. {messageFor(refreshError)}
        </p>
      )}
    </main>
  );
}

function useControlsVisibility(): boolean {
  const [visible, setVisible] = useState(true);
  useEffect(() => {
    let timer = window.setTimeout(() => setVisible(false), CONTROLS_HIDE_MS);
    const onMove = () => {
      setVisible(true);
      window.clearTimeout(timer);
      timer = window.setTimeout(() => setVisible(false), CONTROLS_HIDE_MS);
    };
    window.addEventListener('mousemove', onMove);
    return () => {
      window.removeEventListener('mousemove', onMove);
      window.clearTimeout(timer);
    };
  }, []);
  return visible;
}

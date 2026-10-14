import { useEffect, useRef, useState } from 'react';
import { Link, useLoaderData, useParams, type LoaderFunctionArgs } from 'react-router';
import { BackIcon } from '../../components/icons';
import { isDevMode } from '../../lib/devMode';
import { messageFor } from '../../lib/api/messages';
import { startPlayback } from './api';
import { QoePanel } from './QoePanel';
import { usePlayer, type PlayerGrant, type PlayerProblem } from './usePlayer';

const CONTROLS_HIDE_MS = 3_000;

const DENIED_MESSAGES: Record<string, string> = {
  GEO_BLOCKED: '현재 계신 국가에서는 볼 수 없는 작품입니다',
  NOT_AVAILABLE: '지금은 볼 수 있는 기간이 아닙니다',
  POLICY_UNAVAILABLE: '이 작품의 재생 정책이 없어 재생할 수 없습니다',
};

// 재생 시작은 화면 진입 한 번에 한 번이라 loader에서 한다. 재검증으로 세션이 또 생기지 않게 막는다
export async function playerLoader({ params }: LoaderFunctionArgs): Promise<PlayerGrant> {
  const requestedAt = performance.now();
  return { ...(await startPlayback(params.id ?? '')), requestedAt };
}

export function shouldPlayerRevalidate() {
  return false;
}

export function PlayerPage() {
  const grant = useLoaderData<typeof playerLoader>();
  const { id = '' } = useParams();
  const video = useRef<HTMLVideoElement>(null);
  const { problem, diagnostics } = usePlayer(video, grant);
  const controlsVisible = useControlsVisibility();
  const panelOpen = useDiagnosticsToggle();

  return (
    <main className="fixed inset-0 bg-black">
      {/* eslint-disable-next-line jsx-a11y/media-has-caption -- 자막은 범위 밖 (app-design.md §1) */}
      <video ref={video} controls playsInline aria-label="영상" className="h-full w-full" />
      <div
        className={`scrim-top absolute inset-x-0 top-0 flex items-center justify-between p-4 transition-opacity duration-300 ${controlsVisible ? 'visible opacity-100' : 'invisible opacity-0'}`}
      >
        <Link
          to={`/contents/${id}`}
          className="text-text inline-flex items-center gap-2 rounded-md px-2 py-1.5 text-sm"
        >
          <BackIcon />
          나가기
        </Link>
        {isDevMode() && <span className="text-text-muted text-xs">Shift + D 재생 진단</span>}
      </div>
      {panelOpen && (
        <QoePanel
          video={video}
          diagnostics={diagnostics}
          sessionId={grant.sessionId}
          manifestUrl={grant.manifestUrl}
        />
      )}
      {problem && <ProblemNotice problem={problem} contentId={id} />}
    </main>
  );
}

function ProblemNotice({ problem, contentId }: { problem: PlayerProblem; contentId: string }) {
  if (problem.kind === 'denied') {
    return (
      <div role="alert" className="absolute inset-0 grid place-items-center bg-black/80 p-6">
        <div className="max-w-md text-center">
          <p className="text-lg font-semibold">
            {DENIED_MESSAGES[problem.code] ?? '재생할 수 없는 작품입니다'}
          </p>
          <p className="text-text-muted mt-2 text-sm">사유 코드 {problem.code}</p>
          <Link
            to={`/contents/${contentId}`}
            className="text-text mt-6 inline-block underline underline-offset-4"
          >
            작품 정보로 돌아가기
          </Link>
        </div>
      </div>
    );
  }
  return (
    <p
      role="alert"
      className="bg-surface-raised absolute inset-x-0 bottom-24 mx-auto w-fit rounded-md px-4 py-2 text-sm"
    >
      {problem.kind === 'refresh'
        ? `재생을 이어갈 수 없습니다. ${messageFor(problem.error)}`
        : '영상을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요'}
    </p>
  );
}

function useDiagnosticsToggle(): boolean {
  const [open, setOpen] = useState(false);
  useEffect(() => {
    if (!isDevMode()) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.repeat || event.ctrlKey || event.metaKey || event.altKey) return;
      if (event.shiftKey && event.code === 'KeyD') setOpen((value) => !value);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
  return open;
}

// 입력이 3초 동안 없으면 숨긴다. 숨기는 동안은 포커스도 받지 않는다
function useControlsVisibility(): boolean {
  const [visible, setVisible] = useState(true);
  useEffect(() => {
    let timer = window.setTimeout(() => setVisible(false), CONTROLS_HIDE_MS);
    const show = () => {
      setVisible(true);
      window.clearTimeout(timer);
      timer = window.setTimeout(() => setVisible(false), CONTROLS_HIDE_MS);
    };
    const events = ['mousemove', 'touchstart', 'keydown'] as const;
    events.forEach((name) => window.addEventListener(name, show));
    return () => {
      events.forEach((name) => window.removeEventListener(name, show));
      window.clearTimeout(timer);
    };
  }, []);
  return visible;
}

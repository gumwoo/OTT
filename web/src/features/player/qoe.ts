// 지표 정의는 design.md §9. 시각은 performance.now() 기준 밀리초
export type QoeSnapshot = {
  startupMs: number | null;
  watchedMs: number;
  rebufferMs: number;
  rebufferCount: number;
  levelSwitches: number;
  errors: string[];
};

const MAX_ERRORS = 20;
const MAX_ERROR_LENGTH = 200;

export type QoeTracker = ReturnType<typeof createQoeTracker>;

export function createQoeTracker(startedAt: number) {
  let firstPlayingAt: number | null = null;
  let waitingSince: number | null = null;
  let rebufferMs = 0;
  let rebufferCount = 0;
  let levelSwitches = 0;
  let currentLevel: string | null = null;
  const errors: string[] = [];

  return {
    playing(now: number) {
      firstPlayingAt ??= now;
      if (waitingSince !== null) {
        rebufferMs += now - waitingSince;
        waitingSince = null;
      }
    },
    // 첫 재생 전 대기는 startup에 들어가므로 rebuffer로 세지 않는다
    waiting(now: number) {
      if (firstPlayingAt === null || waitingSince !== null) {
        return;
      }
      waitingSince = now;
      rebufferCount += 1;
    },
    levelSwitched(label: string) {
      if (currentLevel !== null && currentLevel !== label) {
        levelSwitches += 1;
      }
      currentLevel = label;
    },
    error(message: string) {
      if (errors.length < MAX_ERRORS) {
        errors.push(message.slice(0, MAX_ERROR_LENGTH));
      }
    },
    snapshot(now: number): QoeSnapshot {
      const ongoing = waitingSince === null ? 0 : now - waitingSince;
      return {
        startupMs: firstPlayingAt === null ? null : Math.round(firstPlayingAt - startedAt),
        watchedMs: firstPlayingAt === null ? 0 : Math.round(now - firstPlayingAt),
        rebufferMs: Math.round(rebufferMs + ongoing),
        rebufferCount,
        levelSwitches,
        errors: [...errors],
      };
    },
  };
}

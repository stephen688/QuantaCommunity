const DEFAULT_INTERVAL_MS = 45_000;

let timerId: ReturnType<typeof setInterval> | null = null;

export function startFeedTimer(refresh: () => void, intervalMs = DEFAULT_INTERVAL_MS): void {
  stopFeedTimer();
  timerId = setInterval(refresh, intervalMs);
}

export function stopFeedTimer(): void {
  if (timerId !== null) {
    clearInterval(timerId);
    timerId = null;
  }
}

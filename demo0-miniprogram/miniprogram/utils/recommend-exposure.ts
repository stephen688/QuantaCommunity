export const RECOMMEND_VISIBLE_RATIO = 0.5;
const EXPOSURE_BATCH_SIZE = 20;
const EXPOSURE_BATCH_INTERVAL_MS = 2_000;
const MAX_PENDING_EXPOSURES = 500;
const MAX_RETRY_ATTEMPTS = 3;
// A refresh waits through the normal request timeout and one more batch, but
// must not be held forever by a large or repeatedly failing local queue.
const EXPOSURE_DRAIN_TIMEOUT_MS = 30_000;

export interface RecommendExposureBatch {
  feedSessionId: string;
  contentIds: number[];
}

export interface ExposureObservation {
  sessionId: string;
  actorKey: string;
  contentId: number;
  ratio: number;
}

type PendingExposure = ExposureObservation & { attempts: number };
type SendExposureBatch = (batch: RecommendExposureBatch) => Promise<void>;

function pendingKey(item: Pick<PendingExposure, 'actorKey' | 'sessionId' | 'contentId'>): string {
  return `${item.actorKey}|${item.sessionId}|${item.contentId}`;
}

function retryTimerUnref(timer: ReturnType<typeof setTimeout>): void {
  const candidate = timer as ReturnType<typeof setTimeout> & { unref?: () => void };
  candidate.unref?.();
}

/**
 * 按可视比例收集推荐曝光并批量发送。
 * 发送失败只保留当前批次，最多退避重试三次，不把失败伪装成已曝光。
 */
export function createExposureQueue(send: SendExposureBatch): {
  observe(observation: ExposureObservation): void;
  flush(): Promise<void>;
  drain(): Promise<void>;
  dispose(): void;
} {
  const pending = new Map<string, PendingExposure>();
  const reported = new Set<string>();
  let flushTimer: ReturnType<typeof setTimeout> | undefined;
  let retryTimer: ReturnType<typeof setTimeout> | undefined;
  let inFlightFlush: Promise<void> | undefined;
  let lastFlushSucceeded = true;
  let inFlightDrain: Promise<void> | undefined;
  let disposed = false;

  function scheduleFlush(): void {
    if (disposed || flushTimer || retryTimer) {
      return;
    }
    flushTimer = setTimeout(() => {
      flushTimer = undefined;
      void flush();
    }, EXPOSURE_BATCH_INTERVAL_MS);
    retryTimerUnref(flushTimer);
  }

  function selectBatch(): PendingExposure[] {
    const first = pending.values().next().value as PendingExposure | undefined;
    if (!first) {
      return [];
    }
    const result: PendingExposure[] = [];
    for (const item of pending.values()) {
      if (item.actorKey !== first.actorKey || item.sessionId !== first.sessionId) {
        continue;
      }
      result.push(item);
      if (result.length >= EXPOSURE_BATCH_SIZE) {
        break;
      }
    }
    return result;
  }

  function flush(): Promise<void> {
    // Returning the same promise is important when a refresh and a scheduled
    // batch attempt meet at the same time: the refresh must await that actual
    // request instead of observing a prematurely resolved placeholder.
    if (inFlightFlush) {
      return inFlightFlush;
    }
    if (disposed) {
      return Promise.resolve();
    }
    if (flushTimer) {
      clearTimeout(flushTimer);
      flushTimer = undefined;
    }
    const batch = selectBatch();
    if (!batch.length) {
      lastFlushSucceeded = true;
      return Promise.resolve();
    }

    const operation = (async (): Promise<void> => {
      try {
        await send({
          feedSessionId: batch[0].sessionId,
          contentIds: batch.map((item) => item.contentId),
        });
        for (const item of batch) {
          const key = pendingKey(item);
          pending.delete(key);
          reported.add(key);
        }
        if (pending.size > 0) {
          scheduleFlush();
        }
        lastFlushSucceeded = true;
      } catch {
        const retryable = batch.some((item) => item.attempts < MAX_RETRY_ATTEMPTS);
        for (const item of batch) {
          const key = pendingKey(item);
          const current = pending.get(key);
          if (current) {
            current.attempts += 1;
          }
        }
        if (retryable && !disposed && !retryTimer) {
          const maxAttempts = Math.max(...batch.map((item) => item.attempts));
          const delay = 1_000 * 2 ** Math.max(0, maxAttempts - 1);
          retryTimer = setTimeout(() => {
            retryTimer = undefined;
            void flush();
          }, delay);
          retryTimerUnref(retryTimer);
        }
        lastFlushSucceeded = false;
      }
    })();
    inFlightFlush = operation.finally(() => {
      inFlightFlush = undefined;
    });
    return inFlightFlush;
  }

  function drain(): Promise<void> {
    if (inFlightDrain) {
      return inFlightDrain;
    }
    const operation = new Promise<void>((resolve) => {
      let settled = false;
      let timedOut = false;
      const finish = () => {
        if (settled) {
          return;
        }
        settled = true;
        clearTimeout(timeout);
        resolve();
      };
      const timeout = setTimeout(() => {
        timedOut = true;
        finish();
      }, EXPOSURE_DRAIN_TIMEOUT_MS);
      retryTimerUnref(timeout);

      void (async () => {
        while (!disposed && !timedOut) {
          // Even when the in-flight sender has already removed its items from
          // pending, wait for its promise to settle before allowing a refresh
          // to issue the new session GET.
          if (inFlightFlush) {
            await flush();
            if (!lastFlushSucceeded) {
              break;
            }
            continue;
          }
          if (pending.size === 0) {
            break;
          }
          // flush() waits for an existing attempt, or starts the next batch
          // immediately. A failed attempt stops the drain; the batch remains
          // pending for its bounded background retries.
          await flush();
          if (!lastFlushSucceeded) {
            break;
          }
        }
      })().finally(finish);
    });
    inFlightDrain = operation.finally(() => {
      inFlightDrain = undefined;
    });
    return inFlightDrain;
  }

  function observe(observation: ExposureObservation): void {
    if (
      disposed ||
      !observation ||
      !observation.sessionId ||
      !observation.actorKey ||
      !Number.isInteger(observation.contentId) ||
      observation.contentId <= 0 ||
      !Number.isFinite(observation.ratio) ||
      observation.ratio < RECOMMEND_VISIBLE_RATIO
    ) {
      return;
    }
    const key = pendingKey(observation);
    if (reported.has(key)) {
      return;
    }
    const previous = pending.get(key);
    if (previous) {
      if (previous.attempts < MAX_RETRY_ATTEMPTS) {
        return;
      }
      // 达到退避上限后，下一次重新可见允许重新开始一轮有限重试。
      previous.attempts = 0;
      void flush();
      return;
    }
    if (pending.size >= MAX_PENDING_EXPOSURES) {
      void flush();
      if (pending.size >= MAX_PENDING_EXPOSURES) {
        return;
      }
    }
    pending.set(key, { ...observation, attempts: 0 });
    if (pending.size >= EXPOSURE_BATCH_SIZE) {
      void flush();
    } else {
      scheduleFlush();
    }
  }

  function dispose(): void {
    disposed = true;
    if (flushTimer) {
      clearTimeout(flushTimer);
      flushTimer = undefined;
    }
    if (retryTimer) {
      clearTimeout(retryTimer);
      retryTimer = undefined;
    }
    pending.clear();
  }

  return { observe, flush, drain, dispose };
}

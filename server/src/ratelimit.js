// Fixed-window counters in memory. Enough for one container; if the API
// ever runs on several machines, move this into a shared store.
export class RateLimiter {
  constructor(clock = () => Date.now()) {
    this.clock = clock;
    this.windows = new Map();
  }

  /** Counts one hit; returns false when `limit` is already used up. */
  hit(bucket, key, limit, windowMs) {
    const now = this.clock();
    const id = `${bucket}:${key}`;
    let w = this.windows.get(id);
    if (!w || now - w.start >= windowMs) {
      w = { start: now, count: 0, windowMs };
      this.windows.set(id, w);
    }
    if (w.count >= limit) return false;
    w.count += 1;
    return true;
  }

  /** True when the bucket is used up, without counting. */
  exhausted(bucket, key, limit) {
    const w = this.windows.get(`${bucket}:${key}`);
    return Boolean(w && this.clock() - w.start < w.windowMs && w.count >= limit);
  }

  sweep() {
    const now = this.clock();
    for (const [id, w] of this.windows) if (now - w.start >= w.windowMs) this.windows.delete(id);
  }
}

export const HOUR = 3600_000;
export const DAY = 24 * HOUR;

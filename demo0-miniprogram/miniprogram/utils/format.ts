const MS_DAY = 86400000;

function parseInput(input?: string | number | Date): Date | undefined {
  if (input === undefined || input === null) {
    return undefined;
  }
  if (input instanceof Date) {
    return Number.isNaN(input.getTime()) ? undefined : input;
  }
  if (typeof input === 'number') {
    const d = new Date(input);
    return Number.isNaN(d.getTime()) ? undefined : d;
  }
  const s = String(input).trim();
  if (!s) {
    return undefined;
  }
  const normalized = s.includes('T') ? s : s.replace(' ', 'T');
  const d = new Date(normalized);
  return Number.isNaN(d.getTime()) ? undefined : d;
}

/**
 * 相对时间展示：今天显示时间，昨天显示「昨天」，一周内显示「N天前」，否则 yyyy-MM-dd。
 */
export function formatRelativeTime(input?: string | number | Date): string {
  const d = parseInput(input);
  if (!d) {
    return '';
  }
  const now = new Date();
  const diff = now.getTime() - d.getTime();
  if (diff < 0) {
    return formatDateYmd(d);
  }
  const dayStart = (t: Date) =>
    new Date(t.getFullYear(), t.getMonth(), t.getDate()).getTime();
  const today0 = dayStart(now);
  const d0 = dayStart(d);
  if (d0 === today0) {
    const hh = String(d.getHours()).padStart(2, '0');
    const mm = String(d.getMinutes()).padStart(2, '0');
    return `${hh}:${mm}`;
  }
  if (d0 === today0 - MS_DAY) {
    const hh = String(d.getHours()).padStart(2, '0');
    const mm = String(d.getMinutes()).padStart(2, '0');
    return `昨天 ${hh}:${mm}`;
  }
  if (diff < 7 * MS_DAY) {
    const days = Math.floor(diff / MS_DAY);
    return `${Math.max(1, days)}天前`;
  }
  return formatDateYmd(d);
}

export function formatDateYmd(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

/** 紧凑数字：1.2万、987 */
export function formatCompactNumber(n?: number): string {
  if (n === undefined || n === null || Number.isNaN(n)) {
    return '0';
  }
  const x = Math.floor(n);
  if (x < 10000) {
    return String(x);
  }
  const w = x / 10000;
  const s = w >= 10 ? w.toFixed(0) : w.toFixed(1).replace(/\.0$/, '');
  return `${s}万`;
}

/** 作者副标题：Quanta 2023届 · 技术部 */
export function formatAuthorSubtitle(batch?: string, department?: string): string {
  const b = (batch || '').trim();
  const d = (department || '').trim();
  const batchLabel = b ? (b.endsWith('届') ? b : `${b}届`) : '';
  const parts: string[] = [];
  if (batchLabel) {
    parts.push(`Quanta ${batchLabel}`);
  }
  if (d) {
    parts.push(d);
  }
  return parts.join(' · ');
}

/** 摘要截断，省略号 */
export function formatSummary(text?: string, maxLen = 80): string {
  if (!text) {
    return '';
  }
  const t = text.replace(/\s+/g, ' ').trim();
  if (t.length <= maxLen) {
    return t;
  }
  return `${t.slice(0, maxLen)}…`;
}

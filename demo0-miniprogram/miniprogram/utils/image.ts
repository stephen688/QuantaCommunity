/**
 * 过滤空串；无图时返回空数组（由 UI 决定是否占位）。
 */
export function normalizeImages(images?: string[] | null): string[] {
  if (!images || !Array.isArray(images)) {
    return [];
  }
  return images.map((s) => (typeof s === 'string' ? s.trim() : '')).filter(Boolean);
}

/** 列表首张图，无则 undefined */
export function pickCoverImage(images?: string[] | null): string | undefined {
  const list = normalizeImages(images);
  return list[0];
}

/**
 * 头像兜底：无有效 URL 时返回空串，避免组件 String 属性收到 null。
 */
export function withDefaultAvatar(avatarUrl?: string | null): string {
  if (typeof avatarUrl !== 'string') {
    return '';
  }
  return avatarUrl.trim();
}

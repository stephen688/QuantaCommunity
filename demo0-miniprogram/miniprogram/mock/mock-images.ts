/**
 * Mock 配图 URL（与后端「部分帖子有图、部分无图」一致；列表支持最多 5 张）。
 * 真机/正式版需在小程序后台配置 downloadFile 合法域名（如 picsum.photos），或改为你们 CDN/OSS 域名。
 */
export function mockPostImages(contentId: number, count: 1 | 2 | 3 | 4 | 5 = 1): string[] {
  const n = Math.max(1, Math.min(5, count));
  const out: string[] = [];
  for (let i = 0; i < n; i += 1) {
    out.push(`https://picsum.photos/seed/demo0-${contentId}-${i}/800/520`);
  }
  return out;
}

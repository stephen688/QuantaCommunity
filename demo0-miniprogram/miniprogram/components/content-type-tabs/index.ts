import type { ContentTypeFilter } from '../../constants/content';
import { CONTENT_TYPE_ALL, CONTENT_TYPE_LIFE, CONTENT_TYPE_PROFESSIONAL } from '../../constants/content';

function normalizeFilter(v: unknown): ContentTypeFilter {
  if (v === CONTENT_TYPE_LIFE || v === CONTENT_TYPE_PROFESSIONAL) {
    return v;
  }
  if (v === 'all' || v === CONTENT_TYPE_ALL) {
    return CONTENT_TYPE_ALL;
  }
  const n = Number(v);
  if (n === CONTENT_TYPE_LIFE) {
    return CONTENT_TYPE_LIFE;
  }
  if (n === CONTENT_TYPE_PROFESSIONAL) {
    return CONTENT_TYPE_PROFESSIONAL;
  }
  return CONTENT_TYPE_ALL;
}

Component({
  properties: {
    value: {
      type: null,
      optionalTypes: [String, Number],
      value: CONTENT_TYPE_ALL,
    },
  },
  data: {
    activeFilter: CONTENT_TYPE_ALL as ContentTypeFilter,
    tabs: [
      { key: CONTENT_TYPE_ALL, label: '全部' },
      { key: CONTENT_TYPE_LIFE, label: '生活' },
      { key: CONTENT_TYPE_PROFESSIONAL, label: '专业' },
    ] as { key: ContentTypeFilter; label: string }[],
  },
  observers: {
    value(v: unknown) {
      this.setData({ activeFilter: normalizeFilter(v) });
    },
  },
  lifetimes: {
    attached() {
      this.setData({ activeFilter: normalizeFilter(this.data.value) });
    },
  },
  methods: {
    onTabTap(e: WechatMiniprogram.TouchEvent) {
      const key = e.currentTarget.dataset.key as ContentTypeFilter | undefined;
      if (key === undefined) {
        return;
      }
      const next = normalizeFilter(key);
      const cur = normalizeFilter(this.data.activeFilter);
      if (next === cur) {
        return;
      }
      this.triggerEvent('change', { value: next });
    },
  },
});

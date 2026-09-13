import type { MineEntry } from '../../types/user';

Component({
  properties: {
    entries: {
      type: Array,
      value: [] as MineEntry[],
    },
  },
  methods: {
    onCellTap(e: WechatMiniprogram.TouchEvent) {
      const idx = e.currentTarget.dataset.index as number | undefined;
      const list = this.data.entries as MineEntry[];
      if (idx === undefined || !list[idx]) {
        return;
      }
      this.triggerEvent('select', { entry: list[idx], index: idx });
    },
  },
});

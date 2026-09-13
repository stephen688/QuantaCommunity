import type { MineEntry } from '../../types/user';

Component({
  properties: {
    entry: {
      type: Object,
      value: {} as MineEntry,
    },
  },
  methods: {
    onTap() {
      this.triggerEvent('tap', { entry: this.data.entry });
    },
  },
});

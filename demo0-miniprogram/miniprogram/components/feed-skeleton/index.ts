Component({
  properties: {
    variant: {
      type: String,
      value: 'home',
    },
    count: {
      type: Number,
      value: 4,
    },
  },
  data: {
    rows: [] as number[],
  },
  observers: {
    count(c: number) {
      const n = Math.min(8, Math.max(3, Math.floor(Number(c)) || 4));
      this.setData({ rows: Array.from({ length: n }, (_, i) => i) });
    },
  },
  lifetimes: {
    attached() {
      const c = Math.min(8, Math.max(3, Math.floor(Number(this.data.count)) || 4));
      this.setData({ rows: Array.from({ length: c }, (_, i) => i) });
    },
  },
});

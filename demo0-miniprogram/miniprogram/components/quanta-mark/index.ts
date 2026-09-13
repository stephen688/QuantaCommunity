Component({
  properties: {
    size: {
      type: String,
      value: 'md',
    },
  },
  data: {
    sizeClass: '',
  },
  observers: {
    size(v: string) {
      const sizeClass =
        v === 'xs' ? 'quanta-mark--xs' : v === 'sm' ? 'quanta-mark--sm' : '';
      this.setData({ sizeClass });
    },
  },
  lifetimes: {
    attached() {
      const size = this.properties.size as string;
      const sizeClass =
        size === 'xs' ? 'quanta-mark--xs' : size === 'sm' ? 'quanta-mark--sm' : '';
      this.setData({ sizeClass });
    },
  },
});

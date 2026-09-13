Component({
  properties: {
    keyword: {
      type: String,
      value: '',
    },
    summary: {
      type: String,
      value: '',
    },
    sourceCount: {
      type: Number,
      value: 0,
    },
    loading: {
      type: Boolean,
      value: false,
    },
    errorType: {
      type: String,
      value: '',
    },
    errorMessage: {
      type: String,
      value: '',
    },
  },
  data: {
    showFooterHint: false,
  },
  observers: {
    summary(s: string) {
      const t = typeof s === 'string' ? s.trim() : '';
      this.setData({ showFooterHint: Boolean(t) });
    },
  },
  lifetimes: {
    attached() {
      const t = typeof this.data.summary === 'string' ? this.data.summary.trim() : '';
      this.setData({ showFooterHint: Boolean(t) });
    },
  },
  methods: {
    onTapCard() {
      if (this.data.loading) {
        return;
      }
      const t = typeof this.data.summary === 'string' ? this.data.summary.trim() : '';
      if (!t) {
        return;
      }
      this.triggerEvent('tap');
    },
  },
});

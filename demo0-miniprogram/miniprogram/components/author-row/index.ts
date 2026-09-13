import { formatAuthorSubtitle, formatRelativeTime } from '../../utils/format';

Component({
  properties: {
    nickname: {
      type: String,
      value: '',
    },
    avatarUrl: {
      type: String,
      value: '',
    },
    time: {
      type: String,
      value: '',
    },
    quantaBatch: {
      type: String,
      value: '',
    },
    quantaDepartment: {
      type: String,
      value: '',
    },
    /** 作者用户 ID；与 clickable 配合用于跳转主页 */
    userId: {
      type: Number,
      optionalTypes: [String],
      value: 0,
    },
    /** 为 true 时点击作者区跳转（由页面 bind:tapauthor 处理） */
    clickable: {
      type: Boolean,
      value: false,
    },
    /** 信息流卡片标题下等紧凑场景 */
    compact: {
      type: Boolean,
      value: false,
    },
    /** 关注流等：如「已关注」 */
    relationText: {
      type: String,
      value: '',
    },
  },
  data: {
    displayName: '匿名用户',
    avatarLetter: '匿',
    timeText: '',
    subtitleText: '',
    showAvatar: false,
    showRelation: false,
  },
  observers: {
    relationText(t: string) {
      const text = typeof t === 'string' ? t.trim() : '';
      this.setData({ showRelation: Boolean(text) });
    },
    nickname(n: string) {
      const name = (n && n.trim()) || '匿名用户';
      this.setData({ displayName: name, avatarLetter: name.slice(0, 1) });
    },
    avatarUrl(url: string | null | undefined) {
      const s = typeof url === 'string' ? url.trim() : '';
      this.setData({ showAvatar: Boolean(s) });
    },
    time(t: string) {
      this.setData({ timeText: formatRelativeTime(t) });
    },
    'quantaBatch, quantaDepartment'(batch: string, department: string) {
      this.setData({
        subtitleText: formatAuthorSubtitle(batch, department),
      });
    },
  },
  lifetimes: {
    attached() {
      const name = (this.data.nickname && this.data.nickname.trim()) || '匿名用户';
      const url = typeof this.data.avatarUrl === 'string' ? this.data.avatarUrl.trim() : '';
      const rel = typeof this.data.relationText === 'string' ? this.data.relationText.trim() : '';
      this.setData({
        displayName: name,
        avatarLetter: name.slice(0, 1),
        showAvatar: Boolean(url),
        timeText: formatRelativeTime(this.data.time),
        subtitleText: formatAuthorSubtitle(this.data.quantaBatch, this.data.quantaDepartment),
        showRelation: Boolean(rel),
      });
    },
  },
  methods: {
    onAuthorTap() {
      if (!this.data.clickable) {
        return;
      }
      const raw = this.data.userId as unknown;
      const uid =
        typeof raw === 'number'
          ? raw
          : typeof raw === 'string'
            ? Number(raw.trim())
            : Number(raw);
      if (!Number.isFinite(uid) || uid <= 0 || !Number.isInteger(uid)) {
        wx.showToast({ title: '用户信息暂不可用', icon: 'none' });
        return;
      }
      this.triggerEvent('tapauthor', { userId: uid });
    },
  },
});

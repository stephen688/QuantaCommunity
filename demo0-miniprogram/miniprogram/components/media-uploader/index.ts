import { uploadImage } from '../../services/upload.service';

type Slot = {
  id: string;
  tempPath: string;
  remoteUrl: string;
  status: 'uploading' | 'done' | 'error';
  displaySrc: string;
};

Component({
  properties: {
    /** `grid` 多图网格；`avatar` 单头像圆形 */
    variant: {
      type: String,
      value: 'grid',
    },
    maxCount: {
      type: Number,
      value: 5,
    },
    disabled: {
      type: Boolean,
      value: false,
    },
    /** 初始或受控的已上传 URL 列表；与内部槽位同步时会触发 `change` */
    value: {
      type: Array,
      value: [] as string[],
    },
  },
  data: {
    slots: [] as Slot[],
    canAdd: true,
  },
  observers: {
    value(v: unknown[]) {
      this.syncFromParentUrls(v);
    },
    maxCount() {
      this.refreshCanAdd();
    },
    disabled() {
      this.refreshCanAdd();
    },
  },
  lifetimes: {
    attached() {
      const v = Array.isArray(this.data.value) ? (this.data.value as unknown[]) : [];
      this.syncFromParentUrls(v, true);
    },
  },
  methods: {
    emitState() {
      const slots = this.data.slots as Slot[];
      const uploading = slots.some((s) => s.status === 'uploading');
      const hasError = slots.some((s) => s.status === 'error');
      this.triggerEvent('statechange', { uploading, hasError });
    },
    syncFromParentUrls(v: unknown[], force = false) {
      const urls = (Array.isArray(v) ? v : [])
        .map((x) => (typeof x === 'string' ? x.trim() : ''))
        .filter(Boolean);
      const pending = (this.data.slots as Slot[]).some((s) => s.status === 'uploading');
      if (pending && !force) {
        return;
      }
      const cur = (this.data.slots as Slot[])
        .filter((s) => s.status === 'done' && s.remoteUrl)
        .map((s) => s.remoteUrl);
      if (!force && cur.length === urls.length && cur.every((u, i) => u === urls[i])) {
        return;
      }
      const slots = urls.map((u, i) => makeDoneSlot(u, i));
      this.setData({ slots }, () => {
        this.refreshCanAdd();
        this.emitState();
      });
    },
    refreshCanAdd() {
      const max = Math.min(9, Math.max(1, Number(this.data.maxCount) || 5));
      const n = (this.data.slots as Slot[]).length;
      const canAdd = !this.data.disabled && n < max;
      this.setData({ canAdd });
    },
    emitChange() {
      const urls = (this.data.slots as Slot[])
        .filter((s) => s.status === 'done' && s.remoteUrl)
        .map((s) => s.remoteUrl);
      this.triggerEvent('change', { value: urls });
    },
    onChoose() {
      if (!this.data.canAdd || this.data.disabled) {
        return;
      }
      const max = Math.min(9, Math.max(1, Number(this.data.maxCount) || 5));
      const remain = max - (this.data.slots as Slot[]).length;
      if (remain <= 0) {
        return;
      }
      wx.chooseMedia({
        count: remain,
        mediaType: ['image'],
        sourceType: ['album', 'camera'],
        success: (res) => {
          const files = res.tempFiles || [];
          const paths = files
            .map((f) => f.tempFilePath)
            .filter((p) => typeof p === 'string' && p);
          paths.forEach((p) => this.appendAndUpload(p));
        },
      });
    },
    appendAndUpload(tempPath: string) {
      const id = `s-${Date.now()}-${Math.random().toString(16).slice(2, 8)}`;
      const slot: Slot = {
        id,
        tempPath,
        remoteUrl: '',
        status: 'uploading',
        displaySrc: tempPath,
      };
      const slots = [...(this.data.slots as Slot[]), slot];
      this.setData({ slots }, () => {
        this.refreshCanAdd();
        this.runUpload(id, tempPath);
        this.emitState();
      });
    },
    runUpload(id: string, tempPath: string) {
      void uploadImage(tempPath).then((res) => {
        const slots = (this.data.slots as Slot[]).map((s) => {
          if (s.id !== id) {
            return s;
          }
          if (res.ok) {
            const url = res.data;
            return {
              ...s,
              status: 'done' as const,
              remoteUrl: url,
              displaySrc: url,
            };
          }
          return {
            ...s,
            status: 'error' as const,
            displaySrc: s.tempPath,
          };
        });
        if (!res.ok && res.message) {
          wx.showToast({ title: res.message.slice(0, 20), icon: 'none' });
        }
        this.setData({ slots }, () => {
          this.refreshCanAdd();
          this.emitChange();
          this.emitState();
        });
      });
    },
    onRetry(e: { currentTarget?: { dataset?: { id?: string } } }) {
      const id = e.currentTarget?.dataset?.id;
      if (!id || this.data.disabled) {
        return;
      }
      const slots = (this.data.slots as Slot[]).map((s) => {
        if (s.id !== id) {
          return s;
        }
        return {
          ...s,
          status: 'uploading' as const,
          displaySrc: s.tempPath,
        };
      });
      this.setData({ slots }, () => {
        const s = (this.data.slots as Slot[]).find((x) => x.id === id);
        if (s) {
          this.runUpload(id, s.tempPath);
        }
        this.emitState();
      });
    },
    onRemove(e: { currentTarget?: { dataset?: { id?: string } } }) {
      const id = e.currentTarget?.dataset?.id;
      if (!id || this.data.disabled) {
        return;
      }
      const slots = (this.data.slots as Slot[]).filter((s) => s.id !== id);
      this.setData({ slots }, () => {
        this.refreshCanAdd();
        this.emitChange();
        this.emitState();
      });
    },
    onPreview(e: { currentTarget?: { dataset?: { index?: unknown } } }) {
      const idx = Number(e.currentTarget?.dataset?.index);
      const slots = this.data.slots as Slot[];
      const urls = slots.map((s) => s.displaySrc).filter(Boolean);
      if (!urls.length) {
        return;
      }
      const current = Number.isFinite(idx) && idx >= 0 ? urls[idx] : urls[0];
      wx.previewImage({ urls, current });
    },
  },
});

function makeDoneSlot(remoteUrl: string, index: number): Slot {
  return {
    id: `sync-${index}`,
    tempPath: remoteUrl,
    remoteUrl,
    status: 'done',
    displaySrc: remoteUrl,
  };
}

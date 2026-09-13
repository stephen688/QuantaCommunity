import type { ApiErrorType } from '../../types/api';

type StateVisualType = ApiErrorType | 'empty' | 'finished';
type StateGraphic =
  | 'default'
  | 'bulletin'
  | 'envelope'
  | 'list'
  | 'network'
  | 'follow'
  | 'search'
  | 'collect'
  | 'message';

const ERROR_TYPES: ApiErrorType[] = [
  'network',
  'unauthorized',
  'server',
  'invalidData',
  'unknown',
];

function resolveGraphic(type: string, variant: string): StateGraphic {
  if (variant && variant !== 'auto' && variant !== 'default') {
    return variant as StateGraphic;
  }
  if (ERROR_TYPES.includes(type as ApiErrorType)) {
    return 'network';
  }
  if (type === 'empty') {
    return 'list';
  }
  return 'default';
}

Component({
  properties: {
    type: {
      type: String,
      value: 'empty',
    },
    /** auto | default | bulletin | envelope | list | network | follow | search | collect | message */
    variant: {
      type: String,
      value: 'auto',
    },
    title: {
      type: String,
      value: '',
    },
    description: {
      type: String,
      value: '',
    },
    actionText: {
      type: String,
      value: '',
    },
  },
  data: {
    showAction: false,
    graphic: 'list' as StateGraphic,
  },
  observers: {
    actionText(t: string) {
      this.setData({ showAction: Boolean(t && t.trim()) });
    },
    'type, variant'(type: string, variant: string) {
      this.setData({ graphic: resolveGraphic(type, variant) });
    },
  },
  lifetimes: {
    attached() {
      const t = this.data.actionText;
      this.setData({
        showAction: Boolean(t && t.trim()),
        graphic: resolveGraphic(this.data.type, this.data.variant),
      });
    },
  },
  methods: {
    onActionTap() {
      this.triggerEvent('action', { type: this.data.type as StateVisualType });
    },
  },
});

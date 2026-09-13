"use strict";
Component({
    properties: {
        count: {
            type: Number,
            value: 0,
        },
    },
    data: {
        text: '',
        visible: false,
    },
    observers: {
        count(n) {
            const c = Math.max(0, Math.floor(Number(n) || 0));
            let text = '';
            if (c <= 0) {
                this.setData({ visible: false, text: '' });
                return;
            }
            if (c > 99) {
                text = '99+';
            }
            else {
                text = String(c);
            }
            this.setData({ visible: true, text });
        },
    },
    lifetimes: {
        attached() {
            const c = Math.max(0, Math.floor(Number(this.data.count) || 0));
            if (c <= 0) {
                this.setData({ visible: false, text: '' });
                return;
            }
            this.setData({ visible: true, text: c > 99 ? '99+' : String(c) });
        },
    },
});

"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const content_1 = require("../../constants/content");
function normalizeFilter(v) {
    if (v === content_1.CONTENT_TYPE_LIFE || v === content_1.CONTENT_TYPE_PROFESSIONAL) {
        return v;
    }
    if (v === 'all' || v === content_1.CONTENT_TYPE_ALL) {
        return content_1.CONTENT_TYPE_ALL;
    }
    const n = Number(v);
    if (n === content_1.CONTENT_TYPE_LIFE) {
        return content_1.CONTENT_TYPE_LIFE;
    }
    if (n === content_1.CONTENT_TYPE_PROFESSIONAL) {
        return content_1.CONTENT_TYPE_PROFESSIONAL;
    }
    return content_1.CONTENT_TYPE_ALL;
}
Component({
    properties: {
        value: {
            type: null,
            optionalTypes: [String, Number],
            value: content_1.CONTENT_TYPE_ALL,
        },
    },
    data: {
        activeFilter: content_1.CONTENT_TYPE_ALL,
        tabs: [
            { key: content_1.CONTENT_TYPE_ALL, label: '全部' },
            { key: content_1.CONTENT_TYPE_LIFE, label: '生活' },
            { key: content_1.CONTENT_TYPE_PROFESSIONAL, label: '专业' },
        ],
    },
    observers: {
        value(v) {
            this.setData({ activeFilter: normalizeFilter(v) });
        },
    },
    lifetimes: {
        attached() {
            this.setData({ activeFilter: normalizeFilter(this.data.value) });
        },
    },
    methods: {
        onTabTap(e) {
            const key = e.currentTarget.dataset.key;
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

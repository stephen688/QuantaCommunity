"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
Component({
    properties: {
        entries: {
            type: Array,
            value: [],
        },
    },
    methods: {
        onCellTap(e) {
            const idx = e.currentTarget.dataset.index;
            const list = this.data.entries;
            if (idx === undefined || !list[idx]) {
                return;
            }
            this.triggerEvent('select', { entry: list[idx], index: idx });
        },
    },
});

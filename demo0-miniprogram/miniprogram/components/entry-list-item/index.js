"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
Component({
    properties: {
        entry: {
            type: Object,
            value: {},
        },
    },
    methods: {
        onTap() {
            this.triggerEvent('tap', { entry: this.data.entry });
        },
    },
});

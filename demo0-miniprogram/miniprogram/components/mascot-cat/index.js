"use strict";
Component({
    properties: {
        position: {
            type: String,
            value: 'right',
        },
        /** 设为 false 时使用纯 CSS 简化小猫（无 PNG 资源时降级） */
        useImage: {
            type: Boolean,
            value: true,
        },
        motionReduced: {
            type: Boolean,
            value: false,
        },
    },
    data: {
        positionClass: 'mascot-cat--right',
    },
    observers: {
        position(v) {
            this.setData({
                positionClass: v === 'left' ? 'mascot-cat--left' : 'mascot-cat--right',
            });
        },
    },
    lifetimes: {
        attached() {
            const pos = this.properties.position;
            this.setData({
                positionClass: pos === 'left' ? 'mascot-cat--left' : 'mascot-cat--right',
            });
        },
    },
});

"use strict";
Component({
    properties: {
        /** 正在加载更多 */
        loadingMore: {
            type: Boolean,
            value: false,
        },
        /** 分页/加载更多失败 */
        loadError: {
            type: Boolean,
            value: false,
        },
        /** 没有更多数据 */
        finished: {
            type: Boolean,
            value: false,
        },
        /** 为 false 时不占位（列表为空时由父级控制隐藏） */
        visible: {
            type: Boolean,
            value: true,
        },
    },
    methods: {
        onRetry() {
            this.triggerEvent('retry');
        },
    },
});

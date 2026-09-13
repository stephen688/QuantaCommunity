"use strict";
Component({
    properties: {
        placeholder: {
            type: String,
            value: '搜索生活求助、专业问答',
        },
        showPublish: {
            type: Boolean,
            value: true,
        },
    },
    methods: {
        onSearchTap() {
            this.triggerEvent('search');
        },
        onPublishTap() {
            this.triggerEvent('publish');
        },
    },
});

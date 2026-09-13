"use strict";
Component({
    properties: {
        type: {
            type: String,
            value: 'white',
        },
        size: {
            type: Number,
            value: 200,
        },
        rotate: {
            type: Number,
            value: 0,
        },
        flip: {
            type: Boolean,
            value: false,
        },
        motionReduced: {
            type: Boolean,
            value: false,
        },
    },
});

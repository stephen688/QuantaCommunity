package com.quanta.demo0.enums;

public enum InboxAcquireResult {

    /**
     * 当前实例抢占成功，可以执行审核。
     */
    ACQUIRED,

    /**
     * 这个事件已经成功处理过。
     */
    ALREADY_SUCCESS,

    /**
     * 正在被其他实例处理。
     */
    BUSY,

    /**
     * 已经超过最大重试次数。
     */
    DEAD
}
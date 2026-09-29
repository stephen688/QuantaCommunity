package com.quanta.demo0.platform.mq.exception;

/**
 * 平台 Outbox 插入契约异常：数据库调用完成但没有插入恰好一行记录。
 *
 * 边界：只表达平台层的写入结果，不依赖任何业务域异常；业务 Producer
 * 在自己的边界将它翻译为旧有业务错误，数据访问异常仍原样向上抛出。
 */
public class OutboxInsertFailedException extends RuntimeException {

    public OutboxInsertFailedException(String message) {
        super(message);
    }
}

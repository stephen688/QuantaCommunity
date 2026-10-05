package com.quanta.demo0.content.exception;
import com.quanta.demo0.platform.common.exception.BaseException;


import com.quanta.demo0.platform.security.context.BaseContext;

/**
 * 内容域业务失败异常。
 *
 * 继承 BaseException（RuntimeException），由全局异常处理器统一转成错误响应；
 * 语义是"业务规则不允许继续"，而不是程序故障 —— 典型如参数/docId 校验不过
 * （BotContentSyncServiceImpl、ContentEventProducer）、目标内容不存在等。
 *
 * ============================================================
 * 【为什么 answer / comment / feed 等包也在直接抛这个 content 包的异常？】
 * ============================================================
 * 它们继承的是同一个 BaseException，而 BaseException 本身不携带错误码，
 * 只是"带 message 的运行时异常"。为"校验失败"这种高频场景，各域没有
 * 各自造一套异常家族，而是共用这个最具体的域异常 —— **在错误码体系补齐之前，
 * 统一异常类型 + 清晰 message 是成本最低的一致性方案**；将来要按域细分时，
 * 从它派生即可，调用方的 try/catch 语义不受影响（catch 的是同一个基类）。
 */
public class ContentFailedException extends BaseException {
    /**
     * 用失败说明构造异常；message 会原样进入全局处理后的错误响应。
     */
    public ContentFailedException(String message) {
        super(message);
    }
}

package com.quanta.demo0.search.exception;
import com.quanta.demo0.platform.common.exception.BaseException;


/**
 * 搜索域业务异常（参数非法、ES 写入失败等）。
 * 继承 BaseException（RuntimeException），由 GlobalExceptionHandler
 * 统一兜底为 code=500 的 Result 返回。
 */
public class SearchFailedException extends BaseException {
    public SearchFailedException(String message) {
        super(message);
    }
}

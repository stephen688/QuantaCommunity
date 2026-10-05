package com.quanta.demo0.feed.controller.user;

import com.quanta.demo0.content.controller.user.ContentController;
import com.quanta.demo0.feed.exception.RecommendSessionExpiredException;
import com.quanta.demo0.platform.common.result.Result;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 推荐专属生命周期错误：限定推荐门面，保留全局旧业务异常响应。 */
@RestControllerAdvice(assignableTypes = {ContentController.class, RecommendExposureController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RecommendProtocolAdvice {
    /** 过期游标必须由用户刷新开启新轮，不能静默重建并跳过页面。 */
    @ExceptionHandler(RecommendSessionExpiredException.class)
    public ResponseEntity<Result<Void>> expired(RecommendSessionExpiredException exception) {
        return ResponseEntity.status(409).body(Result.error(409, exception.getMessage()));
    }
}

package com.quanta.demo0.feed.controller.user;

import com.quanta.demo0.content.exception.ContentFailedException;
import com.quanta.demo0.feed.dto.RecommendExposureDTO;
import com.quanta.demo0.feed.dto.RecommendVisitor;
import com.quanta.demo0.feed.properties.RecommendProperties;
import com.quanta.demo0.feed.service.RecommendExposureService;
import com.quanta.demo0.platform.common.result.Result;
import com.quanta.demo0.platform.security.context.BaseContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

/** 推荐曝光门面：允许游客，认证身份优先；Redis写入和归属校验由领域服务承担。 */
@RestController
@RequestMapping("/content/recommend")
@RequiredArgsConstructor
public class RecommendExposureController {
    private final RecommendExposureService exposureService;
    private final RecommendProperties properties;

    /** 批量回传真实进入屏幕的内容；接口返回不代表完整推荐链路健康。 */
    @PostMapping("/exposures")
    public Result<Void> record(
            @Valid @RequestBody RecommendExposureDTO batch,
            @RequestHeader(value = "X-Guest-Id", required = false) String guestId) {
        if (!properties.getDiscovery().isEnabled()) {
            throw new ContentFailedException("推荐发现暂未开启");
        }
        RecommendVisitor visitor = RecommendVisitor.from(BaseContext.getCurrentId(), guestId);
        exposureService.record(visitor, batch.getFeedSessionId(), batch.getContentIds());
        return Result.success();
    }

    /** 新曝光接口参数错误使用HTTP及body 400，不改变旧内容接口约定。 */
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, ContentFailedException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> invalidBatch(Exception exception) {
        return Result.error(400, exception instanceof ContentFailedException
                ? exception.getMessage() : "推荐曝光参数无效");
    }
}

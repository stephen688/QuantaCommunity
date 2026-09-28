package com.quanta.demo0.controller.bot;

import com.quanta.demo0.dto.BotProfileEventDTO;
import com.quanta.demo0.content.exception.ContentFailedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import com.quanta.demo0.platform.common.result.Result;
import com.quanta.demo0.service.ExplicitPreferenceService;
import com.quanta.demo0.service.TopicCatalog;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** BOT 画像接口门面：沿用 /bot/** 的服务身份校验；只提交主题事实，不直接操作缓存/MQ。 */
@RestController
@RequestMapping("/bot/profile")
@PreAuthorize("hasRole('BOT')")
@RequiredArgsConstructor
public class BotProfileController {
    private final ExplicitPreferenceService service;
    /** 返回主服务唯一词表，不接收客户端自创标签。 */
    @GetMapping("/topics")
    public Result<List<TopicCatalog.Topic>> topics() {
        return Result.success(TopicCatalog.topics());
    }
    /** 返回是否新增事实；false 表示同 ID 同内容已持久化，而不是请求失败。 */
    @PostMapping("/events")
    public Result<Boolean> accept(@Valid @RequestBody BotProfileEventDTO event) {
        return Result.success(service.accept(event));
    }

    /** 新服务契约参数失败同时使用 HTTP 400 与 Result.code=400，不改变其它旧接口的响应约定。 */
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, ContentFailedException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> invalidEvent(Exception exception) {
        return Result.error(400, "显式偏好事件格式、目标用户或事件内容无效");
    }
}

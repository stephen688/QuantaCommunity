package com.quanta.demo0.handler;


import com.quanta.demo0.enums.AuditStatus;
import com.quanta.demo0.exception.*;
import com.quanta.demo0.result.Result;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 全局异常处理器，处理项目中抛出的业务异常
 */
@RestControllerAdvice//控制器增强，捕获异常并返回json数据
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 捕获业务异常
     * @param ex
     * @return
     */
    // 1. 登录失败 → 400
    @ExceptionHandler(LoginFailedException.class)
    public Result<Void> handleLoginFailedException(LoginFailedException ex){
        log.error("登录失败：{}", ex.getMessage());
        return Result.error(400, ex.getMessage());
    }

    // 2. 认证失败 → 401
    @ExceptionHandler(AuthFailedException.class)
    public Result<Void> handleAuthFailedException(AuthFailedException ex){
        log.error("认证失败：{}", ex.getMessage());
        return Result.error(401, ex.getMessage());
    }

    // 3. 资源未找到 → 404
    @ExceptionHandler(NoFoundException.class)
    public Result<Void> handleNoFoundException(NoFoundException ex){
        log.error("资源未找到：{}", ex.getMessage());
        return Result.error(404, ex.getMessage());
    }

    // 4. 评论失败 → 400
    @ExceptionHandler(CommentFailedException.class)
    public Result<Void> handleCommentFailedException(CommentFailedException ex){
        log.error("评论失败：{}", ex.getMessage());
        return Result.error(400, ex.getMessage());
    }

    // 5. 内容异常 → 400
    @ExceptionHandler(ContentFailedException.class)
    public Result<Void> handleContentFailedException(ContentFailedException ex){
        log.error("内容异常：{}", ex.getMessage());
        return Result.error(400, ex.getMessage());
    }
    // 5.1 搜索失败 → 400
    @ExceptionHandler(SearchFailedException.class)
    public Result<Void> handleSearchFailedException(SearchFailedException ex){
        log.error("搜索失败：{}", ex.getMessage());
        return Result.error(400, ex.getMessage());
    }

    // 5.2 @Valid / @RequestBody 参数校验失败 → 400
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public Result<Void> handleValidationException(Exception ex) {
        String msg = "参数校验失败";
        if (ex instanceof MethodArgumentNotValidException manv) {
            msg = manv.getBindingResult().getFieldErrors().stream()
                    .map(err -> err.getDefaultMessage() != null ? err.getDefaultMessage() : err.getField())
                    .findFirst()
                    .orElse(msg);
        } else if (ex instanceof BindException bind) {
            msg = bind.getBindingResult().getFieldErrors().stream()
                    .map(err -> err.getDefaultMessage() != null ? err.getDefaultMessage() : err.getField())
                    .findFirst()
                    .orElse(msg);
        }
        log.warn("参数校验失败：{}", msg);
        return Result.error(400, msg);
    }

    // 5.3 请求体缺失或 JSON 格式错误 → 400
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        log.warn("请求体解析失败：{}", ex.getMessage());
        return Result.error(400, "请求体格式错误");
    }

    // 5.4 上传文件超过大小限制 → 400
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex) {
        log.warn("上传文件过大：{}", ex.getMessage());
        return Result.error(400, "图片大小不能超过10MB");
    }

    // 6. 其他业务异常 → 500
    @ExceptionHandler(BaseException.class)
    public Result<Void> handleBaseException(BaseException ex){
        log.error("业务异常：{}", ex.getMessage());
        return Result.error(500, ex.getMessage());
    }

    // 7. 系统异常 → 500
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception ex){
        log.error("系统异常：{}", ex.getMessage());
        ex.printStackTrace();
        return Result.error(500, "系统繁忙，请稍后再试");
    }

    //8.用户信息异常 → 400
    @ExceptionHandler(UserInfoFailedException.class)
    public Result<Void> handleUserInfoFailedException(UserInfoFailedException ex) {
        log.error("用户信息异常：{}", ex.getMessage());
        return Result.error(400, ex.getMessage());
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, ConversionFailedException.class})
    public Result<Void> handleParamConvertException(Exception ex) {
        String allowed = Arrays.stream(AuditStatus.values())
                .map(Enum::name)
                .collect(Collectors.joining(", "));
        return Result.error(400, "auditStatus 参数非法，可选值: " + allowed);
    }

    /**
     * 限流必须同时返回HTTP 429和Result.code=429。
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public Result<Void> handleRateLimitExceeded(
            RateLimitExceededException exception,
            HttpServletResponse response
    ) {
        response.setStatus(429);
        response.setHeader(
                "Retry-After",
                String.valueOf(exception.getRetryAfterSeconds())
        );

        return Result.error(429, exception.getMessage());
    }



}

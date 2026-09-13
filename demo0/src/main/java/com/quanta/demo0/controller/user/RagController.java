package com.quanta.demo0.controller.user;

import com.quanta.demo0.exception.RagRetrieveException;
import com.quanta.demo0.rag.generation.RagSearchService;
import com.quanta.demo0.rag.model.RagSearchRequest;
import com.quanta.demo0.rag.model.RagSearchResponse;
import com.quanta.demo0.result.Result;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RAG 搜索控制器
 * ============================
 * 作用说明
 * ============================
 * 这个类对外暴露"搜索+AI总结"接口，统一输入校验与输出封装。
 * 在 RAG 架构中的角色：
 *   前端请求 → RagController（参数校验） → RagSearchService（编排） → 返回 RagSearchResponse
 * ============================
 * 接口说明
 * ============================
 * POST /rag/search
 *   入参：RagSearchRequest（query、contentType、enableAi 等）
 *   出参：Result<RagSearchResponse>（AI 总结 + 帖子列表）
 * ============================
 * 异常处理策略
 * ============================
 * 检索失败：抛出 RagRetrieveException，由全局异常处理器兜底
 * AI 失败：不影响主流程，仅置 aiAnswer.enabled=false
 * 参数校验失败：由 @Valid 注解自动拦截，返回 400 错误
 */
@RestController
@RequestMapping("/rag")
@Slf4j
public class RagController {

    @Autowired
    private RagSearchService ragSearchService;

    @PostMapping("/search")
    public Result<RagSearchResponse> searchWithAi(@Valid @RequestBody RagSearchRequest request) {
        log.info("[RAG-CONTROLLER] 收到 RAG 搜索请求: query='{}', contentType={}, enableAi={}",
                request.getQuery(), request.getContentType(), request.getEnableAi());

        try {
            RagSearchResponse response = ragSearchService.searchWithAi(request);

            if (response == null) {
                log.warn("[RAG-CONTROLLER] RAG 功能未开启");
                return Result.error("RAG 功能未开启");
            }

            log.info("[RAG-CONTROLLER] RAG 搜索成功: 返回 {} 条帖子, aiAnswer={}",
                    response.getList() != null ? response.getList().size() : 0,
                    response.getAiAnswer() != null && response.getAiAnswer().getEnabled());

            return Result.success(response);

        } catch (RagRetrieveException e) {
            // 检索失败，返回业务错误码
            log.error("[RAG-CONTROLLER] 检索失败: {}", e.getMessage());
            return Result.error(e.getMessage());
        } catch (Exception e) {
            // 其他异常，由全局异常处理器兜底
            log.error("[RAG-CONTROLLER] RAG 搜索异常", e);
            return Result.error("RAG 搜索失败: " + e.getMessage());
        }

    }
}

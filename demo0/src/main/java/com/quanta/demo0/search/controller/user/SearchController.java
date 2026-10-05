package com.quanta.demo0.search.controller.user;

import com.quanta.demo0.search.dto.SearchDTO;
import com.quanta.demo0.platform.common.result.Result;
import com.quanta.demo0.search.service.ContentSearchService;
import com.quanta.demo0.search.service.SearchService;
import com.quanta.demo0.content.vo.ContentVO;
import com.quanta.demo0.platform.common.result.PageVO;
import com.quanta.demo0.search.vo.SearchTrendingVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * 搜索域用户侧入口：内容搜索、搜索历史管理、热门发现聚合。
 *
 * ============================================================
 * 【为什么 Controller 只做绑定和日志，校验全下沉？】
 * ============================================================
 * 关键词为空/超长、contentType 非法等校验都在 ContentSearchServiceImpl
 * 里抛 SearchFailedException，而不是写在 @RequestParam 校验或这里的手工 if：
 * 同一份校验口径可以同时服务 Controller 和 RAG 等其他 Service 调用方，
 * Controller 保持"薄壳"，换前端或加调用方时不需要同步改参数规则。
 */
@RestController
@RequestMapping("/search")
@Slf4j
public class SearchController {

    @Autowired
    private ContentSearchService contentSearchService;

    @Autowired
    private SearchService searchService;
    /**
     * 搜索接口
        * @param searchDTO 搜索参数，包括关键词、搜索类型（内容、用户、话题）等
     * @return 搜索结果列表
     */
    @GetMapping("/content")
    public Result<PageVO<ContentVO>> searchContent(@ModelAttribute SearchDTO searchDTO) {
        // @ModelAttribute 把查询串（?keyword=&contentType=&current=&pageSize=）逐字段绑进 SearchDTO，
        // 省去逐个 @RequestParam；缺省/非法值的兜底在 Service 层完成。
        log.info("搜索内容：{}", searchDTO);
       PageVO<ContentVO> pageVO= contentSearchService.searchContent(searchDTO);
        return Result.success(pageVO);
    }


    /**
     * 查询搜索历史的关键词
     *
     * <p>用户维度取自登录态（BaseContext），接口不接收 userId 参数，
     * 天然杜绝"看别人的搜索历史"。</p>
     */
    @GetMapping("/history/keywords")
    public Result<List<String>> getSearchHistoryKeywords() {
        log.info("查询搜索历史关键词");
        List<String> keywords = searchService.getSearchHistoryKeywords();
        return Result.success(keywords);
    }
    /**
     * 清空我的搜索历史
     *
     * <p>DELETE 语义对应软删（is_deleted=1），SQL 细节见 SearchMapper.softDeleteAllByUserId。</p>
     */
   @DeleteMapping("/history/clear")
    public Result clearSearchHistory() {
        log.info("清空搜索历史");
        searchService.clearSearchHistory();
        return Result.success();
    }

    /**
     * 单个删除搜索历史记录
     *
     * <p>id 走路径变量；"记录不存在"与"删别人的记录"都会在 Service 层
     * 统一抛 SearchFailedException（rows=0），防止越权探测。</p>
     */
    @DeleteMapping("/history/deleteOne/{id}")
    public Result deleteOneSearchHistory(@PathVariable Long id) {
        log.info("删除搜索历史记录：{}", id);
        searchService.deleteOneSearchHistory(id);
        return Result.success();
    }
    /**
     * 获取搜索热门发现（聚合：热门关键词 + 热门问题 + 热门校友）
     *
     * <p>一次请求刷完发现页三张榜，走 SearchServiceImpl.getTrending 的两级缓存
     * （L1 Caffeine 10s / L2 Redis 300s±60s），高频访问不会打到 MySQL。</p>
     */
    @GetMapping("/trending")
    public Result<SearchTrendingVO> getTrending() {
        log.info("获取搜索热门发现");
        SearchTrendingVO vo = searchService.getTrending();
        return Result.success(vo);
    }

}

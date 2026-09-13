package com.quanta.demo0.controller.user;

import com.quanta.demo0.dto.SearchDTO;
import com.quanta.demo0.result.Result;
import com.quanta.demo0.service.ContentService;
import com.quanta.demo0.service.SearchService;
import com.quanta.demo0.vo.ContentVO;
import com.quanta.demo0.vo.PageVO;
import com.quanta.demo0.vo.SearchTrendingVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/search")
@Slf4j
public class SearchController {

    @Autowired
    private ContentService contentService;

    @Autowired
    private SearchService searchService;
    /**
     * 搜索接口
        * @param searchDTO 搜索参数，包括关键词、搜索类型（内容、用户、话题）等
     * @return 搜索结果列表
     */
    @GetMapping("/content")
    public Result<PageVO<ContentVO>> searchContent(@ModelAttribute SearchDTO searchDTO) {
        log.info("搜索内容：{}", searchDTO);
       PageVO<ContentVO> pageVO= contentService.searchContent(searchDTO);
        return Result.success(pageVO);
    }


    /**
     * 查询搜索历史的关键词
     */
    @GetMapping("/history/keywords")
    public Result<List<String>> getSearchHistoryKeywords() {
        log.info("查询搜索历史关键词");
        List<String> keywords = searchService.getSearchHistoryKeywords();
        return Result.success(keywords);
    }
    /**
     * 清空我的搜索历史
     */
   @DeleteMapping("/history/clear")
    public Result clearSearchHistory() {
        log.info("清空搜索历史");
        searchService.clearSearchHistory();
        return Result.success();
    }

    /**
     * 单个删除搜索历史记录
     */
    @DeleteMapping("/history/deleteOne/{id}")
    public Result deleteOneSearchHistory(@PathVariable Long id) {
        log.info("删除搜索历史记录：{}", id);
        searchService.deleteOneSearchHistory(id);
        return Result.success();
    }
    /**
     * 获取搜索热门发现（聚合：热门关键词 + 热门问题 + 热门校友）
     */
    @GetMapping("/trending")
    public Result<SearchTrendingVO> getTrending() {
        log.info("获取搜索热门发现");
        SearchTrendingVO vo = searchService.getTrending();
        return Result.success(vo);
    }

}

package com.quanta.demo0.controller.user;

import com.quanta.demo0.dto.FollowStateDTO;
import com.quanta.demo0.dto.FollowFeedQueryDTO;
import com.quanta.demo0.result.Result;
import com.quanta.demo0.result.ScrollResult;
import com.quanta.demo0.service.FollowService;
import com.quanta.demo0.vo.FollowResultVO;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/follow")
@Slf4j
public class FollowController {

    @Autowired
    private FollowService followService;
    // 关注/取关用户
    @PostMapping("/{id}")
    public Result<FollowResultVO> follow(@PathVariable Long id,
                                         @Valid @RequestBody FollowStateDTO stateDTO) {
        log.info("关注用户：{}，关注状态：{}", id, stateDTO.getFollowed());

        FollowResultVO followResultVO = followService.follow(id, stateDTO.getFollowed());
        return Result.success(followResultVO);


    }

    @GetMapping("/feed")
    public Result<ScrollResult> getFollowFeed(@ModelAttribute FollowFeedQueryDTO followFeedQueryDTO) {
        log.info("获取关注用户动态,参数:{}", followFeedQueryDTO);
        ScrollResult scrollResult = followService.getFollowFeed(followFeedQueryDTO);
        return Result.success(scrollResult);
    }

}

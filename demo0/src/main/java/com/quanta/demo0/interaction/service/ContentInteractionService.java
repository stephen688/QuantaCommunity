package com.quanta.demo0.interaction.service;

import com.quanta.demo0.interaction.vo.CollectResultVO;
import com.quanta.demo0.interaction.vo.LikeResultVO;

public interface ContentInteractionService {

    LikeResultVO likeContent(Long contentId, boolean liked);

    CollectResultVO collect(Long contentId, boolean collected);
}

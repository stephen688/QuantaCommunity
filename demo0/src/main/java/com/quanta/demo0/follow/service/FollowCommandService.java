package com.quanta.demo0.follow.service;

import com.quanta.demo0.follow.vo.FollowResultVO;

public interface FollowCommandService {
    FollowResultVO follow(Long id, boolean followed);
}

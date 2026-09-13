package com.quanta.demo0.vo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 热门校友 VO（搜索发现页）
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class HotAlumniVO implements Serializable {

    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 用户昵称
     */
    private String nickName;

    /**
     * 用户头像
     */
    private String avatarUrl;
}
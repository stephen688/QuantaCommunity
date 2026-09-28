package com.quanta.demo0.content.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** bot 视角的帖子主楼摘要（C-2①）。 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BotPostVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long postId;
    private Long userId;
    private String title;
    private String content;
}

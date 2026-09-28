package com.quanta.demo0.comment.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/** chain 接口响应：主楼摘要和触发评论直接回复链。 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BotCommentChainVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private BotPostVO post;
    private List<BotCommentNodeVO> chain;
}

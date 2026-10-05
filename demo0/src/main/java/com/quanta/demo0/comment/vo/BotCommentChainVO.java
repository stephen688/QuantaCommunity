package com.quanta.demo0.comment.vo;

import com.quanta.demo0.content.vo.BotPostVO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/** chain 接口响应：主楼摘要和触发评论直接回复链。 */
/**
 * 【契约设计：为什么 chain 顺带带上主楼 post？】
 * QuantaBot 回帖前必须知道"这层楼挂在哪个帖子下面、楼主在问什么"——
 * 单独再调一次帖子接口就多一次跨服务往返和一致性问题（主楼可能恰被修改）。
 * **把回答所需的最小上下文一次给齐**：post（BotPostVO，四个字段的裁剪版主楼）
 * + chain（从主楼方向到触发评论的时间正序节点链）。
 * 装配逻辑在 BotCommentServiceImpl.getChain：链沿 replyCommentId（缺省退 parentId）
 * 向上回溯，深度上限 50 层，且只包含可见（audit_status=1、is_deleted=0）节点。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BotCommentChainVO implements Serializable {

    private static final long serialVersionUID = 1L;

    // 帖子主楼摘要（postId/userId/title/content），由 comment 域经
    // ContentQueryService.getContentSnapshot 装配，客户端传值无意义。
    private BotPostVO post;

    // 触发评论及其全部祖先：orderedChain 迭代的是链栈（head 先出），
    // 顺序为最早祖先在前、触发评论在最后，即时间正序。
    private List<BotCommentNodeVO> chain;
}

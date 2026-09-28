package com.quanta.demo0.comment.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/** bot 视角的评论节点，字段名与 QuantaBot CommentNode 契约一致。 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BotCommentNodeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long commentId;
    private Long parentId;
    private Long replyCommentId;
    private Long userId;
    private String content;
    private List<String> images;
    private String createTime;
}

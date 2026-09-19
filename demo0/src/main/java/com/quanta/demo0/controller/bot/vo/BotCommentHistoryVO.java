package com.quanta.demo0.controller.bot.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/** history 接口响应：用户在某帖下的可见评论分页。 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BotCommentHistoryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<BotCommentNodeVO> list;
    private long total;
}

package com.quanta.demo0.controller.bot.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/** tree 接口响应：帖子全部可见楼层分页。 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BotCommentTreeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private long total;
    private List<BotCommentNodeVO> list;
}

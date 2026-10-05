package com.quanta.demo0.comment.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 一级评论分页查询入参，服务 GET /comment/list。
 *
 * 【字段上没有默认值和校验注解，规则全在服务端】commentPage 第 1 步归一化：
 * pageNum/pageSize 非正数回落 1/10，sortType 非 1 非 2 回落 1；contentId 为 null
 * 直接抛异常。参数对象保持"哑"状态，保证同一条规则只在一处生效。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommentPageDTO implements Serializable {
    // 所属内容 ID，必填（commentPage 第 1 步校验为空即 400）。
    private Long contentId;
    // 专业区（contentType=2）必传且回答须属于该内容；生活区不传。
    private Long answerId;
    private Integer pageNum;
    private Integer pageSize;
    // 落到 SQL：sortType=2 时 like_count DESC, create_time DESC，否则 create_time DESC
    //（selectFirstLevelComments 的 choose 分支）。注意与二级回复 ReplyPageDTO 的
    // sortType（时间正/倒序）语义不同。
    private Integer sortType; // 1:时间倒序 2:点赞倒序
}

package com.quanta.demo0.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContentDTO implements Serializable {

    /**
     * 内容类型：1-专业问答 2-生活求助
     */
    private Integer contentType;

    /**
     * 问题标题（限制 50 字以内）
     */
    private String title;

    /**
     * 问题描述（限制 500 字以内）
     */
    private String content;

    /**
     * 图片列表（最多 5 张）
     */
    private List<String>images;

}

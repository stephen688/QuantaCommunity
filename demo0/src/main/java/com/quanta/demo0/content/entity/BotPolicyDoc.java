package com.quanta.demo0.content.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 政策文档实体（C-3 POLICY 内容源）。 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BotPolicyDoc implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String docId;
    private String title;
    private String content;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}

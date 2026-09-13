package com.quanta.demo0.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SearchHistory implements Serializable {
    private Long id;
    private Long userId;
    private String keyword;
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
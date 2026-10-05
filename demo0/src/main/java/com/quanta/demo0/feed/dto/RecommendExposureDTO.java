package com.quanta.demo0.feed.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 推荐可视曝光批次：只接收会话及帖子ID，主体和时间均由服务器确定。 */
@Data
public class RecommendExposureDTO {
    @NotBlank @Size(max = 36)
    private String feedSessionId;
    @NotEmpty @Size(max = 50)
    private List<@jakarta.validation.constraints.NotNull @Positive Long> contentIds;
}

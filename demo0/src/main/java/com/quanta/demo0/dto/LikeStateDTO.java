package com.quanta.demo0.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class LikeStateDTO {
    @NotNull(message = "liked不能为空")
    private Boolean liked;
}

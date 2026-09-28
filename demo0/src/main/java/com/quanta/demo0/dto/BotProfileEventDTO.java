package com.quanta.demo0.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.List;

/** BOT 偏好快照请求：只传受控主题与事件标识，不接收记忆原文或前端用户身份。 */
@Data
public class BotProfileEventDTO {
    @NotBlank @Pattern(regexp = "[0-9a-fA-F-]{32,36}")
    private String eventId;
    @NotNull @Positive
    private Long userId;
    @NotBlank @Pattern(regexp = "[0-9a-fA-F-]{32,36}")
    private String memoryId;
    @NotBlank @Size(max = 64)
    private String personaVersion;
    /** 同一记忆变更的 UTC 微秒版本；重试不能改变。 */
    @NotNull @Positive
    private Long revision;
    @NotBlank @Pattern(regexp = "UPSERT|DELETE")
    private String operation;
    @NotNull @Size(max = 3)
    private List<@NotBlank String> topics;
    @Pattern(regexp = "positive|negative")
    private String valence;
}

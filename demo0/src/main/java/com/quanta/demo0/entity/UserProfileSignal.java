package com.quanta.demo0.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 显式偏好事实：追加式事件留痕；当前状态按每个记忆的最高 revision 计算，DELETE 保留墓碑。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileSignal {
    private Long id;
    private String eventId;
    private Long userId;
    private String memoryId;
    private String personaVersion;
    private Long revision;
    private String operation;
    /** 只保存受控主题 ID 数组，不存原文。 */
    private String topics;
    private String valence;
}

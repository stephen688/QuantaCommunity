package com.quanta.demo0.service.Impl;

import com.quanta.demo0.entity.BaseContext;
import com.quanta.demo0.properties.AliyunModerationProperties;
import com.quanta.demo0.properties.QuantabotProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * C-6 双层审核：bot 评论强制进 AI 机审。
 * 只豁免 targets.comment.enabled 分区开关，仍尊重全局总闸。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommentServiceImplBotModerationTest {

    @Mock
    private AliyunModerationProperties moderationProperties;

    @Mock
    private AliyunModerationProperties.Targets targets;

    @Mock
    private AliyunModerationProperties.TargetConfig commentConfig;

    @InjectMocks
    private CommentServiceImpl service;

    private final QuantabotProperties quantabotProperties =
            new QuantabotProperties();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(
                service,
                "quantabotProperties",
                quantabotProperties
        );
        when(moderationProperties.isEnabled()).thenReturn(true);
        when(moderationProperties.getTargets()).thenReturn(targets);
        when(targets.getComment()).thenReturn(commentConfig);
        when(commentConfig.isEnabled()).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        BaseContext.removeCurrentId();
    }

    @Test
    void bot评论_分区关闭仍强制机审() {
        BaseContext.setCurrentId(10000L);

        assertTrue(service.shouldModerateComment());
    }

    @Test
    void 普通用户评论_分区关闭不机审() {
        BaseContext.setCurrentId(3L);

        assertFalse(service.shouldModerateComment());
    }

    @Test
    void 普通用户评论_分区开启机审() {
        BaseContext.setCurrentId(3L);
        when(commentConfig.isEnabled()).thenReturn(true);

        assertTrue(service.shouldModerateComment());
    }

    @Test
    void 全局总闸关闭_bot也不机审() {
        BaseContext.setCurrentId(10000L);
        when(moderationProperties.isEnabled()).thenReturn(false);

        assertFalse(service.shouldModerateComment());
    }
}

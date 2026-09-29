package com.quanta.demo0.platform.mq.service.impl;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平台 Outbox 服务架构约束：通用持久化实现不得依赖业务实体。
 */
class OutboxEventServiceArchitectureTest {

    @Test
    void genericOutboxImplementationDoesNotAcceptBusinessEntities() {
        assertThat(Arrays.stream(OutboxEventServiceImpl.class.getDeclaredMethods())
                .flatMap(method -> Arrays.stream(method.getParameterTypes())))
                .noneMatch(type -> type.getPackageName().startsWith("com.quanta.demo0.content.entity"))
                .noneMatch(type -> type.getPackageName().startsWith("com.quanta.demo0.answer.entity"))
                .noneMatch(type -> type.getPackageName().startsWith("com.quanta.demo0.comment.entity"));
    }
}

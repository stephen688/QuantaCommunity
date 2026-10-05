package com.quanta.demo0.content.admin;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import com.quanta.demo0.platform.audit.annotation.AdminAudit;
import com.quanta.demo0.platform.audit.constant.AdminAuditActionConstants;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 政策知识库管理 API 契约测试。
 *
 * <p>先锁定管理入口必须存在且只暴露源文档管理职责；具体业务行为由服务测试覆盖。</p>
 */
class PolicyDocAdminApiContractTest {

    @Test
    void adminPolicyDocControllerExposesSourceDocumentEndpoints() throws Exception {
        Class<?> controller = loadController();

        assertThat(controller).as("政策知识库管理控制器必须存在").isNotNull();
        if (controller == null) {
            return;
        }

        RequestMapping baseMapping = controller.getAnnotation(RequestMapping.class);
        assertThat(baseMapping)
                .as("管理接口必须挂在 /admin/knowledge/policy-docs 下")
                .isNotNull();
        assertThat(baseMapping.value()).containsExactly("/admin/knowledge/policy-docs");

        Method page = findMethod(controller, "page", com.quanta.demo0.content.dto.PolicyDocAdminQueryDTO.class);
        assertThat(page)
                .as("必须支持分页查询")
                .isNotNull();
        assertThat(page.getAnnotation(GetMapping.class).value()).containsExactly("/page");
        assertThat(page.getAnnotation(PreAuthorize.class).value()).contains("OPERATIONS_ADMIN");
        Method detail = findMethod(controller, "detail", String.class);
        assertThat(detail).as("必须支持源文档详情").isNotNull();
        assertThat(detail.getAnnotation(GetMapping.class).value()).containsExactly("/{docId}");
        assertThat(detail.getAnnotation(PreAuthorize.class).value()).contains("OPERATIONS_ADMIN");
        Method upsert = findMethod(controller, "upsert", com.quanta.demo0.content.dto.BotPolicyDocDTO.class);
        assertThat(upsert)
                .as("必须复用既有政策文档写入 DTO")
                .isNotNull();
        assertThat(upsert.getAnnotation(PostMapping.class).value()).isEmpty();
        assertThat(upsert.getAnnotation(PreAuthorize.class).value())
                .contains("OPERATIONS_ADMIN");
        assertThat(upsert.getAnnotation(AdminAudit.class).action())
                .isEqualTo(AdminAuditActionConstants.POLICY_DOC_UPSERT);
        Method delete = findMethod(controller, "delete", String.class);
        assertThat(delete).as("必须支持软删除").isNotNull();
        assertThat(delete.getAnnotation(DeleteMapping.class).value()).containsExactly("/{docId}");
        assertThat(delete.getAnnotation(PreAuthorize.class).value()).contains("OPERATIONS_ADMIN");
        assertThat(delete.getAnnotation(AdminAudit.class).action())
                .isEqualTo(AdminAuditActionConstants.POLICY_DOC_DELETE);
    }

    private Class<?> loadController() {
        try {
            return Class.forName("com.quanta.demo0.content.controller.admin.AdminPolicyDocController");
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    private Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            return type.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }
}

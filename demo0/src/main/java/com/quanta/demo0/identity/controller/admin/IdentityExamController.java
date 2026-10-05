package com.quanta.demo0.identity.controller.admin;

import com.quanta.demo0.platform.audit.annotation.AdminAudit;
import com.quanta.demo0.platform.audit.constant.AdminAuditActionConstants;
import com.quanta.demo0.platform.security.constant.PermissionConstants;
import com.quanta.demo0.identity.dto.IdentityAuditDTO;
import com.quanta.demo0.identity.dto.IdentityExamDTO;
import com.quanta.demo0.platform.common.result.PageResult;
import com.quanta.demo0.platform.common.result.Result;
import com.quanta.demo0.identity.service.IdentityExamService;
import com.quanta.demo0.identity.vo.IdentityDetailVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端身份审核工作台（/admin/identityExam/*）。
 *
 * ============================================================
 * 【为什么每个接口都挂 @PreAuthorize，audit 还要再多挂一个 @AdminAudit？】
 * ============================================================
 * 两者管的事完全不同：
 * @PreAuthorize(IDENTITY_AUDIT) 是**准入**——没有该权限的请求根本进不了方法
 * （PermissionConstants.IDENTITY_AUDIT，权限来自登录快照里的角色映射）；
 * @AdminAudit 是**追责**——AOP 切面（AdminAuditAspect）只在业务抛异常时补记失败日志
 * （此时事务已回滚，只能在事务外写"失败审计"），成功记录则由服务层 recordSuccess
 * 在业务事务内显式写入。**失败走切面、成功走事务**，两层合起来才是完整审计链。
 */
@RestController
@RequestMapping("/admin/identityExam")
@Slf4j
public class IdentityExamController {
    @Autowired
    IdentityExamService identityExamService;


    //TODO 修复分页查询的bug

    //分页查询用户身份认证
    @PreAuthorize("hasAuthority('" + PermissionConstants.IDENTITY_AUDIT + "')")
    @GetMapping ("/page")
    public Result<PageResult> page( @ModelAttribute IdentityExamDTO identityExamDTO) {
    log.info("分页查询用户身份认证{},{},{}",identityExamDTO.getPageNum(),identityExamDTO.getPageSize(),identityExamDTO.getAuditStatus());

   PageResult pageResult = identityExamService.pageQuery(identityExamDTO);
   return Result.success(pageResult);
    }

    //根据authId查询用户身份认证的详细信息
    @PreAuthorize("hasAuthority('" + PermissionConstants.IDENTITY_AUDIT + "')")
    @GetMapping("/userAuth/detail/{authId}")
    public Result<IdentityDetailVO> getById(@PathVariable Long authId) {
        log.info("根据authId查询用户身份认证的详细信息：{}", authId);
        IdentityDetailVO identityDetailVO = identityExamService.getDetailById(authId);
        return Result.success(identityDetailVO);
    }


    //通过或驳回用户身份认证
    // targetId 是 SpEL：从入参 DTO 取 authId，审计日志能精确到"审了哪条记录"
    @AdminAudit(
            action = AdminAuditActionConstants.IDENTITY_AUDIT,
            targetType = "IDENTITY_AUTH",
            targetId = "#identityAuditDTO.authId"
    )
    @PreAuthorize("hasAuthority('" + PermissionConstants.IDENTITY_AUDIT + "')")
    @PostMapping("/audit")
    public Result audit(@RequestBody IdentityAuditDTO identityAuditDTO) {
        log.info("审核用户身份认证：{}",identityAuditDTO);

        identityExamService.audit(identityAuditDTO);
        return Result.success();
    }


}

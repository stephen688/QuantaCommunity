package com.quanta.demo0.identity.controller.admin;

import com.quanta.demo0.platform.audit.annotation.AdminAudit;
import com.quanta.demo0.platform.audit.constant.AdminAuditActionConstants;
import com.quanta.demo0.platform.security.constant.PermissionConstants;
import com.quanta.demo0.identity.dto.IdentityAuditDTO;
import com.quanta.demo0.identity.dto.IdentityExamDTO;
import com.quanta.demo0.platform.common.result.PageResult;
import com.quanta.demo0.platform.common.result.Result;
import com.quanta.demo0.service.IdentityExamService;
import com.quanta.demo0.identity.vo.IdentityDetailVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

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

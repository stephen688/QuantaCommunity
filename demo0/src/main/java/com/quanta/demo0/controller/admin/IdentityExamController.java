package com.quanta.demo0.controller.admin;

import com.quanta.demo0.annotation.AdminAudit;
import com.quanta.demo0.constant.AdminAuditActionConstants;
import com.quanta.demo0.constant.PermissionConstants;
import com.quanta.demo0.dto.IdentityAuditDTO;
import com.quanta.demo0.dto.IdentityExamDTO;
import com.quanta.demo0.result.PageResult;
import com.quanta.demo0.result.Result;
import com.quanta.demo0.service.IdentityExamService;
import com.quanta.demo0.vo.IdentityDetailVO;
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

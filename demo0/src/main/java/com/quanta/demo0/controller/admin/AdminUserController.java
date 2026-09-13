package com.quanta.demo0.controller.admin;

import com.quanta.demo0.annotation.AdminAudit;
import com.quanta.demo0.constant.AdminAuditActionConstants;
import com.quanta.demo0.constant.PermissionConstants;
import com.quanta.demo0.dto.UserAdminQueryDTO;
import com.quanta.demo0.result.PageResult;
import com.quanta.demo0.result.Result;
import com.quanta.demo0.service.AdminUserService;
import com.quanta.demo0.vo.AdminUserDetailVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端 - 用户管理控制器
 * 路径前缀：/admin/user
 */
@RestController
@RequestMapping("/admin/user")
@Slf4j
public class AdminUserController {

    @Autowired
    private AdminUserService adminUserService;

    /**
     * 分页查询用户列表
     * 接口说明：
     * - 路径：GET /admin/user/page
     * - 权限：需要 USER_READ_ADMIN 权限（方法级 @PreAuthorize 校验）
     * - 支持按账号状态、是否管理员、昵称模糊搜索筛选
     * 请求参数（Query Param）：
     * - pageNum：页码，默认 1
     * - pageSize：每页数量，默认 10
     * - accountStatus：可选，账号状态（0-正常 1-封禁）
     * - isAdmin：可选，是否管理员（0-否 1-是）
     * - nickName：可选，昵称模糊搜索
     * 返回数据：
     * - total：总记录数
     * - records：当前页用户列表（User 实体）
     * 示例请求：
     * GET /admin/user/page?pageNum=1&pageSize=10&accountStatus=0
     */

    @PreAuthorize("hasAuthority('" + PermissionConstants.USER_READ_ADMIN + "')")
    @GetMapping("/page")
    public Result<PageResult> page(UserAdminQueryDTO query) {
        log.info("管理端分页查询用户，查询条件：{}", query);
        PageResult pageResult = adminUserService.pageQuery(query);
        return Result.success(pageResult);
    }


    /**
     * 查询用户详情
     * 接口说明：
     * - 路径：GET /admin/user/{id}
     * - 权限：仅管理员可访问
     * - 返回用户完整信息（含管理员标识、账号状态等）
     * 路径参数：
     * - id：用户 ID
     * 返回数据：
     * - AdminUserDetailVO 对象
     * 示例请求：
     * GET /admin/user/1
     */
    @PreAuthorize("hasAuthority('" + PermissionConstants.USER_READ_ADMIN + "')")
    @GetMapping("/{id}")
    public Result<AdminUserDetailVO> getById(@PathVariable Long id) {
        log.info("管理端查询用户详情，userId={}", id);
        AdminUserDetailVO detail = adminUserService.getDetailById(id);
        return Result.success(detail);
    }


    /**
     * 封禁用户
     *
     * 接口说明：
     * - 路径：POST /admin/user/ban
     * - 权限：仅管理员可访问
     * - 封禁后用户无法登录，已登录的会被强制下线（清理 Redis token）
     *
     * 请求体（JSON）：
     * - userId：用户 ID
     *
     * 示例请求：
     * POST /admin/user/ban
     * {
     *   "userId": 2
     * }
     */
    @AdminAudit(
            action = AdminAuditActionConstants.USER_BAN,
            targetType = "USER",
            targetId = "#userId"
    )
    @PreAuthorize("hasAuthority('" + PermissionConstants.USER_BAN + "')")
    @PostMapping("/ban/{userId}")
    public Result ban(@PathVariable Long userId) {
        log.info("管理端封禁用户，userId={}", userId);
        adminUserService.banUser(userId);
        return Result.success();
    }


    /**
     * 解封用户
     * 接口说明：
     * - 路径：POST /admin/user/unban/{userId}
     * - 权限：仅管理员可访问
     * - 解封后用户可以正常登录
     * 路径参数：
     * - userId：用户 ID
     * 示例请求：
     * POST /admin/user/unban/2
     */
    @AdminAudit(
            action = AdminAuditActionConstants.USER_UNBAN,
            targetType = "USER",
            targetId = "#userId"
    )
    @PreAuthorize("hasAuthority('" + PermissionConstants.USER_BAN + "')")
    @PostMapping("/unban/{userId}")
    public Result unban(@PathVariable Long userId) {
        log.info("管理端解封用户，userId={}", userId);
        adminUserService.unbanUser(userId);
        return Result.success();
    }
}

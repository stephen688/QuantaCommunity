package com.quanta.demo0.controller.user;

import com.quanta.demo0.constant.JwtClaimsConstant;
import com.quanta.demo0.dto.UserAuthDTO;
import com.quanta.demo0.dto.UserInfoDTO;
import com.quanta.demo0.dto.UserLoginDTO;
import com.quanta.demo0.entity.BaseContext;
import com.quanta.demo0.entity.User;
import com.quanta.demo0.entity.UserAuth;
import com.quanta.demo0.enums.AuditStatus;
import com.quanta.demo0.exception.AuthFailedException;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.properties.JwtProperties;
import com.quanta.demo0.result.Result;
import com.quanta.demo0.security.AuthenticatedUser;
import com.quanta.demo0.service.ContentService;
import com.quanta.demo0.service.UserService;
import com.quanta.demo0.utils.JwtUtil;
import com.quanta.demo0.vo.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.quanta.demo0.constant.RedisConstants.LOGIN_USER_KEY;
import static com.quanta.demo0.constant.RedisConstants.LOGIN_USER_TTL;

@Tag(name = "用户模块", description = "用户信息相关接口")
@RestController
@RequestMapping("/user")
@Slf4j
public class UserController {

    @Autowired
    private UserService userService;
    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ContentService contentService;


     //TODO：增加一个回收站功能，给用户删除的帖子一个恢复期，过了恢复期才真正删除，期间用户可以在回收站恢复帖子，或者彻底删除帖子
     //TODO:禁言功能





    /**
     * 微信登录接口
     */
    @PostMapping("/login")
    public Result<UserLoginVO> login(@RequestBody UserLoginDTO userLoginDTO) {
        log.info("微信登录，code 已接收，携带昵称={}", userLoginDTO.getNickName() != null);
        //1.获取openid和id
        User user = userService.weChatLogin(userLoginDTO);

        //2.生成jwt令牌
        //生成令牌(通过id生成）
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.USER_ID, user.getId());
        //判断账号状态，如果被封禁，抛出异常
        if (user.getAccountStatus() != null && user.getAccountStatus() == 1) {
            throw new AuthFailedException("账号已被封禁");
        }

        String token = JwtUtil.createJWT(jwtProperties.getUserSecretKey(), jwtProperties.getUserTtl(), claims);

        //3.存储token到redis，设置过期时间（到期时间则会自动删除，用户无法）
        String loginKey = LOGIN_USER_KEY + user.getId();
        stringRedisTemplate.opsForValue().set(loginKey, token, LOGIN_USER_TTL, TimeUnit.DAYS);

        //4.。封装并返回
        UserLoginVO userLoginVO = UserLoginVO.builder()
                .id(user.getId())
                .openid(user.getOpenid())
                .token(token)
                .nickName(user.getNickName())
                .avatarUrl(user.getAvatarUrl())
                .build();

        return Result.success(userLoginVO);
    }


    /**
     * 查询（回显）用户基本信息
     */
    @GetMapping("/info")
    public Result<UserInfoVO> getUserInfo() {
        log.info("查询回显用户基本信息，当前用户id：{}", BaseContext.getCurrentId());
        UserInfoVO userInfoVO = userService.getById(BaseContext.getCurrentId());
        return Result.success(userInfoVO);
    }

    /**
     * 更新用户基本信息
     */
    @PutMapping("/info/update")
    public Result updateUserInfo(@RequestBody UserInfoDTO userInfoDTO) {
        log.info("更新用户基本信息：{}", userInfoDTO);
        userService.updateUserInfo(userInfoDTO);
        return Result.success();

    }

    /**
     * 新增用户认证信息
     */
    @PostMapping("/auth/add")
    public Result<UserAuth> addUserAuth(@RequestBody UserAuthDTO userAuthDTO) {
        log.info("新增用户认证信息：{}", userAuthDTO);
        UserAuth userAuth = userService.addUserAuth(userAuthDTO);
        return Result.success(userAuth);
    }

    /**
     * 查询用户认证状态
     */
    @GetMapping("/auth/status")
    public Result<UserAuthStatusVO> getAuthStatus() {
        log.info("查询用户认证状态，当前用户id：{}", BaseContext.getCurrentId());
        UserAuthStatusVO authStatus = userService.getAuthStatus(BaseContext.getCurrentId());
        return Result.success(authStatus);
    }

    /**
     * 查询用户认证详情
     */
    @GetMapping("/auth/detail")
    public Result<UserAuth> getAuthDetail() {
        log.info("查询用户认证详情，当前用户id：{}", BaseContext.getCurrentId());
        UserAuth userAuth = userService.getAuthDetail(BaseContext.getCurrentId());
        return Result.success(userAuth);
    }


    /**
     * 查询我发布的帖子(普通分页）
     * 不传状态默认查询全部，传了状态则查询对应状态的帖子
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/content/my/list")
    public Result<PageVO<ContentVO>> getMyContentList(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) AuditStatus auditStatus) {
        log.info("查询我的帖子，当前用户id：{}，分页参数：current={},size={}", BaseContext.getCurrentId(), current, size);
        Long userId = BaseContext.getCurrentId();
        PageVO<ContentVO> pageVO = contentService.getMyContentList(userId, current, size, auditStatus);
        return Result.success(pageVO);

    }

    /**
     * 查询我点赞的帖子
     */
    @GetMapping("/content/my/liked")
    public Result<PageVO<ContentVO>> getMyLikedContentList(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size) {
        log.info("查询我点赞的帖子，当前用户id：{}，分页参数：current={},size={}", BaseContext.getCurrentId(), current, size);
        Long userId = BaseContext.getCurrentId();
        PageVO<ContentVO> pageVO = contentService.getMyLikedContentList(userId, current, size);

        return Result.success(pageVO);
    }

    /**
     * 查询我收藏的帖子
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/content/my/collect")
    public Result<PageVO<ContentVO>> getMyCollectContentList(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size) {
        log.info("查询我收藏的帖子，当前用户id：{}，分页参数：current={},size={}", BaseContext.getCurrentId(), current, size);
        Long userId = BaseContext.getCurrentId();
        PageVO<ContentVO> pageVO = contentService.getMyCollectContentList(userId, current, size);

        return Result.success(pageVO);
    }

    /**
     * 查询我浏览过的帖子
     */
    @GetMapping("/content/my/browseHistory")
    public Result<PageVO<ContentVO>> getMyBrowseHistoryContentList(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size) {
        log.info("查询我浏览过的帖子，当前用户id：{}，分页参数：current={},size={}", BaseContext.getCurrentId(), current, size);
        Long userId = BaseContext.getCurrentId();
        PageVO<ContentVO> pageVO = contentService.getMyBrowseHistoryContentList(userId, current, size);
        return Result.success(pageVO);
    }

    /**
     * 清空浏览记录
     */
    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/browse/history/clear")
    public Result<Void> clearBrowseHistory() {
        Long userId = BaseContext.getCurrentId();
        contentService.clearBrowseHistory(userId);
        return Result.success();
    }
    /**
     * 查询用户主页信息（C 端公开字段）
     * 包含：用户基本信息、认证信息、关注数据、关系状态
     */
    @GetMapping("/{userId}/profile")
    public Result<UserProfileVO> getUserProfile(@PathVariable Long userId) {
        log.info("查询用户主页信息，targetUserId={}", userId);
        Long viewerId = BaseContext.getCurrentId();
        UserProfileVO userProfile = userService.getUserProfile(userId, viewerId);
        return Result.success(userProfile);
    }

    /**
     * 查询用户已发布的帖子列表（分页）
     * 仅返回审核通过且未删除的帖子
     */
    @GetMapping("/{userId}/contents")
    public Result<PageVO<ContentVO>> getUserContents(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size) {
        log.info("查询用户帖子列表，userId={}, current={}, size={}", userId, current, size);
        PageVO<ContentVO> pageVO = contentService.pageUserPublicContents(userId, current, size);
        return Result.success(pageVO);
    }


    /**
     * 退出登录
     */
    @PostMapping("/logout")
    public Result<Void> logout() {
        log.info("退出登录，userId={}", BaseContext.getCurrentId());
        userService.logout();
        return Result.success();
    }

    /**
     * 查询当前用户由后端确认的角色和权限。
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/security-context")
    public Result<SecurityContextVO> getSecurityContext(
            Authentication authentication
    ) {
        AuthenticatedUser currentUser =
                (AuthenticatedUser) authentication.getPrincipal();

        SecurityContextVO securityContextVO =
                SecurityContextVO.builder()
                        .userId(currentUser.getUserId())
                        .roles(currentUser.getRoles())
                        .authorities(currentUser.getAuthorities())
                        .verified(currentUser.getVerified())
                        .build();

        return Result.success(securityContextVO);
    }
}

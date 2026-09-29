package com.quanta.demo0.user.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.quanta.demo0.content.service.ContentQueryService;
import com.quanta.demo0.follow.service.FollowQueryService;
import com.quanta.demo0.identity.enums.UserAuthDisplayStatus;
import com.quanta.demo0.identity.service.IdentityQueryService;
import com.quanta.demo0.identity.vo.UserAuthStatusVO;
import com.quanta.demo0.user.mapper.UserMapper;
import com.quanta.demo0.platform.common.enums.AuditStatus;
import com.quanta.demo0.platform.common.exception.NoFoundException;
import com.quanta.demo0.platform.security.context.BaseContext;
import com.quanta.demo0.moderation.utils.SensitiveWordChecker;
import com.quanta.demo0.user.dto.UserInfoDTO;
import com.quanta.demo0.user.entity.User;
import com.quanta.demo0.user.exception.UserInfoFailedException;
import com.quanta.demo0.user.service.UserQueryService;
import com.quanta.demo0.user.service.UserProfileService;
import com.quanta.demo0.user.service.UserReadCacheInvalidator;
import com.quanta.demo0.user.vo.UserAuthInfoVO;
import com.quanta.demo0.user.vo.UserInfoVO;
import com.quanta.demo0.user.vo.UserProfileVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 用户资料服务实现。
 *
 * 负责用户资料写入、公开主页聚合和认证展示态修复；登录会话及认证申请分别由
 * SessionService 和 IdentityService 负责，关注、内容和认证读取通过公开查询端口完成。
 */
@Service
@RequiredArgsConstructor
public class UserProfileServiceImpl implements UserProfileService {

    private final UserMapper userMapper;
    private final UserQueryService userQueryService;
    private final SensitiveWordChecker sensitiveWordChecker;
    private final UserReadCacheInvalidator userReadCacheInvalidator;
    private final IdentityQueryService identityQueryService;
    private final FollowQueryService followQueryService;
    private final ContentQueryService contentQueryService;

    /**
     * 根据用户 ID 查询基本资料，并隐藏不存在或封禁账号。
     */
    @Override
    public UserInfoVO getById(Long id) {
        User user = userMapper.getById(id);
        if (user == null || Integer.valueOf(1).equals(user.getAccountStatus())) {
            throw new NoFoundException("用户不存在");
        }
        return UserInfoVO.builder()
                .nickName(user.getNickName())
                .avatarUrl(user.getAvatarUrl())
                .build();
    }

    /**
     * 更新当前用户的昵称和头像，校验敏感词、长度和 URL 后写入数据库。
     */
    @Override
    public void updateUserInfo(UserInfoDTO userInfoDTO) {
        if (userInfoDTO == null
                || ((userInfoDTO.getNickName() == null || userInfoDTO.getNickName().trim().isEmpty())
                && (userInfoDTO.getAvatarUrl() == null || userInfoDTO.getAvatarUrl().trim().isEmpty()))) {
            throw new UserInfoFailedException("昵称和头像不能同时为空");
        }
        if (userInfoDTO.getNickName() != null && !userInfoDTO.getNickName().trim().isEmpty()) {
            String nickName = userInfoDTO.getNickName().trim();
            if (nickName.length() > 20) {
                throw new UserInfoFailedException("昵称不能超过 20 个字符");
            }
            userInfoDTO.setNickName(sensitiveWordChecker.replaceSensitiveWords(nickName));
        }
        if (userInfoDTO.getAvatarUrl() != null && !userInfoDTO.getAvatarUrl().trim().isEmpty()) {
            String avatarUrl = userInfoDTO.getAvatarUrl().trim();
            if (!avatarUrl.startsWith("http://") && !avatarUrl.startsWith("https://")) {
                throw new UserInfoFailedException("头像 URL 格式不正确");
            }
            userInfoDTO.setAvatarUrl(avatarUrl);
        }

        User user = BeanUtil.copyProperties(userInfoDTO, User.class);
        user.setId(BaseContext.getCurrentId());
        user.setUpdateTime(LocalDateTime.now());
        if (userMapper.updateById(user) == 0) {
            throw new UserInfoFailedException("更新用户信息失败");
        }
        userReadCacheInvalidator.evictAuthorAfterCommit(user.getId());
    }

    /**
     * 聚合用户公开资料、关注关系和公开帖子计数。
     */
    @Override
    public UserProfileVO getUserProfile(Long targetUserId, Long viewerId) {
        if (targetUserId == null) {
            throw new NoFoundException("用户 ID 不能为空");
        }

        UserAuthInfoVO userInfo = userQueryService.getUserAuthInfo(targetUserId);
        if (userInfo == null || Integer.valueOf(1).equals(userInfo.getAccountStatus())) {
            throw new NoFoundException("用户不存在");
        }

        Integer followingCount = followQueryService.countFollowing(targetUserId);
        Integer followerCount = followQueryService.countFollowers(targetUserId);
        Integer contentCount = contentQueryService.countUserPublicContents(targetUserId);
        Boolean isFollowed = followQueryService.isFollowing(viewerId, targetUserId);
        Boolean isSelf = viewerId != null && viewerId.equals(targetUserId);
        Integer authStatus = resolveAuthDisplayStatus(targetUserId, userInfo.getAuthStatus());

        return UserProfileVO.builder()
                .userId(userInfo.getUserId())
                .nickName(userInfo.getNickName())
                .avatarUrl(userInfo.getAvatarUrl())
                .authStatus(authStatus)
                .quantaDepartment(userInfo.getQuantaDepartment())
                .quantaBatch(userInfo.getQuantaBatch())
                .followingCount(followingCount != null ? followingCount : 0)
                .followerCount(followerCount != null ? followerCount : 0)
                .contentCount(contentCount != null ? contentCount : 0)
                .isFollowed(isFollowed)
                .isSelf(isSelf)
                .build();
    }

    private Integer resolveAuthDisplayStatus(Long userId, Integer storedDisplayStatus) {
        UserAuthStatusVO userAuthStatus = identityQueryService.getAuthStatus(userId);
        UserAuthDisplayStatus expected = expectedDisplayFromAuditStatus(
                userAuthStatus == null ? null : userAuthStatus.getAuditStatus());
        int code = expected.getCode();
        if (!Objects.equals(storedDisplayStatus, code)) {
            syncUserAuthDisplayStatus(userId, expected);
        }
        return code;
    }

    private UserAuthDisplayStatus expectedDisplayFromAuditStatus(Integer auditStatus) {
        if (Objects.equals(auditStatus, AuditStatus.APPROVED.getCode())) {
            return UserAuthDisplayStatus.VERIFIED;
        }
        if (Objects.equals(auditStatus, AuditStatus.REJECTED.getCode())) {
            return UserAuthDisplayStatus.REJECTED;
        }
        if (Objects.equals(auditStatus, AuditStatus.PENDING.getCode())) {
            return UserAuthDisplayStatus.PENDING;
        }
        return UserAuthDisplayStatus.NONE;
    }

    private void syncUserAuthDisplayStatus(Long userId, UserAuthDisplayStatus displayStatus) {
        if (userId == null || displayStatus == null) {
            return;
        }
        User patch = new User();
        patch.setId(userId);
        patch.setAuthStatus(displayStatus.getCode());
        patch.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(patch);
    }
}

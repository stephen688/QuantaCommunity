package com.quanta.demo0.user.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.alibaba.fastjson.JSONObject;
import com.quanta.demo0.mapper.UserMapper;
import com.quanta.demo0.moderation.utils.SensitiveWordChecker;
import com.quanta.demo0.user.dto.UserLoginDTO;
import com.quanta.demo0.user.entity.User;
import com.quanta.demo0.user.exception.LoginFailedException;
import com.quanta.demo0.user.properties.WeChatProperties;
import com.quanta.demo0.user.service.UserAccountService;
import com.quanta.demo0.user.service.UserReadCacheInvalidator;
import com.quanta.demo0.user.utils.HttpClientUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static com.quanta.demo0.platform.common.constant.SystemConstant.USER_NICK_NAME_PREFIX;

/**
 * 用户账号服务实现。
 *
 * 负责微信 code 换取账号、测试登录账号和登录资料同步；不负责 JWT 会话写入，
 * 会话边界由 platform/security 的 SessionService 承担。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAccountServiceImpl implements UserAccountService {

    /** 微信登录接口。 */
    public static final String WX_LOGIN = "https://api.weixin.qq.com/sns/jscode2session";

    private final WeChatProperties weChatProperties;
    private final UserMapper userMapper;
    private final SensitiveWordChecker sensitiveWordChecker;
    private final UserReadCacheInvalidator userReadCacheInvalidator;

    /**
     * 读取账号封禁状态。
     */
    @Override
    public Integer getAccountStatus(Long userId) {
        return userMapper.getAccountStatusById(userId);
    }

    /**
     * 微信登录：测试 code 走既有联调账号，其他 code 调用微信接口并创建/读取账号。
     */
    @Override
    public User weChatLogin(UserLoginDTO userLoginDTO) {
        String code = userLoginDTO == null ? null : userLoginDTO.getCode();
        if (code == null || code.trim().isEmpty()) {
            throw new LoginFailedException("微信登录凭证不能为空");
        }
        code = code.trim();

        if ("test".equals(code)) {
            return loginTestUser("test_openid_123456", "测试用户", userLoginDTO);
        }
        if ("test2".equals(code)) {
            return loginTestUser("test_openid_2", "测试用户B", userLoginDTO);
        }

        Map<String, String> map = new HashMap<>();
        map.put("appid", weChatProperties.getAppid());
        map.put("secret", weChatProperties.getSecret());
        map.put("js_code", userLoginDTO.getCode());
        map.put("grant_type", "authorization_code");
        String json = HttpClientUtil.doGet(WX_LOGIN, map);

        JSONObject jsonObject;
        try {
            jsonObject = JSONObject.parseObject(json);
        } catch (Exception exception) {
            log.error("微信登录返回非JSON，body={}", json);
            throw new LoginFailedException("微信登录失败：响应格式异常");
        }
        String openid = jsonObject.getString("openid");
        Integer errcode = jsonObject.getInteger("errcode");
        String errmsg = jsonObject.getString("errmsg");
        if (errcode != null) {
            log.error("微信登录失败，errcode={}, errmsg={}", errcode, errmsg);
            throw new LoginFailedException("微信登录失败：" + errmsg);
        }
        if (openid == null) {
            log.error("微信登录失败，openid为空，完整返回：{}", json);
            throw new LoginFailedException("微信登录失败");
        }

        User user = userMapper.getByOpenid(openid);
        boolean isNewUser = user == null;
        String weChatNickName = sanitizeLoginNickName(userLoginDTO.getNickName());
        String weChatAvatarUrl = sanitizeLoginAvatarUrl(userLoginDTO.getAvatarUrl());
        if (isNewUser) {
            String nickName = weChatNickName != null
                    ? weChatNickName
                    : USER_NICK_NAME_PREFIX + RandomUtil.randomString(8);
            user = User.builder()
                    .openid(openid)
                    .nickName(nickName)
                    .avatarUrl(weChatAvatarUrl)
                    .createTime(LocalDateTime.now())
                    .updateTime(LocalDateTime.now())
                    .build();
            userMapper.insert(user);
        } else {
            syncWeChatProfileOnLogin(user, userLoginDTO, false);
        }
        return user;
    }

    private User loginTestUser(String openid, String defaultNickName, UserLoginDTO userLoginDTO) {
        User user = userMapper.getByOpenid(openid);
        boolean isNewUser = user == null;
        if (isNewUser) {
            user = getOrCreateTestUser(openid, defaultNickName);
        }
        syncWeChatProfileOnLogin(user, userLoginDTO, isNewUser);
        return user;
    }

    private User getOrCreateTestUser(String openid, String nickName) {
        User user = userMapper.getByOpenid(openid);
        if (user == null) {
            user = User.builder()
                    .openid(openid)
                    .nickName(nickName)
                    .createTime(LocalDateTime.now())
                    .updateTime(LocalDateTime.now())
                    .build();
            userMapper.insert(user);
        }
        return user;
    }

    private void syncWeChatProfileOnLogin(User user, UserLoginDTO userLoginDTO, boolean isNewUser) {
        String nickName = sanitizeLoginNickName(userLoginDTO.getNickName());
        String avatarUrl = sanitizeLoginAvatarUrl(userLoginDTO.getAvatarUrl());
        if (nickName == null && avatarUrl == null) {
            return;
        }

        User update = User.builder().id(user.getId()).updateTime(LocalDateTime.now()).build();
        boolean changed = false;
        if (isNewUser) {
            if (nickName != null) {
                user.setNickName(nickName);
                update.setNickName(nickName);
                changed = true;
            }
            if (avatarUrl != null) {
                user.setAvatarUrl(avatarUrl);
                update.setAvatarUrl(avatarUrl);
                changed = true;
            }
        } else {
            if (nickName != null && isPlaceholderNickName(user.getNickName())) {
                user.setNickName(nickName);
                update.setNickName(nickName);
                changed = true;
            }
            if (avatarUrl != null && (user.getAvatarUrl() == null || user.getAvatarUrl().trim().isEmpty())) {
                user.setAvatarUrl(avatarUrl);
                update.setAvatarUrl(avatarUrl);
                changed = true;
            }
        }
        if (changed) {
            userMapper.updateById(update);
            userReadCacheInvalidator.evictAuthorAfterCommit(user.getId());
        }
    }

    private String sanitizeLoginNickName(String nickName) {
        if (nickName == null || nickName.trim().isEmpty()) {
            return null;
        }
        nickName = nickName.trim();
        if (nickName.length() > 20) {
            throw new LoginFailedException("昵称不能超过 20 个字符");
        }
        return sensitiveWordChecker.replaceSensitiveWords(nickName);
    }

    private String sanitizeLoginAvatarUrl(String avatarUrl) {
        if (avatarUrl == null || avatarUrl.trim().isEmpty()) {
            return null;
        }
        avatarUrl = avatarUrl.trim();
        if (!avatarUrl.startsWith("http://") && !avatarUrl.startsWith("https://")) {
            throw new LoginFailedException("头像 URL 格式不正确");
        }
        return avatarUrl;
    }

    private boolean isPlaceholderNickName(String nickName) {
        return nickName == null
                || nickName.isEmpty()
                || nickName.startsWith(USER_NICK_NAME_PREFIX)
                || "测试用户".equals(nickName)
                || "测试用户B".equals(nickName);
    }
}

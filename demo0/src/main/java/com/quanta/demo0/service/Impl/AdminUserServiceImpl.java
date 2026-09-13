package com.quanta.demo0.service.Impl;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.quanta.demo0.constant.AdminAuditActionConstants;
import com.quanta.demo0.constant.RedisConstants;
import com.quanta.demo0.dto.UserAdminQueryDTO;
import com.quanta.demo0.entity.User;
import com.quanta.demo0.exception.NoFoundException;
import com.quanta.demo0.mapper.UserMapper;
import com.quanta.demo0.result.PageResult;
import com.quanta.demo0.service.AdminAuditRecorder;
import com.quanta.demo0.service.AdminUserService;
import com.quanta.demo0.vo.AdminUserDetailVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static com.quanta.demo0.constant.RedisConstants.LOGIN_USER_KEY;

/**
 * 管理端用户服务实现类。
 *
 * 核心职责：
 * 1. 提供用户分页查询与详情查询能力，支撑后台用户管理页面；
 * 2. 处理封禁/解封等账号状态变更，并同步清理登录态；
 * 3. 统一校验用户存在性，避免对无效用户执行管理操作。
 *
 * 设计说明：
 * - 读操作以 Mapper 直查数据库为主，保证管理端数据实时性；
 * - 状态变更后配合 Redis 登录缓存处理，确保账号管控即时生效。
 */
@Slf4j
@Service
public class AdminUserServiceImpl implements AdminUserService {

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;


    @Autowired
    private AdminAuditRecorder adminAuditRecorder;

    /**
     * 分页查询用户列表
     * 执行流程：
     * 1. PageHelper.startPage() 开启分页（拦截下一次查询）
     * 2. 调用 Mapper 执行 SQL 查询
     * 3. PageHelper 自动拦截并包装结果为 Page 对象
     * 4. 从 Page 对象提取 total 和 records，封装成 PageResult 返回
     * @param query 查询条件
     * @return 分页结果
     */
    @Override
    public PageResult pageQuery(UserAdminQueryDTO query) {
        // 开启分页，拦截下一次 SQL 查询
        PageHelper.startPage(query.getPageNum(), query.getPageSize());

        // 执行查询，PageHelper 会自动包装为 Page 对象
        Page<User> page = (Page<User>) userMapper.pageAdmin(query);

        // 封装分页结果
        return new PageResult(page.getTotal(), page.getResult());
    }



    /**
     * 查询用户详情
     * 执行流程：
     * 1. 根据 userId 查询 User 实体
     * 2. 判断用户是否存在，不存在抛异常
     * 3. 将 User 转换为 AdminUserDetailVO 返回
     * @param userId 用户 ID
     * @return 用户详情
     */
    @Override
    public AdminUserDetailVO getDetailById(Long userId) {
        User user = userMapper.getById(userId);
        if (user == null) {
            throw new NoFoundException("用户不存在");
        }
        return AdminUserDetailVO.builder()
                .id(user.getId())
                .openid(user.getOpenid())
                .nickName(user.getNickName())
                .avatarUrl(user.getAvatarUrl())
                .authStatus(user.getAuthStatus())
                .isAdmin(user.getIsAdmin())
                .accountStatus(user.getAccountStatus())
                .createTime(user.getCreateTime())
                .updateTime(user.getUpdateTime())
                .build();
    }

    /**
     * 封禁用户
     * 执行流程：
     * 1. 校验用户是否存在
     * 2. 更新 account_status = 1（封禁）
     * 3. 删除 Redis 中的登录态（强制下线）
     * 为什么能做到强制下线？因为用户登录时会在 Redis 中存储一个 token，封禁后删除这个 token，用户再次请求时会发现 token 无效，从而无法继续使用系统。
     * @param userId 用户 ID
     */
    @Transactional
    @Override
    public void banUser(Long userId) {
        // 校验用户是否存在
        User user = userMapper.getById(userId);
        if (user == null) {
            throw new NoFoundException("用户不存在");
        }

        // 更新账号状态为封禁
        User updateUser = new User();
        updateUser.setId(userId);
        updateUser.setAccountStatus(1);
        updateUser.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(updateUser);

        // 删除 Redis 登录态，强制下线
        String loginKey = LOGIN_USER_KEY + userId;
        stringRedisTemplate.delete(loginKey);


        // 写入封禁标记到 Redis（拦截器优先读此 key）
        String bannedKey = RedisConstants.USER_BANNED_KEY + userId;
        stringRedisTemplate.opsForValue().set(bannedKey, "1");

        log.info("封禁用户成功，userId={}", userId);


        //审计记录成功
        adminAuditRecorder.recordSuccess(
                AdminAuditActionConstants.USER_BAN,
                "USER",
                String.valueOf(userId),
                "accountStatus=" + user.getAccountStatus(),
                "accountStatus=1"
        );



        // 从粉丝排行 ZSET 中移除
        stringRedisTemplate.opsForZSet().remove(RedisConstants.USER_FOLLOWER_RANK_KEY, userId.toString());

        log.info("封禁用户成功，userId={}", userId);
    }

    /**
     * 解封用户
     * 执行流程：
     * 1. 校验用户是否存在
     * 2. 更新 account_status = 0（正常）
     *
     * @param userId
     */
    @Transactional
    @Override
    public void unbanUser(Long userId) {
        // 校验用户是否存在
        User user = userMapper.getById(userId);
        if (user == null) {
            throw new NoFoundException("用户不存在");
        }

        // 更新账号状态为正常
        User updateUser = new User();
        updateUser.setId(userId);
        updateUser.setAccountStatus(0);
        updateUser.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(updateUser);


       // 清除封禁标记
        String bannedKey = RedisConstants.USER_BANNED_KEY + userId;
        stringRedisTemplate.delete(bannedKey);


        log.info("解封用户成功，userId={}", userId);

        //审计记录解封成功
        adminAuditRecorder.recordSuccess(
                AdminAuditActionConstants.USER_UNBAN,
                "USER",
                String.valueOf(userId),
                "accountStatus=" + user.getAccountStatus(),
                "accountStatus=0"
        );

    }

}
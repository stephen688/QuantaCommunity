package com.quanta.demo0.user.service.impl;

import com.quanta.demo0.user.entity.User;
import com.quanta.demo0.user.mapper.UserMapper;
import com.quanta.demo0.user.service.UserQueryService;
import com.quanta.demo0.user.vo.UserAccountVO;
import com.quanta.demo0.user.vo.UserAuthInfoVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

/** 用户域事实查询实现：只访问本域 Mapper，不承担会话或缓存失效。 */
@Service
@RequiredArgsConstructor
public class UserQueryServiceImpl implements UserQueryService {
    private final UserMapper userMapper;

    /** {@inheritDoc} */
    @Override
    public UserAccountVO getAccount(Long userId) {
        User user = userMapper.getById(userId);
        return user == null ? null : UserAccountVO.builder().id(user.getId())
                .openid(user.getOpenid()).nickName(user.getNickName()).avatarUrl(user.getAvatarUrl())
                .accountStatus(user.getAccountStatus()).isDeleted(user.getIsDeleted()).build();
    }

    /** {@inheritDoc} */
    @Override
    public UserAuthInfoVO getUserAuthInfo(Long userId) {
        return userMapper.selectUserAuthInfoById(userId);
    }

    /** {@inheritDoc} */
    @Override
    public List<UserAuthInfoVO> getUserAuthInfos(List<Long> userIds) {
        return userMapper.selectUserAuthInfoByIds(userIds);
    }

    /** {@inheritDoc} */
    @Override
    public List<UserAuthInfoVO> getTopFollowedUsers(int limit) {
        return userMapper.selectTopFollowedUsers(limit);
    }
}

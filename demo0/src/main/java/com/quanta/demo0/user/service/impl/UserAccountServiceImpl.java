package com.quanta.demo0.user.service.impl;

import com.quanta.demo0.mapper.UserMapper;
import com.quanta.demo0.user.service.UserAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserAccountServiceImpl implements UserAccountService {

    private final UserMapper userMapper;

    @Override
    public Integer getAccountStatus(Long userId) {
        return userMapper.getAccountStatusById(userId);
    }
}

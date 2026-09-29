package com.quanta.demo0.user.service;

import com.quanta.demo0.user.dto.UserAdminQueryDTO;
import com.quanta.demo0.platform.common.result.PageResult;
import com.quanta.demo0.user.vo.AdminUserDetailVO;

public interface AdminUserService {

    PageResult pageQuery(UserAdminQueryDTO query);

    AdminUserDetailVO getDetailById(Long id);

    void banUser(Long userId);

    void unbanUser(Long userId);
}

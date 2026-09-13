package com.quanta.demo0.service;

import com.quanta.demo0.dto.UserAdminQueryDTO;
import com.quanta.demo0.result.PageResult;
import com.quanta.demo0.vo.AdminUserDetailVO;

public interface AdminUserService {

    PageResult pageQuery(UserAdminQueryDTO query);

    AdminUserDetailVO getDetailById(Long id);

    void banUser(Long userId);

    void unbanUser(Long userId);
}

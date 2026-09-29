package com.quanta.demo0.user.service;

import com.quanta.demo0.user.vo.UserAuthInfoVO;
import com.quanta.demo0.user.vo.UserAccountVO;
import java.util.List;

/** 用户域事实查询端口，跨域调用者只接收 VO，不访问用户 Mapper/Entity。 */
public interface UserQueryService {
    /** 查询账号事实，不存在时返回 null。 */
    UserAccountVO getAccount(Long userId);
    /** 查询单个作者的公开资料及认证展示状态。 */
    UserAuthInfoVO getUserAuthInfo(Long userId);
    /** 一次数据库查询批量读取作者资料。 */
    List<UserAuthInfoVO> getUserAuthInfos(List<Long> userIds);
    /** 热门校友兜底查询。 */
    List<UserAuthInfoVO> getTopFollowedUsers(int limit);
}

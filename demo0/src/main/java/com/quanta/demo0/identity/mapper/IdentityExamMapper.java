package com.quanta.demo0.identity.mapper;

import com.github.pagehelper.Page;
import com.quanta.demo0.identity.dto.IdentityExamDTO;
import com.quanta.demo0.identity.vo.IdentityExamVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 管理端审核列表查询 Mapper，唯一的 SQL 在 resources/mapper/identity/IdentityExamMapper.xml。
 *
 * 【说明】XML 里的旧注释写着"联表SQL"，实际是 tb_user_auth 单表查询（别名 ua）——
 * 列表页只需要认证记录自身的六个字段，不用联 tb_user；昵称头像在详情接口
 * （IdentityExamServiceImpl#getDetailById）才去 user 域补齐。
 *
 * 【为什么返回 Page 而不是 List？】服务层先调 PageHelper.startPage(pageNum, pageSize)，
 * 它靠 ThreadLocal 拦截**紧随其后的第一条 SQL** 拼上 LIMIT，并把总数塞回这里
 * 返回的 Page 对象；随后 new PageResult(page.getTotal(), page.getResult())
 * 就能组装出分页出参——所以 list() 必须紧跟 startPage 调用，中间不能插别的查询。
 */
@Mapper
public interface IdentityExamMapper {



    /**
     * 按审核状态 + 申请时间区间分页拉取认证申请，按 create_time 倒序（最新申请排最前）。
     *
     * 【坑】startTime/endTime 过滤的是 tb_user_auth.create_time；DTO 里是 LocalDate，
     * endTime 表示"含当天"，XML 用 DATE_ADD(#{endTime}, INTERVAL 1 DAY) 实现闭区间。
     */
    Page<IdentityExamVO> list(IdentityExamDTO identityExamDTO);
}

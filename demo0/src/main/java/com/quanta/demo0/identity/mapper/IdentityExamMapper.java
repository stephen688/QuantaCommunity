package com.quanta.demo0.identity.mapper;

import com.github.pagehelper.Page;
import com.quanta.demo0.identity.dto.IdentityExamDTO;
import com.quanta.demo0.identity.vo.IdentityExamVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface IdentityExamMapper {



    Page<IdentityExamVO> list(IdentityExamDTO identityExamDTO);
}

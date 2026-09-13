package com.quanta.demo0.mapper;

import com.github.pagehelper.Page;
import com.quanta.demo0.dto.IdentityExamDTO;
import com.quanta.demo0.vo.IdentityExamVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface IdentityExamMapper {



    Page<IdentityExamVO> list(IdentityExamDTO identityExamDTO);
}

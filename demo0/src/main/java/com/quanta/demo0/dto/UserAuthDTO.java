package com.quanta.demo0.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UserAuthDTO implements Serializable {

    //序列化版本号，确保反序列化时类的一致性
    private static final long serialVersionUID = 1L;


    /**
     * 身份类型：1-在校成员 2-历届校友
     */
    private Integer identityType;


    /**
     * 真实姓名
     */
    private String realName;


    /**
     * 学号
     */
    private String schoolId;


    /**
     * 届数
     */
    private String quantaBatch;


    /**
     * 部门
     */
    private String quantaDepartment;


}

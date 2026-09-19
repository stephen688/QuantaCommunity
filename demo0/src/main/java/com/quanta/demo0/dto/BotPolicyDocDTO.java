package com.quanta.demo0.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** 政策文档 upsert 请求。 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class BotPolicyDocDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String docId;
    private String title;
    private String content;
}

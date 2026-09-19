package com.quanta.demo0.controller.bot.vo;

import lombok.Data;

import java.io.Serializable;

/** 与 QuantaBot SyncDoc 契约一致的同步文档。 */
@Data
public class BotSyncDocVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String docId;
    private String docKind;
    private Long contentId;
    private Long answerId;
    private String title;
    private String content;
    private String createTime;
    private String updateTime;
    private String updatedAt;
    private String status;
}

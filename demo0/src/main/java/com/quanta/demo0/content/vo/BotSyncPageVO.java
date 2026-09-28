package com.quanta.demo0.content.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/** 内容同步分页响应。 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BotSyncPageVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<BotSyncDocVO> items;
    private boolean hasMore;
}

package com.quanta.demo0.moderation.service;

import com.quanta.demo0.moderation.dto.ModerationTargetQueryDTO;
import com.quanta.demo0.moderation.vo.ModerationRecordVO;

import java.util.List;
import java.util.Map;

public interface AdminModerationService {

    ModerationRecordVO getLatest(String targetType, Long targetId);

    Map<String, ModerationRecordVO> batchLatest(List<ModerationTargetQueryDTO> queries);
}

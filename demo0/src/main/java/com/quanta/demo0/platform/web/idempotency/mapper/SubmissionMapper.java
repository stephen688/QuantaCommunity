package com.quanta.demo0.platform.web.idempotency.mapper;

import com.quanta.demo0.platform.web.idempotency.entity.HttpSubmission;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * HTTP 提交凭证持久化边界。
 *
 * 职责：读写唯一凭证和清理已完成过期记录；边界：不调用领域 Service。
 */
@Mapper
public interface SubmissionMapper {

    /** 按当前用户、固定场景和标准化凭证读取主库事实。 */
    HttpSubmission selectByUserSceneToken(
            @Param("userId") Long userId,
            @Param("scene") String scene,
            @Param("submissionToken") String submissionToken
    );

    /** 插入 PROCESSING 凭证；调用方必须在业务事务内随后更新成功。 */
    int insert(HttpSubmission submission);

    /** 将同一事务中的首次响应和状态写回凭证。 */
    int markSucceeded(
            @Param("id") Long id,
            @Param("responseData") String responseData
    );

    /** 分批删除已成功且超过保留期的凭证。 */
    int deleteExpiredSucceeded(
            @Param("before") LocalDateTime before,
            @Param("limit") int limit
    );
}

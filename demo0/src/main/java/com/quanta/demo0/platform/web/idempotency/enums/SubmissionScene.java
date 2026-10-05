package com.quanta.demo0.platform.web.idempotency.enums;

/**
 * HTTP 提交幂等场景白名单。
 *
 * 职责：把外部路由映射为服务端掌握的固定场景；边界：不接受客户端自定义场景。
 */
public final class SubmissionScene {

    public static final String CONTENT_PUBLISH = "content-publish";
    public static final String ANSWER_PUBLISH = "answer-publish";
    public static final String COMMENT_SEND = "comment-send";

    private SubmissionScene() {
    }

    /** 判断场景是否属于本次 HTTP 幂等能力覆盖范围。 */
    public static boolean isSupported(String scene) {
        return CONTENT_PUBLISH.equals(scene)
                || ANSWER_PUBLISH.equals(scene)
                || COMMENT_SEND.equals(scene);
    }
}

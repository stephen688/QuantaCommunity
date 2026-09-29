package com.quanta.demo0.platform.redis.constant;

public class RedisConstants {

    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 7L;// 7天

    /**
     * 用户校友认证状态缓存。
     *
     * Value：
     * 1 = 已通过认证
     * 0 = 未通过认证
     */
    public static final String SECURITY_VERIFIED_KEY = "security:verified:";

    /**
     * 校友认证状态缓存时间，单位：分钟。
     */
    public static final Long SECURITY_VERIFIED_TTL_MINUTES = 5L;

    //public static final String RECOMMEND_CONTENT_KEY="Content:recommend";
    // 推荐流专用 Key
    public static final String RECOMMEND_ALL_KEY = "content:recommend:all";
    public static final String RECOMMEND_PROFESSIONAL_KEY = "content:recommend:professional";
    public static final String RECOMMEND_LIFE_KEY = "content:recommend:life";

    // 热度流专用 Key
    public static final String RECOMMEND_HOT_ALL_KEY = "content:recommend:hot:all";
    public static final String RECOMMEND_HOT_PROFESSIONAL_KEY = "content:recommend:hot:professional";
    public static final String RECOMMEND_HOT_LIFE_KEY = "content:recommend:hot:life";

    public static final String RECOMMEND_EXPOSED_KEY_PREFIX = "recommend:exposed:";
    public static final long RECOMMEND_EXPOSED_TTL_HOURS = 24L;


    public static final String FEED_ALL_KEY = "feed:all:";                    // 全部关注（混合）
    public static final String FEED_PROFESSIONAL_KEY = "feed:professional:";  // 专业区
    public static final String FEED_LIFE_KEY = "feed:life:";                  // 生活区
    public static final String CONTENT_LIKED_KEY = "content:liked:";
    public static final String COMMENT_LIKED_KEY = "comment:liked:";
    public static final String FOLLOWED_KEY = "follow:";
    public static final String CONTENT_COLLECT_KEY = "content:collect:";

    //回答点赞
    public static final String ANSWER_LIKED_KEY = "answer:liked:";

    // RAG AI 总结缓存
    public static final String RAG_AI_SUMMARY_KEY = "rag:ai:summary:";

    // RAG 向量 chunk 数量记录
    public static final String RAG_VEC_CHUNKS_POST_KEY = "rag:vec:chunks:c:";
    public static final String RAG_VEC_CHUNKS_ANSWER_KEY = "rag:vec:chunks:a:";

    //封禁状态
     public static final String USER_BANNED_KEY = "user:banned:";

    /**
     * 搜索热门发现聚合缓存 Key
     * 格式：search:trending:all
     * Value：JSON 序列化的 SearchTrendingVO
     */
    public static final String SEARCH_TRENDING_ALL_KEY = "search:trending:all";

    /**
     * 帖子详情共享快照缓存 Key 前缀，格式：content:detail:{contentId}。
     */
    public static final String CONTENT_DETAIL_KEY = "content:detail:";

    /**
     * 用户粉丝排行 ZSET Key
     * member：userId（String）
     * score：粉丝数
     */
    public static final String USER_FOLLOWER_RANK_KEY = "user:follower:rank";

    /**
     * 用户行为画像 Hash Key 前缀，格式：user:profile:{userId}。
     * field = 帖子标签名（第一版只有 life / professional，LLM 标签上线后自动扩展）；
     * value = 权重分（HINCRBYFLOAT 累加）。画像为派生快照，事实源在 MySQL 行为数据。
     */
    public static final String USER_PROFILE_KEY = "user:profile:";

    /** 显式偏好快照：独立于行为衰减扫描，__version 仅用于覆盖版本守卫。 */
    public static final String USER_PROFILE_EXPLICIT_KEY = "user:profile-explicit:";

    /**
     * 画像累计行为权重 field（α 动态调整的数据源）。以 "__" 前缀与标签名区隔。
     */
    public static final String USER_PROFILE_TOTAL_FIELD = "__total";

    /*
     * 画像域辅助 key：连字符前缀刻意与 user:profile:{userId} 画像 Hash 隔离，
     * 防止衰减任务 SCAN "user:profile:*" 把 String 类型辅助 key 当 Hash 处理（WRONGTYPE）。
     */
    public static final String USER_PROFILE_SYNC_WATERMARK_KEY = "user:profile-sync:watermark";
    public static final String USER_PROFILE_SYNC_LOCK_KEY = "user:profile-sync:lock";
    public static final String USER_PROFILE_DECAY_LOCK_KEY = "user:profile-decay:lock";

    /*
     * ===== QuantaBot 控制面命名空间（C-7 契约）=====
     *
     * 这些键归 QuantaBot 所有（读写均在 bot 侧，Redis db2）：
     *   - quantabot:switch:kill            kill switch，true=暂停消费
     *   - quantabot:switch:graylist        灰度白名单（SET of userId）
     *   - quantabot:switch:persona_version 人格版本（string）
     *   - quantabot:cost:{yyyyMMdd}        日累计成本
     *
     * demo0 不实现 bot 控制面逻辑，仅在常量层固化命名契约，
     * 保证未来 demo0 侧任何 Redis 使用不会侵入 quantabot: 命名空间。
     * 键值必须与 QuantaBot crosscutting/killswitch.py、budget.py 逐字一致。
     */
    public static final String QUANTABOT_SWITCH_KILL_KEY =
            "quantabot:switch:kill";
    public static final String QUANTABOT_SWITCH_GRAYLIST_KEY =
            "quantabot:switch:graylist";
    public static final String QUANTABOT_SWITCH_PERSONA_VERSION_KEY =
            "quantabot:switch:persona_version";
    public static final String QUANTABOT_COST_KEY_PREFIX =
            "quantabot:cost:";

}

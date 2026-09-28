"""显式主题偏好提取测试：只接受受控词表与明确喜欢/厌恶表达。"""

from quanta_bot.memory.profile_preferences import TopicDefinition, extract_preferences

_TOPICS = (
    TopicDefinition(id="basketball", label="篮球", aliases=("篮球", "打球")),
    TopicDefinition(id="software_technology", label="软件技术", aliases=("编程", "AI 应用")),
    TopicDefinition(id="experience_sharing", label="经验分享", aliases=("经验分享",)),
)


def test_extract_preferences_requires_explicit_positive_expression() -> None:
    """个人事实或单纯提及主题不能被误判成推荐偏好。"""
    assert extract_preferences("我喜欢篮球，也想看编程", "positive", _TOPICS) == (
        "basketball",
        "software_technology",
    )
    assert extract_preferences("我在篮球社团，读计算机专业", "positive", _TOPICS) == ()
    assert extract_preferences("我喜欢篮球，最近在准备考研", "positive", _TOPICS) == ("basketball",)


def test_extract_preferences_requires_matching_negative_expression() -> None:
    """负面偏好只对明确厌恶表达生效，不能因 valence 字段单独放大。"""
    assert extract_preferences("我不喜欢篮球，别再推荐了", "negative", _TOPICS) == ("basketball",)
    assert extract_preferences("我喜欢篮球", "negative", _TOPICS) == ()
    assert extract_preferences("我不喜欢实习加班", "negative", _TOPICS) == ()


def test_extract_preferences_can_infer_user_valence_but_rejects_mixed_polarity() -> None:
    assert extract_preferences("我喜欢篮球", None, _TOPICS) == ("basketball",)
    assert extract_preferences("我不喜欢篮球", None, _TOPICS) == ("basketball",)
    assert extract_preferences("我喜欢篮球但讨厌编程", None, _TOPICS) == ()


def test_negative_preference_does_not_expand_narrow_terms_to_parent_topics() -> None:
    topics = _TOPICS + (
        TopicDefinition(id="career_internship", label="求职实习", aliases=("面试",)),
        TopicDefinition(id="reading_film", label="阅读影视", aliases=("电影",)),
    )
    assert extract_preferences("我讨厌面试", "negative", topics) == ()
    assert extract_preferences("我不喜欢电影", "negative", topics) == ()
    assert extract_preferences("我讨厌求职实习", "negative", topics) == ("career_internship",)


def test_extract_preferences_keeps_catalog_order_and_limits_three_topics() -> None:
    """多个命中按受控词表顺序去重并最多返回三个主题。"""
    topics = _TOPICS + (TopicDefinition(id="course_study", label="课程学业", aliases=("课程",)),)
    assert extract_preferences("我喜欢篮球、编程、经验分享和课程", "positive", topics) == (
        "basketball",
        "software_technology",
        "experience_sharing",
    )

package com.quanta.demo0.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TopicCatalogTest {

    @Test
    void normalizesIdsAndAliasesToDistinctControlledTopics() {
        assertThat(TopicCatalog.normalizeTopics(List.of("basketball", "篮球", "unknown", "篮球")))
                .containsExactly("basketball");
    }

    @Test
    void parsesStoredJsonAndDropsUnknownOrMalformedValues() {
        assertThat(TopicCatalog.parseStoredTags("[\"basketball\",\"unknown\",\"basketball\"]"))
                .containsExactly("basketball");
        assertThat(TopicCatalog.parseStoredTags("not-json")).isEmpty();
    }

    @Test
    void exposesAnImmutableTwentyToFiftyTopicVocabulary() {
        assertThat(TopicCatalog.topics()).hasSizeBetween(20, 50);
        assertThat(TopicCatalog.topics().stream().map(TopicCatalog.Topic::id).distinct())
                .hasSameSizeAs(TopicCatalog.topics());
        assertThatThrownBy(() -> TopicCatalog.topics().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void keepsTheConfirmedTwentySixIdsAndLabelsStable() {
        assertThat(TopicCatalog.topics())
                .extracting(TopicCatalog.Topic::id)
                .containsExactly(
                        "course_study", "software_technology", "experience_sharing", "research_project",
                        "further_education", "career_internship", "campus_policy", "campus_services",
                        "housing", "dining", "transport", "saving", "second_hand", "basketball", "football",
                        "badminton", "table_tennis", "running", "swimming", "strength_training", "gaming",
                        "music_arts", "reading_film", "club_activity", "relationships", "psychological_adjustment");
        assertThat(TopicCatalog.topics())
                .extracting(TopicCatalog.Topic::label)
                .containsExactly(
                        "课程学业", "软件技术", "经验分享", "科研项目", "升学规划", "求职实习", "校务政策", "校园办事",
                        "宿舍住宿", "食堂餐饮", "出行交通", "消费省钱", "二手闲置", "篮球", "足球", "羽毛球", "乒乓球",
                        "跑步", "游泳", "力量训练", "游戏娱乐", "音乐文艺", "阅读影视", "社团活动", "人际交往", "心理调适");
    }
}

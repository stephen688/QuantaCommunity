package com.quanta.demo0.reliability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.quanta.demo0.entity.*;
import com.quanta.demo0.enums.InboxAcquireResult;
import com.quanta.demo0.mapper.CommentMapper;
import com.quanta.demo0.mapper.ContentMapper;
import com.quanta.demo0.mapper.FollowMapper;
import com.quanta.demo0.mapper.InboxEventMapper;
import com.quanta.demo0.mapper.OutboxEventMapper;
import com.quanta.demo0.mapper.QuestionMapper;
import com.quanta.demo0.properties.OutboxDispatchProperties;
import com.quanta.demo0.mq.message.NotificationEventMessage;
import com.quanta.demo0.service.InboxEventService;
import com.quanta.demo0.service.NotificationConsumeService;
import com.quanta.demo0.service.OutboxEventService;
import com.quanta.demo0.service.Impl.InboxEventServiceImpl;
import com.quanta.demo0.service.Impl.NotificationConsumeServiceImpl;
import com.quanta.demo0.service.Impl.OutboxEventServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

@MybatisTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        OutboxEventServiceImpl.class,
        InboxEventServiceImpl.class,
        NotificationConsumeServiceImpl.class,
        OutboxDispatchProperties.class,
        ReliabilityMySqlIntegrationTests.TestBeans.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReliabilityMySqlIntegrationTests {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.43")
            .withDatabaseName("demo")
            .withUsername("demo")
            .withPassword("demo")
            .withInitScript("db/reliability-test-schema.sql");

    @DynamicPropertySource
    static void registerDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("mybatis.mapper-locations", () -> "classpath:mapper/*.xml");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ContentMapper contentMapper;

    @Autowired
    private QuestionMapper questionMapper;

    @Autowired
    private CommentMapper commentMapper;

    @Autowired
    private FollowMapper followMapper;

    @Autowired
    private OutboxEventMapper outboxEventMapper;

    @Autowired
    private InboxEventMapper inboxEventMapper;

    @Autowired
    private OutboxEventService outboxEventService;

    @Autowired
    private InboxEventService inboxEventService;

    @Autowired
    private NotificationConsumeService notificationConsumeService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetData() {
        jdbcTemplate.update("DELETE FROM tb_inbox_event");
        jdbcTemplate.update("DELETE FROM tb_outbox_event");
        jdbcTemplate.update("DELETE FROM tb_comment_like");
        jdbcTemplate.update("DELETE FROM tb_answer_like");
        jdbcTemplate.update("DELETE FROM tb_content_collect");
        jdbcTemplate.update("DELETE FROM tb_content_like");
        jdbcTemplate.update("DELETE FROM tb_user_follow");
        jdbcTemplate.update("DELETE FROM tb_notification");
        jdbcTemplate.update("DELETE FROM tb_content_comment");
        jdbcTemplate.update("DELETE FROM tb_question_answer");
        jdbcTemplate.update("DELETE FROM tb_content");
        jdbcTemplate.update("DELETE FROM tb_user");

        jdbcTemplate.update("INSERT INTO tb_user(id, nick_name) VALUES (1, '发布者'), (2, '操作用户'), (3, '第二用户')");
        jdbcTemplate.update("""
                INSERT INTO tb_content(
                    content_id, content_type, title, content, publish_user_id,
                    audit_status, liked, comment_count, collect_count, is_deleted
                ) VALUES (10, 2, '并发测试问题', '正文', 1, 1, 0, 0, 0, 0)
                """);
        jdbcTemplate.update("""
                INSERT INTO tb_question_answer(
                    answer_id, question_id, user_id, content, like_count,
                    comment_count, is_accepted, audit_status, is_deleted
                ) VALUES
                    (100, 10, 2, '回答一', 0, 0, 0, 1, 0),
                    (101, 10, 3, '回答二', 0, 0, 0, 1, 0)
                """);
        jdbcTemplate.update("""
                INSERT INTO tb_content_comment(
                    comment_id, content_id, user_id, content, like_count, audit_status, is_deleted
                ) VALUES (1000, 10, 2, '评论', 0, 1, 0)
                """);
    }

    @Test
    void repeatedRelationshipInsertReturnsZero() {
        LocalDateTime now = LocalDateTime.now();

        ContentLiked contentLiked = ContentLiked.builder()
                .contentId(10L).userId(2L).createTime(now).build();
        assertEquals(1, contentMapper.insertContentLiked(contentLiked));
        assertEquals(0, contentMapper.insertContentLiked(contentLiked));

        ContentCollect contentCollect = ContentCollect.builder()
                .contentId(10L).userId(2L).createTime(now).build();
        assertEquals(1, contentMapper.insertCollect(contentCollect));
        assertEquals(0, contentMapper.insertCollect(contentCollect));

        AnswerLiked answerLiked = AnswerLiked.builder()
                .answerId(100L).userId(2L).createTime(now).build();
        assertEquals(1, questionMapper.insertAnswerLiked(answerLiked));
        assertEquals(0, questionMapper.insertAnswerLiked(answerLiked));

        assertEquals(1, commentMapper.insertCommentLikes(1000L, 2L));
        assertEquals(0, commentMapper.insertCommentLikes(1000L, 2L));

        Follow follow = Follow.builder()
                .userId(2L).followUserId(1L)
                .createTime(now).updateTime(now).isDeleted(0).build();
        assertEquals(1, followMapper.insert(follow));
        assertEquals(0, followMapper.insert(follow));
    }

    @Test
    void concurrentContentLikesOnlyCreateOneDetailAndOneCount() throws Exception {
        CountDownLatch start = new CountDownLatch(1);

        List<Integer> changedRows = runConcurrently(
                () -> executeInTransaction(() -> {
                    start.await();
                    ContentLiked liked = ContentLiked.builder()
                            .contentId(10L).userId(2L).createTime(LocalDateTime.now()).build();
                    int inserted = contentMapper.insertContentLiked(liked);
                    if (inserted == 1) {
                        contentMapper.updateLiked(10L, 1);
                    }
                    return inserted;
                }),
                () -> executeInTransaction(() -> {
                    start.await();
                    ContentLiked liked = ContentLiked.builder()
                            .contentId(10L).userId(2L).createTime(LocalDateTime.now()).build();
                    int inserted = contentMapper.insertContentLiked(liked);
                    if (inserted == 1) {
                        contentMapper.updateLiked(10L, 1);
                    }
                    return inserted;
                }),
                start
        );

        assertEquals(1, changedRows.stream().mapToInt(Integer::intValue).sum());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_content_like WHERE content_id=10 AND user_id=2",
                Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT liked FROM tb_content WHERE content_id=10",
                Integer.class));
    }

    @Test
    void concurrentContentCollectsOnlyCreateOneDetailAndOneCount() throws Exception {
        CountDownLatch start = new CountDownLatch(1);

        List<Integer> changedRows = runConcurrently(
                () -> executeInTransaction(() -> collectContent(start)),
                () -> executeInTransaction(() -> collectContent(start)),
                start
        );

        assertEquals(1, changedRows.stream().mapToInt(Integer::intValue).sum());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_content_collect WHERE content_id=10 AND user_id=2",
                Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT collect_count FROM tb_content WHERE content_id=10",
                Integer.class));
    }

    @Test
    void concurrentFollowsCreateOneRelationship() throws Exception {
        CountDownLatch start = new CountDownLatch(1);

        List<Integer> changedRows = runConcurrently(
                () -> executeInTransaction(() -> followUser(start)),
                () -> executeInTransaction(() -> followUser(start)),
                start
        );

        assertEquals(1, changedRows.stream().mapToInt(Integer::intValue).sum());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_user_follow WHERE user_id=2 AND follow_user_id=1 AND is_deleted=0",
                Integer.class));
    }

    @Test
    void repeatedCancellationDoesNotProduceNegativeCounts() {
        LocalDateTime now = LocalDateTime.now();
        ContentLiked liked = ContentLiked.builder()
                .contentId(10L).userId(2L).createTime(now).build();
        ContentCollect collected = ContentCollect.builder()
                .contentId(10L).userId(2L).createTime(now).build();

        assertEquals(1, contentMapper.insertContentLiked(liked));
        contentMapper.updateLiked(10L, 1);
        assertEquals(1, contentMapper.insertCollect(collected));
        contentMapper.updateCollectCount(10L, 1);

        int firstUnlike = contentMapper.deleteContentLikedByUser(10L, 2L);
        if (firstUnlike == 1) {
            contentMapper.updateLiked(10L, -1);
        }
        int repeatedUnlike = contentMapper.deleteContentLikedByUser(10L, 2L);
        if (repeatedUnlike == 1) {
            contentMapper.updateLiked(10L, -1);
        }

        int firstUncollect = contentMapper.deleteCollect(10L, 2L);
        if (firstUncollect == 1) {
            contentMapper.updateCollectCount(10L, -1);
        }
        int repeatedUncollect = contentMapper.deleteCollect(10L, 2L);
        if (repeatedUncollect == 1) {
            contentMapper.updateCollectCount(10L, -1);
        }

        assertEquals(0, repeatedUnlike);
        assertEquals(0, repeatedUncollect);
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT liked FROM tb_content WHERE content_id=10", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT collect_count FROM tb_content WHERE content_id=10", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_content_like WHERE content_id=10", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_content_collect WHERE content_id=10", Integer.class));
    }

    @Test
    void concurrentAnswerAcceptanceLeavesOnlyOneAcceptedAnswer() throws Exception {
        CountDownLatch start = new CountDownLatch(1);

        runConcurrently(
                () -> executeInTransaction(() -> acceptAnswerWithQuestionLock(100L, start)),
                () -> executeInTransaction(() -> acceptAnswerWithQuestionLock(101L, start)),
                start
        );

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_question_answer WHERE question_id=10 AND is_accepted=1",
                Integer.class));
    }

    @Test
    void rolledBackBusinessTransactionDoesNotLeaveOutboxEvent() {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        assertThrows(IllegalStateException.class, () -> transactionTemplate.executeWithoutResult(status -> {
            outboxEventService.createContentModerationEvent(testContent("正常正文"), List.of());
            throw new IllegalStateException("模拟业务回滚");
        }));

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_outbox_event",
                Integer.class));
    }

    @Test
    void twoDispatchersCannotClaimTheSameOutboxEvent() throws Exception {
        outboxEventService.createContentModerationEvent(testContent("正常正文"), List.of());
        CountDownLatch start = new CountDownLatch(1);

        List<Integer> claimSizes = runConcurrently(
                () -> {
                    start.await();
                    return outboxEventService.claimBatch("instance-a").size();
                },
                () -> {
                    start.await();
                    return outboxEventService.claimBatch("instance-b").size();
                },
                start
        );

        assertEquals(1, claimSizes.stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void expiredOwnerCannotOverwriteNewOwner() {
        String eventId = outboxEventService.createContentModerationEvent(testContent("正常正文"), List.of());
        OutboxEvent firstClaim = outboxEventService.claimBatch("instance-a").get(0);

        jdbcTemplate.update(
                "UPDATE tb_outbox_event SET locked_until=? WHERE event_id=?",
                LocalDateTime.now().minusSeconds(1), eventId
        );

        OutboxEvent secondClaim = outboxEventService.claimBatch("instance-b").get(0);

        assertFalse(outboxEventService.markSent(firstClaim.getId(), "instance-a"));
        assertTrue(outboxEventService.markSent(secondClaim.getId(), "instance-b"));
    }

    @Test
    void twoAdministratorsCanReplayDeadEventOnlyOnce() throws Exception {
        String eventId = outboxEventService.createContentModerationEvent(testContent("正常正文"), List.of());
        jdbcTemplate.update("UPDATE tb_outbox_event SET status='DEAD' WHERE event_id=?", eventId);
        CountDownLatch start = new CountDownLatch(1);

        List<Integer> replayRows = runConcurrently(
                () -> {
                    start.await();
                    return outboxEventMapper.replayDead(eventId, 1L, LocalDateTime.now());
                },
                () -> {
                    start.await();
                    return outboxEventMapper.replayDead(eventId, 2L, LocalDateTime.now());
                },
                start
        );

        assertEquals(1, replayRows.stream().mapToInt(Integer::intValue).sum());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT replay_count FROM tb_outbox_event WHERE event_id=?",
                Integer.class, eventId));
        assertEquals("PENDING", jdbcTemplate.queryForObject(
                "SELECT status FROM tb_outbox_event WHERE event_id=?",
                String.class, eventId));
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT last_replay_by FROM tb_outbox_event WHERE event_id=?",
                Long.class, eventId));
    }

    @Test
    void twoAdministratorsCanReplayDeadInboxOnlyOnceAndAuditIsKept() throws Exception {
        NotificationEventMessage message = testNotificationMessage();
        String consumerName = "notification-consumer";
        assertEquals(
                InboxAcquireResult.ACQUIRED,
                inboxEventService.acquire(consumerName, "instance-a", message)
        );
        assertTrue(inboxEventService.markDead(
                consumerName,
                message.getEventId(),
                "instance-a",
                "模拟消费失败"
        ));
        CountDownLatch start = new CountDownLatch(1);

        List<Integer> replayRows = runConcurrently(
                () -> {
                    start.await();
                    return inboxEventMapper.replayDead(
                            consumerName,
                            message.getEventId(),
                            1L,
                            LocalDateTime.now()
                    );
                },
                () -> {
                    start.await();
                    return inboxEventMapper.replayDead(
                            consumerName,
                            message.getEventId(),
                            2L,
                            LocalDateTime.now()
                    );
                },
                start
        );

        assertEquals(1, replayRows.stream().mapToInt(Integer::intValue).sum());
        assertEquals("RETRYING", jdbcTemplate.queryForObject(
                "SELECT status FROM tb_inbox_event WHERE consumer_name=? AND event_id=?",
                String.class,
                consumerName,
                message.getEventId()
        ));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT replay_count FROM tb_inbox_event WHERE consumer_name=? AND event_id=?",
                Integer.class,
                consumerName,
                message.getEventId()
        ));
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT last_replay_by FROM tb_inbox_event WHERE consumer_name=? AND event_id=?",
                Long.class,
                consumerName,
                message.getEventId()
        ));
    }

    @Test
    void oversizedPayloadDoesNotLeaveOutboxEvent() {
        assertThrows(RuntimeException.class, () ->
                outboxEventService.createContentModerationEvent(
                        testContent("x".repeat(40_000)),
                        List.of()
                ));

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_outbox_event",
                Integer.class));
    }

    @Test
    void cleanupKeepsOutboxWhileInboxIsDead() {
        String eventId = UUID.randomUUID().toString();
        LocalDateTime oldTime = LocalDateTime.now().minusDays(31);
        jdbcTemplate.update("""
                INSERT INTO tb_outbox_event(
                    event_id,event_type,aggregate_type,aggregate_id,payload,status,
                    retry_count,next_retry_time,replay_count,create_time,sent_time
                ) VALUES (?,?,?,?,?,'SENT',0,?,0,?,?)
                """, eventId, "NOTIFICATION_REQUESTED", "CONTENT", 10L, "{}", oldTime, oldTime, oldTime);
        Long outboxId = jdbcTemplate.queryForObject(
                "SELECT id FROM tb_outbox_event WHERE event_id=?", Long.class, eventId);
        jdbcTemplate.update("""
                INSERT INTO tb_inbox_event(
                    event_id,consumer_name,outbox_event_id,event_type,aggregate_type,
                    aggregate_id,status,retry_count,replay_count,create_time,processed_time
                ) VALUES (?,?,?,?,?,?,'DEAD',3,0,?,?)
                """, eventId, "notification-consumer", outboxId,
                "NOTIFICATION_REQUESTED", "CONTENT", 10L, oldTime, oldTime);

        assertEquals(0, outboxEventMapper.deleteCompletedBefore(LocalDateTime.now().minusDays(30)));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_outbox_event WHERE event_id=?", Integer.class, eventId));
    }

    @Test
    void duplicateInboxMessageIsProcessedOnlyOnce() {
        NotificationEventMessage message = testNotificationMessage();

        assertEquals(
                InboxAcquireResult.ACQUIRED,
                inboxEventService.acquire("notification-consumer", "instance-a", message)
        );
        assertEquals(
                InboxAcquireResult.BUSY,
                inboxEventService.acquire("notification-consumer", "instance-b", message)
        );
        assertTrue(inboxEventService.markSuccess(
                "notification-consumer",
                message.getEventId(),
                "instance-a"
        ));
        assertEquals(
                InboxAcquireResult.ALREADY_SUCCESS,
                inboxEventService.acquire("notification-consumer", "instance-b", message)
        );

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_inbox_event WHERE consumer_name=? AND event_id=?",
                Integer.class,
                "notification-consumer",
                message.getEventId()
        ));
    }

    @Test
    void expiredInboxLeaseCanBeTakenOverButOldOwnerCannotFinish() {
        NotificationEventMessage message = testNotificationMessage();

        assertEquals(
                InboxAcquireResult.ACQUIRED,
                inboxEventService.acquire("notification-consumer", "instance-a", message)
        );
        jdbcTemplate.update(
                "UPDATE tb_inbox_event SET locked_until=? WHERE consumer_name=? AND event_id=?",
                LocalDateTime.now().minusSeconds(1),
                "notification-consumer",
                message.getEventId()
        );

        assertEquals(
                InboxAcquireResult.ACQUIRED,
                inboxEventService.acquire("notification-consumer", "instance-b", message)
        );
        assertFalse(inboxEventService.markSuccess(
                "notification-consumer",
                message.getEventId(),
                "instance-a"
        ));
        assertTrue(inboxEventService.markSuccess(
                "notification-consumer",
                message.getEventId(),
                "instance-b"
        ));
    }

    @Test
    void duplicateNotificationEventCreatesOneNotification() {
        NotificationEventMessage message = testNotificationMessage();
        String consumerName = "notification-consumer";

        assertEquals(
                InboxAcquireResult.ACQUIRED,
                inboxEventService.acquire(consumerName, "instance-a", message)
        );
        notificationConsumeService.saveAndMarkSuccess(
                message,
                consumerName,
                "instance-a"
        );

        assertEquals(
                InboxAcquireResult.ALREADY_SUCCESS,
                inboxEventService.acquire(consumerName, "instance-b", message)
        );
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_notification WHERE recipient_user_id=? AND type=?",
                Integer.class,
                message.getRecipientUserId(),
                message.getType()
        ));
    }

    private Content testContent(String body) {
        return Content.builder()
                .contentId(10L)
                .contentType(2)
                .title("并发测试问题")
                .content(body)
                .publishUserId(1L)
                .createTime(LocalDateTime.now())
                .build();
    }

    private NotificationEventMessage testNotificationMessage() {
        return NotificationEventMessage.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("NOTIFICATION_REQUESTED")
                .recipientUserId(1L)
                .actorUserId(2L)
                .type("LIKE_CONTENT")
                .content("用户点赞了你的内容")
                .occurredAt(LocalDateTime.now())
                .retryCount(0)
                .build();
    }

    private Integer collectContent(CountDownLatch start) throws Exception {
        start.await();
        ContentCollect collected = ContentCollect.builder()
                .contentId(10L)
                .userId(2L)
                .createTime(LocalDateTime.now())
                .build();
        int inserted = contentMapper.insertCollect(collected);
        if (inserted == 1) {
            contentMapper.updateCollectCount(10L, 1);
        }
        return inserted;
    }

    private Integer followUser(CountDownLatch start) throws Exception {
        start.await();
        LocalDateTime now = LocalDateTime.now();
        Follow follow = Follow.builder()
                .userId(2L)
                .followUserId(1L)
                .createTime(now)
                .updateTime(now)
                .isDeleted(0)
                .build();
        return followMapper.insert(follow);
    }

    private Integer acceptAnswerWithQuestionLock(Long answerId, CountDownLatch start) throws Exception {
        start.await();
        contentMapper.selectByIdForUpdate(10L);
        questionMapper.clearAcceptedAnswer(10L);
        return questionMapper.acceptAnswer(answerId);
    }

    private <T> T executeInTransaction(ThrowingSupplier<T> supplier) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        return template.execute(status -> {
            try {
                return supplier.get();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private <T> List<T> runConcurrently(
            ThrowingSupplier<T> first,
            ThrowingSupplier<T> second,
            CountDownLatch start
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<T> firstFuture = executor.submit(first::get);
            Future<T> secondFuture = executor.submit(second::get);
            start.countDown();
            return List.of(firstFuture.get(), secondFuture.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    @TestConfiguration
    static class TestBeans {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().registerModule(new JavaTimeModule());
        }
    }
}

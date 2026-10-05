package com.quanta.demo0.platform.web.trace;

import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Web/MQ 日志关联上下文工具。
 *
 * 职责：统一校验和生成关联编号，并在同步调用或显式包装的线程边界
 * 内维护 MDC。边界：不负责鉴权、幂等、业务状态，也不依赖 Spring 组件。
 */
public final class TraceContext {

    /** HTTP 和 MQ 之间传递请求关联编号的唯一 header。 */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** MQ 事件编号 header。 */
    public static final String EVENT_ID_HEADER = "X-Event-Id";

    /** MDC 中保存请求关联编号的字段名。 */
    public static final String TRACE_ID_KEY = "traceId";

    /** MDC 中保存可靠事件编号的字段名。 */
    public static final String EVENT_ID_KEY = "eventId";

    private static final Pattern HTTP_ID_PATTERN =
            Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private static final Pattern EVENT_ID_PATTERN =
            Pattern.compile("[A-Za-z0-9_.:-]{1,128}");

    private TraceContext() {
        // Utility class.
    }

    /**
     * 解析 HTTP 入站编号；非法、缺失或空值统一生成 32 位小写 hex 编号。
     *
     * @param candidate 外部 X-Request-Id 值
     * @return 可安全写入 header、MDC 和日志的编号
     */
    public static String resolveHttp(String candidate) {
        return isValidHttpId(candidate) ? candidate : randomTraceId();
    }

    /**
     * 解析事件关联编号。优先保留合法候选值；缺少候选值时对合法事件编号
     * 做跨 Java/Python 一致的稳定哈希；两者都不可用时生成随机编号。
     *
     * @param candidate 消息头或已有上下文中的候选请求编号
     * @param eventId 可靠事件编号
     * @return 可安全写入 MDC 和日志的编号
     */
    public static String resolveEvent(String candidate, String eventId) {
        if (isValidHttpId(candidate)) {
            return candidate;
        }
        if (isValidEventId(eventId)) {
            return stableEventTraceId(eventId);
        }
        return randomTraceId();
    }

    /**
     * 返回当前线程的请求关联编号。
     *
     * @return 当前编号；没有打开上下文时为 {@code null}
     */
    public static String currentTraceId() {
        return MDC.get(TRACE_ID_KEY);
    }

    /**
     * 打开一个可嵌套的 MDC 作用域，并在关闭时恢复进入前的全部关联字段。
     *
     * @param traceId 要放入当前作用域的请求关联编号，可为 null
     * @param eventId 要放入当前作用域的事件编号，可为 null
     * @return 关闭后恢复原 MDC 的作用域
     */
    public static Scope open(String traceId, String eventId) {
        return new Scope(traceId, eventId);
    }

    /**
     * 捕获调用点的完整 MDC，在执行 Supplier 时恢复并于结束后还原执行线程原值。
     * 该方法用于显式跨线程边界传递日志上下文。
     *
     * @param supplier 要执行的操作
     * @param <T> 返回值类型
     * @return 已携带调用点 MDC 的 Supplier
     */
    public static <T> Supplier<T> wrapSupplier(Supplier<T> supplier) {
        if (supplier == null) {
            throw new IllegalArgumentException("supplier must not be null");
        }
        Map<String, String> captured = copyContext();
        return () -> {
            Map<String, String> previous = copyContext();
            restoreContext(captured);
            try {
                return supplier.get();
            } finally {
                restoreContext(previous);
            }
        };
    }

    /**
     * 作用域关闭时恢复调用前的 MDC；不清理调用方设置的其它 MDC 键。
     */
    public static final class Scope implements AutoCloseable {

        private final String previousTraceId;
        private final String previousEventId;
        private boolean closed;

        private Scope(String traceId, String eventId) {
            this.previousTraceId = MDC.get(TRACE_ID_KEY);
            this.previousEventId = MDC.get(EVENT_ID_KEY);
            setOrRemove(TRACE_ID_KEY, traceId);
            setOrRemove(EVENT_ID_KEY, eventId);
        }

        /**
         * 关闭当前作用域并恢复进入前的 traceId、eventId。
         */
        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            setOrRemove(TRACE_ID_KEY, previousTraceId);
            setOrRemove(EVENT_ID_KEY, previousEventId);
        }
    }

    /**
     * 判断事件编号是否符合 MQ 头部约束，供消息边界复用。
     */
    public static boolean isValidEventId(String eventId) {
        return eventId != null && EVENT_ID_PATTERN.matcher(eventId).matches();
    }

    private static boolean isValidHttpId(String candidate) {
        return candidate != null && HTTP_ID_PATTERN.matcher(candidate).matches();
    }

    private static String randomTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String stableEventTraceId(String eventId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("event:" + eventId).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value & 0xff));
            }
            return hex.substring(0, 32);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Map<String, String> copyContext() {
        Map<String, String> current = MDC.getCopyOfContextMap();
        return current == null ? Map.of() : Map.copyOf(current);
    }

    private static void restoreContext(Map<String, String> context) {
        if (context == null || context.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }

    private static void setOrRemove(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }
}

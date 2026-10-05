package com.uav.lowaltitude.platform.realtime;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 监听 V202610050001 触发器发出的 pg_notify 信号并汇总转发给 {@link RealtimeHub}。
 * 使用独立连接，不占用业务连接池；只在 PostgreSQL 上启用，断线后按退避重连。
 */
@Component
public class DataChangeListener implements SmartLifecycle {

    static final String CHANNEL = "app_data_change";
    private static final Logger log = LoggerFactory.getLogger(DataChangeListener.class);

    private final RealtimeHub hub;
    private final boolean enabled;
    private final String url;
    private final String username;
    private final String password;
    private final long flushMillis;
    private volatile boolean running;
    private volatile boolean listening;
    private Thread worker;

    public DataChangeListener(RealtimeHub hub,
            @Value("${app.realtime.enabled:true}") boolean enabled,
            @Value("${spring.datasource.url:}") String url,
            @Value("${spring.datasource.username:}") String username,
            @Value("${spring.datasource.password:}") String password,
            @Value("${app.realtime.flush-millis:300}") long flushMillis) {
        this.hub = hub;
        this.enabled = enabled;
        this.url = url;
        this.username = username;
        this.password = password;
        this.flushMillis = flushMillis;
    }

    public boolean isListening() {
        return listening;
    }

    @Override
    public void start() {
        if (!enabled || url == null || !url.startsWith("jdbc:postgresql:")) {
            log.info("realtime data change listener disabled (enabled={}, postgresql={})",
                    enabled, url != null && url.startsWith("jdbc:postgresql:"));
            return;
        }
        running = true;
        worker = new Thread(this::run, "realtime-data-change");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void stop() {
        running = false;
        if (worker != null) worker.interrupt();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void run() {
        long backoff = 1000;
        while (running) {
            try (Connection connection = DriverManager.getConnection(url, username, password)) {
                connection.setAutoCommit(true);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("LISTEN " + CHANNEL);
                }
                Notifications pg = new Notifications(connection);
                listening = true;
                backoff = 1000;
                log.info("realtime data change listener started");
                // 重连后可能错过信号，通知前端全部重读一次。
                hub.publish(Set.of("*"));
                loop(pg);
            } catch (SQLException e) {
                if (running) log.warn("realtime data change listener lost connection: {}", e.getMessage());
            } finally {
                listening = false;
            }
            if (!running) break;
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            backoff = Math.min(backoff * 2, 30_000);
        }
    }

    private void loop(Notifications pg) throws SQLException {
        Set<String> pending = new LinkedHashSet<>();
        long firstPendingAt = 0;
        while (running) {
            List<String> received = pg.poll((int) Math.max(50, flushMillis));
            for (String topic : received) {
                if (pending.isEmpty()) firstPendingAt = System.nanoTime();
                pending.add(topic);
            }
            boolean due = !pending.isEmpty()
                    && (received.isEmpty() || (System.nanoTime() - firstPendingAt) / 1_000_000 >= flushMillis);
            if (due) {
                hub.publish(Set.copyOf(pending));
                pending.clear();
            }
        }
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    /** PostgreSQL 驱动为 runtime 依赖，这里经反射读取通知，不新增编译期依赖。 */
    private static final class Notifications {
        private final Object pgConnection;
        private final Method getNotifications;
        private Method getParameter;

        Notifications(Connection connection) throws SQLException {
            try {
                Class<?> type = Class.forName("org.postgresql.PGConnection");
                this.pgConnection = connection.unwrap(type);
                this.getNotifications = type.getMethod("getNotifications", int.class);
            } catch (ReflectiveOperationException e) {
                throw new SQLException("PostgreSQL notification API unavailable", e);
            }
        }

        List<String> poll(int timeoutMillis) throws SQLException {
            try {
                Object[] notifications = (Object[]) getNotifications.invoke(pgConnection, timeoutMillis);
                if (notifications == null || notifications.length == 0) return List.of();
                List<String> topics = new ArrayList<>(notifications.length);
                for (Object notification : notifications) {
                    if (getParameter == null) getParameter = notification.getClass().getMethod("getParameter");
                    topics.add((String) getParameter.invoke(notification));
                }
                return topics;
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof SQLException sql) throw sql;
                throw new SQLException("PostgreSQL notification read failed", e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new SQLException("PostgreSQL notification read failed", e);
            }
        }
    }
}

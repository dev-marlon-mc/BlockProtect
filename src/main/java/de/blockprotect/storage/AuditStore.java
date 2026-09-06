package de.blockprotect.storage;

import de.blockprotect.config.ConfigService;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class AuditStore implements AutoCloseable {
    private final JavaPlugin plugin;
    private final ConfigService config;
    private final ConcurrentLinkedQueue<AuditRecord> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicBoolean immediateFlushQueued = new AtomicBoolean();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("blockprotect-db", 0).factory()
    );

    private volatile Connection connection;
    private volatile ScheduledFuture<?> periodicFlush;
    private volatile String activeDatabase;
    private volatile int activeBusyTimeout;
    private volatile long lastRetentionCleanup;
    private final AtomicBoolean reopenQueued = new AtomicBoolean();
    private volatile boolean closed;

    public AuditStore(JavaPlugin plugin, ConfigService config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void start() throws SQLException {
        plugin.getDataFolder().mkdirs();
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException exception) {
            throw new SQLException("SQLite JDBC library wurde von Paper nicht geladen.", exception);
        }
        activeDatabase = config.getString("storage.database", "blockprotect.db");
        activeBusyTimeout = config.getInt("storage.busy-timeout-ms", 5000);
        connection = openConnection(activeDatabase);
        config.addListener(ignored -> {
            reschedulePeriodicFlush();
            String configuredDatabase = config.getString("storage.database", "blockprotect.db");
            int configuredBusyTimeout = config.getInt("storage.busy-timeout-ms", 5000);
            if ((!configuredDatabase.equals(activeDatabase) || configuredBusyTimeout != activeBusyTimeout)
                    && reopenQueued.compareAndSet(false, true)) {
                executor.execute(() -> {
                    try {
                        reopenOnExecutor(configuredDatabase);
                    } finally {
                        reopenQueued.set(false);
                    }
                });
            }
        });
        reschedulePeriodicFlush();
    }

    public boolean enqueue(AuditRecord record) {
        if (closed) {
            dropped.incrementAndGet();
            return false;
        }
        int maxQueue = config.getInt("storage.max-queue-size", 100_000);
        if (queued.get() >= maxQueue) {
            dropped.incrementAndGet();
            return false;
        }
        queue.offer(record);
        int size = queued.incrementAndGet();
        if (size >= config.getInt("storage.batch-size", 250)
                && immediateFlushQueued.compareAndSet(false, true)) {
            executor.execute(() -> {
                try {
                    flushOnExecutor();
                } finally {
                    immediateFlushQueued.set(false);
                }
            });
        }
        return true;
    }

    public int queuedCount() {
        return queued.get();
    }

    public long droppedCount() {
        return dropped.get();
    }

    public CompletableFuture<List<AuditRecord>> query(AuditQuery query) {
        CompletableFuture<List<AuditRecord>> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                flushOnExecutor();
                future.complete(queryOnExecutor(query));
            } catch (Exception exception) {
                future.completeExceptionally(exception);
            }
        });
        return future;
    }

    public CompletableFuture<Integer> purgeOlderThan(int days) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                flushOnExecutor();
                long cutoff = System.currentTimeMillis() - Duration.ofDays(days).toMillis();
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM audit_events WHERE timestamp < ?")) {
                    statement.setLong(1, cutoff);
                    future.complete(statement.executeUpdate());
                }
            } catch (Exception exception) {
                future.completeExceptionally(exception);
            }
        });
        return future;
    }

    public CompletableFuture<Void> flush() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                flushOnExecutor();
                future.complete(null);
            } catch (Exception exception) {
                future.completeExceptionally(exception);
            }
        });
        return future;
    }

    private Connection openConnection(String database) throws SQLException {
        File databaseFile = new File(plugin.getDataFolder(), database);
        if (!databaseFile.toPath().normalize().startsWith(plugin.getDataFolder().toPath().normalize())) {
            throw new SQLException("storage.database muss innerhalb des Plugin-Ordners liegen.");
        }
        File parent = databaseFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        Connection opened = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
        try (Statement statement = opened.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=" + config.getInt("storage.busy-timeout-ms", 5000));
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS audit_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        timestamp INTEGER NOT NULL,
                        actor_uuid TEXT,
                        actor_name TEXT,
                        source TEXT NOT NULL,
                        action TEXT NOT NULL,
                        world TEXT,
                        x INTEGER,
                        y INTEGER,
                        z INTEGER,
                        target TEXT,
                        amount INTEGER NOT NULL DEFAULT 0,
                        item_type TEXT,
                        before_state TEXT,
                        after_state TEXT,
                        details TEXT
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_audit_location ON audit_events(world, x, y, z, timestamp DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_audit_actor ON audit_events(actor_name, timestamp DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_audit_action ON audit_events(action, timestamp DESC)");
        }
        return opened;
    }

    private void reopenOnExecutor(String database) {
        try {
            flushOnExecutor();
            Connection replacement = openConnection(database);
            Connection previous = connection;
            connection = replacement;
            activeDatabase = database;
            activeBusyTimeout = config.getInt("storage.busy-timeout-ms", 5000);
            if (previous != null) {
                previous.close();
            }
            plugin.getLogger().info("Audit-Datenbank live auf " + database + " umgestellt.");
        } catch (SQLException exception) {
            plugin.getLogger().warning("Audit-Datenbank konnte nicht live umgestellt werden: " + exception.getMessage());
        }
    }

    private void flushOnExecutor() {
        if (!queue.isEmpty()) {
            int batchSize = Math.max(1, config.getInt("storage.batch-size", 250));
            List<AuditRecord> batch = new ArrayList<>(batchSize);
            while (batch.size() < batchSize) {
                AuditRecord record = queue.poll();
                if (record == null) {
                    break;
                }
                batch.add(record);
                queued.decrementAndGet();
            }
            if (!batch.isEmpty()) {
                try {
                    connection.setAutoCommit(false);
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO audit_events
                            (timestamp, actor_uuid, actor_name, source, action, world, x, y, z,
                             target, amount, item_type, before_state, after_state, details)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """)) {
                        for (AuditRecord record : batch) {
                            statement.setLong(1, record.timestamp());
                            setNullableString(statement, 2, record.actorUuid() == null ? null : record.actorUuid().toString());
                            setNullableString(statement, 3, record.actorName());
                            statement.setString(4, record.source());
                            statement.setString(5, record.action());
                            setNullableString(statement, 6, record.world());
                            setNullableInt(statement, 7, record.x());
                            setNullableInt(statement, 8, record.y());
                            setNullableInt(statement, 9, record.z());
                            setNullableString(statement, 10, record.target());
                            statement.setInt(11, record.amount());
                            setNullableString(statement, 12, record.itemType());
                            setNullableString(statement, 13, record.beforeState());
                            setNullableString(statement, 14, record.afterState());
                            setNullableString(statement, 15, record.details());
                            statement.addBatch();
                        }
                        statement.executeBatch();
                    }
                    connection.commit();
                } catch (SQLException exception) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackException) {
                        exception.addSuppressed(rollbackException);
                    }
                    plugin.getLogger().warning("Audit-Batch konnte nicht gespeichert werden: " + exception.getMessage());
                    for (AuditRecord record : batch) {
                        queue.offer(record);
                        queued.incrementAndGet();
                    }
                } finally {
                    try {
                        connection.setAutoCommit(true);
                    } catch (SQLException ignored) {
                        // Connection errors are reported by the next database operation.
                    }
                }
            }
        }

        int retentionDays = config.getInt("storage.retention-days", 0);
        long now = System.currentTimeMillis();
        if (retentionDays > 0 && now - lastRetentionCleanup > Duration.ofHours(1).toMillis()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM audit_events WHERE timestamp < ?")) {
                statement.setLong(1, now - Duration.ofDays(retentionDays).toMillis());
                int deleted = statement.executeUpdate();
                lastRetentionCleanup = now;
                if (deleted > 0) {
                    plugin.getLogger().info("Retention hat " + deleted + " Audit-Einträge entfernt.");
                }
            } catch (SQLException exception) {
                plugin.getLogger().warning("Retention konnte nicht ausgeführt werden: " + exception.getMessage());
            }
        }
    }

    private List<AuditRecord> queryOnExecutor(AuditQuery query) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT timestamp, actor_uuid, actor_name, source, action, world, x, y, z,
                       target, amount, item_type, before_state, after_state, details
                FROM audit_events
                WHERE world = ? AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ?
                """);
        if (query.actorName() != null) {
            sql.append(" AND lower(actor_name) = lower(?)");
        }
        if (query.action() != null) {
            sql.append(" AND upper(action) = upper(?)");
        }
        if (query.source() != null) {
            sql.append(" AND lower(source) = lower(?)");
        }
        if (query.target() != null) {
            sql.append(" AND lower(target) = lower(?)");
        }
        if (query.sinceTimestamp() != null) {
            sql.append(" AND timestamp >= ?");
        }
        if (query.untilTimestamp() != null) {
            sql.append(" AND timestamp <= ?");
        }
        sql.append(" ORDER BY timestamp DESC, id DESC LIMIT ?");

        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            statement.setString(index++, query.world());
            statement.setInt(index++, query.minX());
            statement.setInt(index++, query.maxX());
            statement.setInt(index++, query.minY());
            statement.setInt(index++, query.maxY());
            statement.setInt(index++, query.minZ());
            statement.setInt(index++, query.maxZ());
            if (query.actorName() != null) {
                statement.setString(index++, query.actorName());
            }
            if (query.action() != null) {
                statement.setString(index++, query.action());
            }
            if (query.source() != null) {
                statement.setString(index++, query.source());
            }
            if (query.target() != null) {
                statement.setString(index++, query.target());
            }
            if (query.sinceTimestamp() != null) {
                statement.setLong(index++, query.sinceTimestamp());
            }
            if (query.untilTimestamp() != null) {
                statement.setLong(index++, query.untilTimestamp());
            }
            statement.setInt(index, query.limit());

            List<AuditRecord> results = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String uuidValue = result.getString(2);
                    results.add(new AuditRecord(
                            result.getLong(1),
                            uuidValue == null ? null : java.util.UUID.fromString(uuidValue),
                            result.getString(3),
                            result.getString(4),
                            result.getString(5),
                            result.getString(6),
                            nullableInt(result, 7),
                            nullableInt(result, 8),
                            nullableInt(result, 9),
                            result.getString(10),
                            result.getInt(11),
                            result.getString(12),
                            result.getString(13),
                            result.getString(14),
                            result.getString(15)
                    ));
                }
            }
            return results;
        }
    }

    private void reschedulePeriodicFlush() {
        if (executor.isShutdown()) {
            return;
        }
        ScheduledFuture<?> previous = periodicFlush;
        if (previous != null) {
            previous.cancel(false);
        }
        long ticks = Math.max(1, config.getInt("storage.flush-interval-ticks", 20));
        periodicFlush = executor.scheduleWithFixedDelay(this::flushOnExecutor, ticks * 50L, ticks * 50L, TimeUnit.MILLISECONDS);
    }

    private static void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static void setNullableInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static Integer nullableInt(ResultSet result, int index) throws SQLException {
        int value = result.getInt(index);
        return result.wasNull() ? null : value;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        ScheduledFuture<?> previous = periodicFlush;
        if (previous != null) {
            previous.cancel(false);
        }
        try {
            flush().get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            plugin.getLogger().warning("Audit-Datenbank konnte beim Stoppen nicht vollständig geleert werden: " + exception.getMessage());
        }
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Audit-Datenbank konnte nicht geschlossen werden: " + exception.getMessage());
        }
    }
}

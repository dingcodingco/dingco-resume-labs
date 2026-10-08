package com.dingco.labs.w6;

import com.dingco.labs.common.Evidence;
import com.dingco.labs.common.OrderSeeder;
import com.dingco.labs.common.Stopwatch;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

import static com.dingco.labs.common.Evidence.map;

/**
 * 6주차 부록: 일별·카테고리별 주문 통계를 DB GROUP BY 로 만들까, Redis 로 누적할까.
 * 실행: ./gradlew bootRun --args='--lab=w6'
 */
@Component
@Profile("w6")
public class RedisPipelineLab implements ApplicationRunner {

    static final String GROUP_BY_SQL = """
            SELECT o.order_date, p.category, COUNT(*) AS order_count, SUM(o.amount) AS total_amount
            FROM orders o
            JOIN products p ON p.id = o.product_id
            GROUP BY o.order_date, p.category""";

    /** 방식 B 의 읽기: GROUP BY 없이 id 순서로 청크를 읽는다. */
    static final String CHUNK_SQL = """
            SELECT o.id, o.order_date, p.category, o.amount
            FROM orders o
            JOIN products p ON p.id = o.product_id
            WHERE o.id > ?
            ORDER BY o.id
            LIMIT ?""";

    private static final int CHUNK = 1_000;
    private static final int NEW_ORDERS = 1_000;
    private static final int REPEAT = 3;
    private static final String KEY_PREFIX = "stats:";
    private static final long SEED = 20261008L;

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final OrderSeeder seeder;

    public RedisPipelineLab(JdbcTemplate jdbc, StringRedisTemplate redis, OrderSeeder seeder) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.seeder = seeder;
    }

    record Row(long id, String date, String category, long amount) {
    }

    @Override
    public void run(ApplicationArguments args) {
        Evidence evidence = new Evidence("w6-redis-pipeline")
                .meta("mysql", jdbc.queryForObject("SELECT VERSION()", String.class))
                .meta("redis", redisInfo("server").getProperty("redis_version"))
                .meta("groupBySql", GROUP_BY_SQL)
                .meta("chunkSql", CHUNK_SQL);

        for (int size : new int[]{100_000, 500_000}) {
            seeder.createTables();
            seeder.insertOrders(size, SEED);
            jdbc.execute("ANALYZE TABLE orders");

            List<Map<String, Object>> plan = new ArrayList<>();
            for (Map<String, Object> row : jdbc.queryForList("EXPLAIN " + GROUP_BY_SQL)) {
                plan.add(map("table", row.get("table"), "type", row.get("type"), "rows", row.get("rows"), "extra", row.get("Extra")));
            }
            evidence.row(map("kind", "plan", "rows", size, "groupByPlan", plan));

            Map<String, long[]> expected = null;
            for (String method : List.of("A_db_group_by", "B_redis_pipeline", "B0_redis_one_by_one")) {
                if (method.equals("B0_redis_one_by_one") && size > 100_000) continue; // 비교용으로 10만 건만
                long[] times = new long[REPEAT];
                long dbMs = 0, redisMs = 0, redisMemoryDelta = 0;
                int groups = 0;
                boolean matches = true;
                for (int r = 0; r < REPEAT; r++) {
                    jdbc.execute("TRUNCATE TABLE order_statistics");
                    clearRedis();
                    long memBefore = usedMemory();
                    long[] split = new long[2];
                    times[r] = Stopwatch.ms(() -> {
                        switch (method) {
                            case "A_db_group_by" -> aggregateWithGroupBy();
                            case "B_redis_pipeline" -> aggregateWithRedis(true, split);
                            default -> aggregateWithRedis(false, split);
                        }
                    });
                    dbMs = split[0];
                    redisMs = split[1];
                    if (!method.startsWith("A")) {
                        redisMemoryDelta = usedMemory() - memBefore;
                    }
                    Map<String, long[]> result = readStatistics();
                    groups = result.size();
                    if (expected == null) {
                        expected = result;
                    } else {
                        matches &= sameStatistics(expected, result);
                    }
                }
                evidence.row(map("kind", "aggregate", "method", method, "rows", size,
                        "elapsedMs", List.of(times[0], times[1], times[2]),
                        "medianMs", Stopwatch.median(times),
                        "lastRunDbReadMs", dbMs, "lastRunRedisMs", redisMs,
                        "redisUsedMemoryDeltaBytes", redisMemoryDelta,
                        "statisticRows", groups,
                        "matchesGroupByResult", matches));
            }
            incremental(evidence, size);
        }
        clearRedis();
        evidence.save();
    }

    /**
     * 증분 집계: 이미 집계된 상태에서 새 주문 1,000건이 들어왔을 때.
     * A 는 전체 GROUP BY 를 다시 하고, B 는 새 주문만 Redis 에 더한 뒤 바뀐 통계 행만 갱신한다.
     */
    private void incremental(Evidence evidence, int size) {
        long[] aTimes = new long[REPEAT];
        long[] bTimes = new long[REPEAT];
        boolean matches = true;
        for (int r = 0; r < REPEAT; r++) {
            jdbc.execute("TRUNCATE TABLE order_statistics");
            clearRedis();
            aggregateWithRedis(true, new long[2]);
            long lastId = jdbc.queryForObject("SELECT MAX(id) FROM orders", Long.class);
            seeder.insertOrders(NEW_ORDERS, SEED + 100 + r);

            bTimes[r] = Stopwatch.ms(() -> applyNewOrders(lastId));
            Map<String, long[]> byRedis = readStatistics();

            aTimes[r] = Stopwatch.ms(() -> {
                jdbc.execute("TRUNCATE TABLE order_statistics");
                aggregateWithGroupBy();
            });
            matches &= sameStatistics(readStatistics(), byRedis);
        }
        evidence.row(map("kind", "incremental", "rows", size, "newOrders", NEW_ORDERS,
                "A_full_group_by_ms", List.of(aTimes[0], aTimes[1], aTimes[2]),
                "A_medianMs", Stopwatch.median(aTimes),
                "B_redis_incremental_ms", List.of(bTimes[0], bTimes[1], bTimes[2]),
                "B_medianMs", Stopwatch.median(bTimes),
                "matchesGroupByResult", matches));
        // 다음 규모 실험을 위해 원래 건수로 되돌린다
        jdbc.update("DELETE FROM orders WHERE id > ?", size);
    }

    /** lastId 이후의 주문만 Redis 에 더하고, 바뀐 (날짜, 카테고리) 통계 행만 UPSERT 한다. */
    private void applyNewOrders(long lastId) {
        java.util.Set<String> touchedKeys = new java.util.TreeSet<>();
        long cursor = lastId;
        while (true) {
            List<Row> rows = jdbc.query(CHUNK_SQL, (rs, i) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4)), cursor, CHUNK);
            if (rows.isEmpty()) break;
            redis.executePipelined((RedisCallback<Object>) connection -> {
                for (Row row : rows) {
                    byte[] key = bytes(KEY_PREFIX + row.date());
                    connection.hashCommands().hIncrBy(key, bytes(row.category() + ":count"), 1);
                    connection.hashCommands().hIncrBy(key, bytes(row.category() + ":amount"), row.amount());
                }
                return null;
            });
            rows.forEach(row -> touchedKeys.add(row.date() + "|" + row.category()));
            cursor = rows.getLast().id();
        }
        List<Object[]> params = new ArrayList<>();
        for (String touched : touchedKeys) {
            String date = touched.substring(0, touched.indexOf('|'));
            String category = touched.substring(touched.indexOf('|') + 1);
            List<Object> values = redis.opsForHash().multiGet(KEY_PREFIX + date, List.of(category + ":count", category + ":amount"));
            params.add(new Object[]{Date.valueOf(date), category, Long.parseLong((String) values.get(0)), Long.parseLong((String) values.get(1))});
        }
        jdbc.batchUpdate("""
                INSERT INTO order_statistics (stat_date, category, order_count, total_amount) VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE order_count = VALUES(order_count), total_amount = VALUES(total_amount)""", params);
    }

    /** 방식 A: DB 가 JOIN + GROUP BY + SUM 을 모두 계산한다. */
    private void aggregateWithGroupBy() {
        jdbc.update("INSERT INTO order_statistics (stat_date, category, order_count, total_amount) " + GROUP_BY_SQL);
    }

    /** 방식 B: DB 는 단순 조회만, 누적은 Redis HINCRBY. pipeline=false 면 명령마다 왕복한다. */
    private void aggregateWithRedis(boolean pipeline, long[] split) {
        long lastId = 0;
        long dbMs = 0, redisMs = 0;
        while (true) {
            long start = System.nanoTime();
            List<Row> rows = jdbc.query(CHUNK_SQL, (rs, i) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4)), lastId, CHUNK);
            dbMs += (System.nanoTime() - start) / 1_000_000;
            if (rows.isEmpty()) break;

            start = System.nanoTime();
            if (pipeline) {
                redis.executePipelined((RedisCallback<Object>) connection -> {
                    for (Row row : rows) {
                        byte[] key = bytes(KEY_PREFIX + row.date());
                        connection.hashCommands().hIncrBy(key, bytes(row.category() + ":count"), 1);
                        connection.hashCommands().hIncrBy(key, bytes(row.category() + ":amount"), row.amount());
                    }
                    return null;
                });
            } else {
                for (Row row : rows) {
                    String key = KEY_PREFIX + row.date();
                    redis.opsForHash().increment(key, row.category() + ":count", 1);
                    redis.opsForHash().increment(key, row.category() + ":amount", row.amount());
                }
            }
            redisMs += (System.nanoTime() - start) / 1_000_000;
            lastId = rows.getLast().id();
        }

        // Redis 에 누적한 값을 RDB 통계 테이블로 옮긴다.
        long start = System.nanoTime();
        List<Object[]> params = new ArrayList<>();
        for (String key : scanKeys()) {
            String date = key.substring(KEY_PREFIX.length());
            Map<Object, Object> hash = redis.opsForHash().entries(key);
            Map<String, long[]> byCategory = new TreeMap<>();
            hash.forEach((field, value) -> {
                String f = (String) field;
                String category = f.substring(0, f.lastIndexOf(':'));
                long[] acc = byCategory.computeIfAbsent(category, k -> new long[2]);
                if (f.endsWith(":count")) acc[0] = Long.parseLong((String) value);
                else acc[1] = Long.parseLong((String) value);
            });
            byCategory.forEach((category, acc) -> params.add(new Object[]{Date.valueOf(date), category, acc[0], acc[1]}));
            redis.expire(key, java.time.Duration.ofHours(1));
        }
        jdbc.batchUpdate("INSERT INTO order_statistics (stat_date, category, order_count, total_amount) VALUES (?, ?, ?, ?)", params);
        redisMs += (System.nanoTime() - start) / 1_000_000;
        split[0] = dbMs;
        split[1] = redisMs;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private Map<String, long[]> readStatistics() {
        Map<String, long[]> result = new TreeMap<>();
        jdbc.query("SELECT stat_date, category, order_count, total_amount FROM order_statistics", rs -> {
            result.put(rs.getString(1) + "|" + rs.getString(2), new long[]{rs.getLong(3), rs.getLong(4)});
        });
        return result;
    }

    private static boolean sameStatistics(Map<String, long[]> a, Map<String, long[]> b) {
        if (!a.keySet().equals(b.keySet())) return false;
        for (var e : a.entrySet()) {
            long[] other = b.get(e.getKey());
            if (other[0] != e.getValue()[0] || other[1] != e.getValue()[1]) return false;
        }
        return true;
    }

    private Set<String> scanKeys() {
        return redis.keys(KEY_PREFIX + "*"); // 실험용. 운영에서는 SCAN 을 쓴다.
    }

    private void clearRedis() {
        Set<String> keys = scanKeys();
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    private long usedMemory() {
        return Long.parseLong(redisInfo("memory").getProperty("used_memory"));
    }

    private Properties redisInfo(String section) {
        return redis.execute((RedisCallback<Properties>) c -> c.serverCommands().info(section));
    }
}

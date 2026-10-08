package com.dingco.labs.w3;

import com.dingco.labs.common.Evidence;
import com.dingco.labs.common.OrderSeeder;
import com.dingco.labs.common.Stopwatch;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.dingco.labs.common.Evidence.map;

/**
 * 3주차 부록: 통계 쿼리의 Using temporary / Using filesort 를 인덱스 설계로 없앨 수 있는가.
 * 실행: ./gradlew bootRun --args='--lab=w3'
 */
@Component
@Profile("w3")
public class IndexLab implements ApplicationRunner {

    /** 월간 상품별 매출. orders 테이블 컬럼만 쓴다. */
    static final String PRODUCT_STATS = """
            SELECT product_id, COUNT(*) AS order_count, SUM(amount) AS total_amount
            FROM orders
            WHERE order_date >= '2025-03-01' AND order_date < '2025-04-01'
            GROUP BY product_id
            ORDER BY product_id""";

    /** 일별 카테고리 매출. GROUP BY 에 다른 테이블(products) 컬럼이 섞인다. */
    static final String CATEGORY_STATS = """
            SELECT p.category, o.order_date, COUNT(*) AS order_count, SUM(o.amount) AS total_amount
            FROM orders o
            JOIN products p ON p.id = o.product_id
            WHERE o.order_date >= '2025-03-01' AND o.order_date < '2025-04-01'
            GROUP BY o.order_date, p.category
            ORDER BY o.order_date, p.category""";

    /** 시나리오 이름 → 추가할 인덱스 DDL (null 이면 인덱스 없음) */
    static final Map<String, String> SCENARIOS = new LinkedHashMap<>();

    static {
        SCENARIOS.put("A_no_index", null);
        SCENARIOS.put("B_single_date", "CREATE INDEX idx_orders_date ON orders (order_date)");
        SCENARIOS.put("C_composite_date_first", "CREATE INDEX idx_orders_date_product ON orders (order_date, product_id, amount)");
        SCENARIOS.put("D_composite_product_first", "CREATE INDEX idx_orders_product_date ON orders (product_id, order_date, amount)");
    }

    private static final int QUERY_REPEAT = 5;
    private static final int INSERT_REPEAT = 3;
    private static final long SEED = 20261008L;

    private final JdbcTemplate jdbc;
    private final OrderSeeder seeder;

    public IndexLab(JdbcTemplate jdbc, OrderSeeder seeder) {
        this.jdbc = jdbc;
        this.seeder = seeder;
    }

    @Override
    public void run(ApplicationArguments args) {
        Evidence evidence = new Evidence("w3-index")
                .meta("mysql", jdbc.queryForObject("SELECT VERSION()", String.class))
                .meta("productStatsSql", PRODUCT_STATS)
                .meta("categoryStatsSql", CATEGORY_STATS);

        // 1) 인덱스가 INSERT 비용을 얼마나 늘리는가: 시나리오별로 10만 건 적재를 3회 반복
        for (var scenario : SCENARIOS.entrySet()) {
            long[] times = new long[INSERT_REPEAT];
            for (int i = 0; i < INSERT_REPEAT; i++) {
                prepare(scenario.getValue());
                times[i] = Stopwatch.ms(() -> seeder.insertOrders(100_000, SEED));
            }
            evidence.row(map("kind", "insert", "scenario", scenario.getKey(), "rows", 100_000,
                    "insertMs", toList(times), "insertMedianMs", Stopwatch.median(times)));
        }

        // 2) 데이터 규모별 실행계획·실행시간·인덱스 크기
        for (int size : new int[]{100_000, 500_000}) {
            for (var scenario : SCENARIOS.entrySet()) {
                prepare(scenario.getValue());
                seeder.insertOrders(size, SEED);
                jdbc.execute("ANALYZE TABLE orders");

                evidence.row(map(
                        "kind", "query",
                        "scenario", scenario.getKey(),
                        "rows", size,
                        "productStats", measure(PRODUCT_STATS),
                        "categoryStats", measure(CATEGORY_STATS),
                        "indexBytes", indexBytes()));
            }
        }
        evidence.save();
    }

    private void prepare(String indexDdl) {
        seeder.createTables();
        if (indexDdl != null) {
            jdbc.execute(indexDdl);
        }
    }

    private Map<String, Object> measure(String sql) {
        List<Map<String, Object>> plan = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList("EXPLAIN " + sql)) {
            plan.add(map("table", row.get("table"), "type", row.get("type"), "key", row.get("key"),
                    "rows", row.get("rows"), "extra", row.get("Extra")));
        }
        String analyze = jdbc.queryForObject("EXPLAIN ANALYZE " + sql, String.class);

        jdbc.queryForList(sql); // 워밍업 1회는 기록하지 않는다
        long[] times = new long[QUERY_REPEAT];
        int[] resultRows = new int[1];
        for (int i = 0; i < QUERY_REPEAT; i++) {
            times[i] = Stopwatch.ms(() -> resultRows[0] = jdbc.queryForList(sql).size());
        }
        return map("plan", plan,
                "explainAnalyze", analyze,
                "resultRows", resultRows[0],
                "queryMs", toList(times),
                "queryMedianMs", Stopwatch.median(times));
    }

    private Map<String, Object> indexBytes() {
        Map<String, Object> sizes = new LinkedHashMap<>();
        jdbc.query("""
                SELECT index_name, stat_value * @@innodb_page_size AS bytes
                FROM mysql.innodb_index_stats
                WHERE database_name = 'labs' AND table_name = 'orders' AND stat_name = 'size'
                ORDER BY index_name""", rs -> {
            sizes.put(rs.getString("index_name"), rs.getLong("bytes"));
        });
        return sizes;
    }

    private static List<Long> toList(long[] values) {
        List<Long> list = new ArrayList<>();
        for (long v : values) list.add(v);
        return list;
    }
}

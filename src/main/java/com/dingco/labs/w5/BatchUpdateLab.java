package com.dingco.labs.w5;

import com.dingco.labs.common.Evidence;
import com.dingco.labs.common.Stopwatch;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.dingco.labs.common.Evidence.map;

/**
 * 5주차 부록: 포인트 적립 배치에서 UPDATE 를 어떻게 보낼 것인가.
 * 모든 전략은 같은 청크를 읽고(id 범위) 각 행에 100 포인트를 더한 값을 쓴다.
 * 실행: ./gradlew bootRun --args='--lab=w5'
 */
@Component
@Profile("w5")
public class BatchUpdateLab implements ApplicationRunner {

    private static final long ADD = 100;
    private static final int REPEAT = 3;
    private static final String UPDATE_SQL = "UPDATE user_point SET point = ?, updated_at = ? WHERE id = ?";

    private final JdbcTemplate jdbc;
    private final EntityManager em;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbcNoRewrite;
    private final TransactionTemplate txNoRewrite;

    public BatchUpdateLab(JdbcTemplate jdbc, EntityManager em, PlatformTransactionManager txManager, HikariDataSource dataSource) {
        this.jdbc = jdbc;
        this.em = em;
        this.tx = new TransactionTemplate(txManager);

        // 같은 DB 에 rewriteBatchedStatements=false 로 붙는 두 번째 커넥션 풀
        HikariDataSource noRewrite = new HikariDataSource();
        noRewrite.setJdbcUrl(dataSource.getJdbcUrl().replace("rewriteBatchedStatements=true", "rewriteBatchedStatements=false"));
        noRewrite.setUsername(dataSource.getUsername());
        noRewrite.setPassword(dataSource.getPassword());
        noRewrite.setMaximumPoolSize(2);
        this.jdbcNoRewrite = new JdbcTemplate(noRewrite);
        this.txNoRewrite = new TransactionTemplate(new DataSourceTransactionManager(noRewrite));
    }

    interface Strategy {
        void apply(long fromId, long toId, LocalDateTime now);
    }

    @Override
    public void run(ApplicationArguments args) {
        Evidence evidence = new Evidence("w5-batch-update")
                .meta("mysql", jdbc.queryForObject("SELECT VERSION()", String.class))
                .meta("updateSql", UPDATE_SQL);

        int[] sizes = {10_000, 50_000, 100_000};
        for (int size : sizes) {
            run(evidence, "A_dirty_checking", size, 1_000, (from, to, now) -> dirtyChecking(from, to, now, 0));
            run(evidence, "A2_dirty_checking_jdbc_batch", size, 1_000, (from, to, now) -> dirtyChecking(from, to, now, 1_000));
            run(evidence, "B_single_update", size, 1_000, (from, to, now) -> singleUpdate(from, to, now));
            for (int chunk : new int[]{500, 1_000, 2_000}) {
                run(evidence, "C_jdbc_batch_rewrite_on", size, chunk, (from, to, now) -> jdbcBatch(jdbc, tx, from, to, now));
            }
            run(evidence, "C2_jdbc_batch_rewrite_off", size, 1_000, (from, to, now) -> jdbcBatch(jdbcNoRewrite, txNoRewrite, from, to, now));
        }
        evidence.save();
    }

    private void run(Evidence evidence, String name, int size, int chunk, Strategy strategy) {
        long[] times = new long[REPEAT];
        long comUpdate = 0;
        long questions = 0;
        boolean consistent = true;
        for (int r = 0; r < REPEAT; r++) {
            seed(size);
            long sumBefore = sumPoint();
            long updatesBefore = status("Com_update");
            long questionsBefore = status("Questions");
            LocalDateTime now = LocalDateTime.now();
            times[r] = Stopwatch.ms(() -> {
                for (long from = 1; from <= size; from += chunk) {
                    strategy.apply(from, Math.min(from + chunk - 1, size), now);
                }
            });
            comUpdate = status("Com_update") - updatesBefore;
            questions = status("Questions") - questionsBefore;
            consistent &= sumPoint() == sumBefore + ADD * size;
        }
        evidence.row(map("strategy", name, "rows", size, "chunk", chunk,
                "elapsedMs", List.of(times[0], times[1], times[2]),
                "medianMs", Stopwatch.median(times),
                "comUpdateDelta", comUpdate,
                "questionsDelta", questions,
                "sumConsistent", consistent));
    }

    /** A: 엔티티를 읽어 값을 바꾸고 커밋 시 변경 감지로 UPDATE. batchSize=0 이면 JDBC 배치 없음. */
    private void dirtyChecking(long from, long to, LocalDateTime now, int batchSize) {
        tx.executeWithoutResult(status -> {
            if (batchSize > 0) {
                em.unwrap(Session.class).setJdbcBatchSize(batchSize);
            }
            List<UserPoint> points = em.createQuery(
                            "select u from UserPoint u where u.id between :from and :to", UserPoint.class)
                    .setParameter("from", from)
                    .setParameter("to", to)
                    .getResultList();
            points.forEach(p -> p.add(ADD, now));
        });
        em.clear();
    }

    /** B: 영속성 컨텍스트 없이 한 행씩 UPDATE 를 보낸다. (Querydsl update().execute() 반복과 같은 SQL 흐름) */
    private void singleUpdate(long from, long to, LocalDateTime now) {
        tx.executeWithoutResult(status -> {
            for (Object[] row : read(jdbc, from, to)) {
                jdbc.update(UPDATE_SQL, row[1], Timestamp.valueOf(now), row[0]);
            }
        });
    }

    /** C: 청크 단위로 JdbcTemplate.batchUpdate 를 보낸다. */
    private void jdbcBatch(JdbcTemplate template, TransactionTemplate txTemplate, long from, long to, LocalDateTime now) {
        txTemplate.executeWithoutResult(status -> {
            List<Object[]> params = new ArrayList<>();
            for (Object[] row : read(template, from, to)) {
                params.add(new Object[]{row[1], Timestamp.valueOf(now), row[0]});
            }
            template.batchUpdate(UPDATE_SQL, params);
        });
    }

    /** 청크를 읽고 [id, 새 포인트] 를 돌려준다. */
    private List<Object[]> read(JdbcTemplate template, long from, long to) {
        return template.query("SELECT id, point FROM user_point WHERE id BETWEEN ? AND ?",
                (rs, i) -> new Object[]{rs.getLong("id"), rs.getLong("point") + ADD}, from, to);
    }

    private void seed(int size) {
        jdbc.execute("DROP TABLE IF EXISTS user_point");
        jdbc.execute("""
                CREATE TABLE user_point (
                  id BIGINT PRIMARY KEY,
                  user_id BIGINT NOT NULL,
                  point BIGINT NOT NULL,
                  updated_at DATETIME(6) NULL
                )""");
        List<Object[]> rows = new ArrayList<>(5_000);
        for (long i = 1; i <= size; i++) {
            rows.add(new Object[]{i, 100_000 + i, i % 1_000});
            if (rows.size() == 5_000) {
                jdbc.batchUpdate("INSERT INTO user_point (id, user_id, point) VALUES (?, ?, ?)", rows);
                rows.clear();
            }
        }
        if (!rows.isEmpty()) jdbc.batchUpdate("INSERT INTO user_point (id, user_id, point) VALUES (?, ?, ?)", rows);
    }

    private long sumPoint() {
        return jdbc.queryForObject("SELECT COALESCE(SUM(point), 0) FROM user_point", Long.class);
    }

    private long status(String name) {
        Map<String, Object> row = jdbc.queryForMap("SHOW GLOBAL STATUS LIKE ?", name);
        return Long.parseLong(String.valueOf(row.get("Value")));
    }
}

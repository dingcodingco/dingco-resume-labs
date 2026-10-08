package com.dingco.labs.w4;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 10ms 마다 HikariCP 풀 상태를 읽어 최대 사용 커넥션·최대 대기 스레드를 기록한다. */
@Component
@Profile("w4")
@RestController
public class PoolSampler {

    private final AtomicInteger maxActive = new AtomicInteger();
    private final AtomicInteger maxPending = new AtomicInteger();
    private final HikariDataSource dataSource;

    public PoolSampler(DataSource dataSource) {
        this.dataSource = (HikariDataSource) dataSource;
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pool-sampler");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::sample, 0, 10, TimeUnit.MILLISECONDS);
    }

    private void sample() {
        var pool = dataSource.getHikariPoolMXBean();
        if (pool == null) return;
        maxActive.accumulateAndGet(pool.getActiveConnections(), Math::max);
        maxPending.accumulateAndGet(pool.getThreadsAwaitingConnection(), Math::max);
    }

    @GetMapping("/w4/pool")
    public Map<String, Object> pool() {
        return Map.of(
                "maximumPoolSize", dataSource.getMaximumPoolSize(),
                "maxActiveObserved", maxActive.get(),
                "maxPendingObserved", maxPending.get());
    }
}

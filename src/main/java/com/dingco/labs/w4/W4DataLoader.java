package com.dingco.labs.w4;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Profile("w4")
public class W4DataLoader implements ApplicationRunner {

    public static final int PAYMENTS = 1_000;
    private final JdbcTemplate jdbc;

    public W4DataLoader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.execute("DROP TABLE IF EXISTS payment");
        jdbc.execute("DROP TABLE IF EXISTS merchant");
        jdbc.execute("CREATE TABLE merchant (id BIGINT PRIMARY KEY, name VARCHAR(50) NOT NULL)");
        jdbc.execute("""
                CREATE TABLE payment (
                  id BIGINT PRIMARY KEY,
                  merchant_id BIGINT NOT NULL,
                  amount BIGINT NOT NULL,
                  status VARCHAR(20) NOT NULL,
                  approved_at DATETIME(6) NULL
                )""");
        List<Object[]> merchants = new ArrayList<>();
        for (long i = 1; i <= 10; i++) merchants.add(new Object[]{i, "merchant-" + i});
        jdbc.batchUpdate("INSERT INTO merchant (id, name) VALUES (?, ?)", merchants);
        List<Object[]> payments = new ArrayList<>();
        for (long i = 1; i <= PAYMENTS; i++) payments.add(new Object[]{i, 1 + i % 10, 10_000 + i});
        jdbc.batchUpdate("INSERT INTO payment (id, merchant_id, amount, status) VALUES (?, ?, ?, 'READY')", payments);
        System.out.println("[w4] ready: payments=" + PAYMENTS);
    }
}

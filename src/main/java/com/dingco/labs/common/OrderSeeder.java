package com.dingco.labs.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 3주차·6주차 공통 데이터: users, products, orders.
 * 시드를 고정해 같은 건수면 언제 실행해도 같은 데이터가 만들어진다.
 */
@Component
public class OrderSeeder {

    public static final int PRODUCTS = 1_000;
    public static final int CATEGORIES = 20;
    public static final int USERS = 10_000;
    public static final LocalDate FIRST_DAY = LocalDate.of(2025, 1, 1);
    public static final int DAYS = 365;

    private final JdbcTemplate jdbc;

    public OrderSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void createTables() {
        jdbc.execute("DROP TABLE IF EXISTS order_statistics");
        jdbc.execute("DROP TABLE IF EXISTS orders");
        jdbc.execute("DROP TABLE IF EXISTS products");
        jdbc.execute("DROP TABLE IF EXISTS users");
        jdbc.execute("""
                CREATE TABLE users (
                  id BIGINT PRIMARY KEY,
                  name VARCHAR(50) NOT NULL
                )""");
        jdbc.execute("""
                CREATE TABLE products (
                  id BIGINT PRIMARY KEY,
                  category VARCHAR(30) NOT NULL,
                  name VARCHAR(100) NOT NULL
                )""");
        jdbc.execute("""
                CREATE TABLE orders (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  user_id BIGINT NOT NULL,
                  product_id BIGINT NOT NULL,
                  quantity INT NOT NULL,
                  amount BIGINT NOT NULL,
                  order_date DATE NOT NULL
                )""");
        jdbc.execute("""
                CREATE TABLE order_statistics (
                  stat_date DATE NOT NULL,
                  category VARCHAR(30) NOT NULL,
                  order_count BIGINT NOT NULL,
                  total_amount BIGINT NOT NULL,
                  PRIMARY KEY (stat_date, category)
                )""");

        List<Object[]> users = new ArrayList<>();
        for (long i = 1; i <= USERS; i++) users.add(new Object[]{i, "user-" + i});
        jdbc.batchUpdate("INSERT INTO users (id, name) VALUES (?, ?)", users);

        List<Object[]> products = new ArrayList<>();
        for (long i = 1; i <= PRODUCTS; i++) {
            products.add(new Object[]{i, "category-" + String.format("%02d", i % CATEGORIES), "product-" + i});
        }
        jdbc.batchUpdate("INSERT INTO products (id, category, name) VALUES (?, ?, ?)", products);
    }

    /** orders 에 count 건을 넣는다. seed 가 같으면 같은 행이 들어간다. */
    public void insertOrders(int count, long seed) {
        Random random = new Random(seed);
        List<Object[]> chunk = new ArrayList<>(5_000);
        for (int i = 0; i < count; i++) {
            int quantity = 1 + random.nextInt(5);
            chunk.add(new Object[]{
                    1 + random.nextInt(USERS),
                    1 + random.nextInt(PRODUCTS),
                    quantity,
                    (long) quantity * (1_000 + random.nextInt(99_000)),
                    Date.valueOf(FIRST_DAY.plusDays(random.nextInt(DAYS)))
            });
            if (chunk.size() == 5_000) {
                flush(chunk);
            }
        }
        flush(chunk);
    }

    private void flush(List<Object[]> chunk) {
        if (chunk.isEmpty()) return;
        jdbc.batchUpdate("INSERT INTO orders (user_id, product_id, quantity, amount, order_date) VALUES (?, ?, ?, ?, ?)", chunk);
        chunk.clear();
    }
}

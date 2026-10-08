package com.dingco.labs.common;

import java.util.Arrays;

public final class Stopwatch {

    private Stopwatch() {
    }

    public static long ms(Runnable task) {
        long start = System.nanoTime();
        task.run();
        return (System.nanoTime() - start) / 1_000_000;
    }

    public static long median(long[] values) {
        long[] copy = values.clone();
        Arrays.sort(copy);
        return copy[copy.length / 2];
    }
}

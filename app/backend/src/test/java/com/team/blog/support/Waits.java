package com.team.blog.support;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/** 다른 스레드가 하는 일을 테스트가 기다릴 때 쓴다. 조건을 짧은 간격으로 다시 확인한다 */
public final class Waits {
    private static final Duration STEP = Duration.ofMillis(5);

    private Waits() {
    }

    /** 조건이 참이 될 때까지 기다린다. @return 시간 안에 참이 되었는가 */
    public static boolean until(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) return false;
            pause(STEP);
        }
        return true;
    }

    /** 확인을 period 동안 되풀이한다. 그동안 한 번이라도 어긋나면 그 실패를 그대로 던진다 */
    public static void during(Duration period, Runnable check) throws InterruptedException {
        long deadline = System.nanoTime() + period.toNanos();
        do {
            check.run();
            pause(STEP);
        } while (System.nanoTime() - deadline < 0);
        check.run();
    }

    /** 시각이 확실히 지나가게 잠깐 쉰다 (같은 시각에 두 기록이 겹치지 않게) */
    public static void pause(Duration d) throws InterruptedException {
        long deadline = System.nanoTime() + d.toNanos();
        for (long left = d.toNanos(); left > 0; left = deadline - System.nanoTime()) {
            LockSupport.parkNanos(left);
            if (Thread.interrupted()) throw new InterruptedException();
        }
    }
}

package org.example.fruitpickingrobt.service.control;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 急停守护器的并发安全验证。
 *
 * 核心要证的一件事：多线程同时调 trigger 时，只有一个线程能"首次置位成功"，
 * 其余全部看到已激活状态——这是急停不被运动指令覆盖的基础。
 */
class EmergencyStopGuardTest {

    private EmergencyStopGuard guard;

    @BeforeEach
    void setUp() {
        guard = new EmergencyStopGuard();
    }

    @Test
    void firstTriggerReturnsTrueAndActivates() {
        assertThat(guard.trigger("car-001")).isTrue();
        assertThat(guard.isActive("car-001")).isTrue();
    }

    @Test
    void repeatedTriggerReturnsFalseWithoutReactivating() {
        guard.trigger("car-001");
        assertThat(guard.trigger("car-001")).isFalse();
        assertThat(guard.isActive("car-001")).isTrue();
    }

    @Test
    void releaseClearsTheFlag() {
        guard.trigger("car-001");
        guard.release("car-001");
        assertThat(guard.isActive("car-001")).isFalse();
        // 释放后再次触发应重新返回 true
        assertThat(guard.trigger("car-001")).isTrue();
    }

    @Test
    void releaseWhenNotActiveIsNoOp() {
        guard.release("never-seen"); // 不应抛异常
        assertThat(guard.isActive("never-seen")).isFalse();
    }

    @Test
    void removeClearsStateForDisconnectedDevice() {
        guard.trigger("car-001");
        guard.remove("car-001");
        assertThat(guard.isActive("car-001")).isFalse();
        assertThat(guard.trigger("car-001")).isTrue();
    }

    @Test
    void flagsAreIsolatedPerDevice() {
        guard.trigger("car-001");
        assertThat(guard.isActive("car-001")).isTrue();
        assertThat(guard.isActive("car-002")).isFalse();

        guard.release("car-001");
        assertThat(guard.isActive("car-001")).isFalse();
    }

    /**
     * 并发压力测试：64 个线程同时对同一设备触发急停，
     * 必须且只能有一个线程拿到"首次触发"（返回 true）。
     */
    @Test
    void triggerIsAtomicUnderConcurrency() throws Exception {
        int threadCount = 64;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        AtomicInteger firstTimeCount = new AtomicInteger();
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    if (guard.trigger("car-x")) {
                        firstTimeCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        doneGate.await();
        pool.shutdown();

        assertThat(firstTimeCount.get()).isEqualTo(1);
        assertThat(guard.isActive("car-x")).isTrue();
    }
}

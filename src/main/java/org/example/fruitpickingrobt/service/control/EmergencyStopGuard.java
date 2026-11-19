package org.example.fruitpickingrobt.service.control;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 急停状态守护器 —— 多线程安全的急停标志管理。
 *
 * 核心问题：
 *   急停（emergency_stop）和运动指令（forwardMotion）跑在不同线程上。
 *   如果急停刚把目标速度清为 (0,0)，另一个线程的 forwardMotion 紧接着又写入 (0.5, 0)，
 *   急停就被"覆盖"了，车不会停。
 *
 * 解决方案：
 *   用 ConcurrentHashMap<deviceId, AtomicBoolean> 记录每台车的急停状态。
 *   - trigger(deviceId)：置位急停标志（CAS 保证只有首次触发返回 true）
 *   - release(deviceId)：清除急停标志（执行 clear_error 时调用）
 *   - isActive(deviceId)：查询急停是否激活
 *
 * forwardMotion 在发送前先检查 isActive，激活则拦截非零运动，
 * 从根本上杜绝"急停被运动指令覆盖"的竞态。
 */
@Component
@Slf4j
public class EmergencyStopGuard {

    /** deviceId -> 急停是否激活 */
    private final ConcurrentHashMap<String, AtomicBoolean> flags = new ConcurrentHashMap<>();

    /**
     * 触发急停。
     *
     * @return true 表示本次是"首次触发"（之前未激活），用于日志去重；
     *         false 表示急停已经激活，本次是重复触发。
     */
    public boolean trigger(String deviceId) {
        boolean firstTime = flags
                .computeIfAbsent(deviceId, k -> new AtomicBoolean(false))
                .compareAndSet(false, true);
        if (firstTime) {
            log.warn("[急停] 触发 deviceId={}", deviceId);
        }
        return firstTime;
    }

    /**
     * 释放急停（执行 clear_error 清错时调用）。
     * 释放后运动指令才能正常下发。
     */
    public void release(String deviceId) {
        AtomicBoolean flag = flags.get(deviceId);
        if (flag != null && flag.compareAndSet(true, false)) {
            log.info("[急停] 释放 deviceId={}", deviceId);
        }
    }

    /** 急停是否激活 */
    public boolean isActive(String deviceId) {
        AtomicBoolean flag = flags.get(deviceId);
        return flag != null && flag.get();
    }

    /** 清除某设备的急停记录（设备断线时调用，避免僵尸状态） */
    public void remove(String deviceId) {
        flags.remove(deviceId);
    }
}

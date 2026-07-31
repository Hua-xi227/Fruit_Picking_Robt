package org.example.fruitpickingrobt.store;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 视频帧存储服务
 * 负责保存小车端上传的最新一帧 JPEG 图片
 * 前端可以通过 /api/video/stream 接口拉取
 */
@Component
public class VideoFrameStore {

    // 使用 AtomicReference 保证多线程下替换帧的原子性
    private final AtomicReference<byte[]> currentFrame = new AtomicReference<>(new byte[0]);

    // 记录每个设备上传的视频帧
    private final ConcurrentHashMap<String, byte[]> deviceFrames = new ConcurrentHashMap<>();

    // 记录最后更新时间
    private final AtomicReference<Long> lastUpdateTime = new AtomicReference<>(0L);

    /**
     * 存储最新一帧（默认设备）
     */
    public void putFrame(byte[] jpegBytes) {
        currentFrame.set(jpegBytes);
        lastUpdateTime.set(System.currentTimeMillis());
    }

    /**
     * 存储指定设备的最新一帧
     */
    public void putFrame(String deviceId, byte[] jpegBytes) {
        if (deviceId == null || deviceId.isEmpty()) {
            putFrame(jpegBytes);
            return;
        }
        deviceFrames.put(deviceId, jpegBytes);
        currentFrame.set(jpegBytes);
        lastUpdateTime.set(System.currentTimeMillis());
    }

    /**
     * 获取默认设备的最新一帧
     */
    public byte[] getFrame() {
        return currentFrame.get();
    }

    /**
     * 获取指定设备的最新一帧
     */
    public byte[] getFrame(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) {
            return currentFrame.get();
        }
        return deviceFrames.getOrDefault(deviceId, currentFrame.get());
    }

    /**
     * 获取最后更新时间
     */
    public Long getLastUpdateTime() {
        return lastUpdateTime.get();
    }

    /**
     * 是否有帧数据
     */
    public boolean hasFrame() {
        return currentFrame.get() != null && currentFrame.get().length > 0;
    }

    /**
     * 判断当前帧是否已过期（超过指定毫秒数未更新）
     */
    public boolean isFrameExpired(long timeoutMs) {
        Long last = lastUpdateTime.get();
        if (last == null || last == 0L) {
            return true;
        }
        return System.currentTimeMillis() - last > timeoutMs;
    }
}
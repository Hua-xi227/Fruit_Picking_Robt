package org.example.fruitpickingrobt.controller;

import org.example.fruitpickingrobt.entity.Result;
import org.example.fruitpickingrobt.store.VideoFrameStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 视频流代理接口
 * 实现 MJPEG 视频流转发
 *
 * 核心原理：
 * 1. MJPEG 是一种简单的视频流格式，由连续的 JPEG 图片组成
 * 2. 每帧以 --myboundary 开头，后面跟 Content-Type: image/jpeg 和图片二进制数据
 * 3. 前端 <img src="/api/video/stream"> 标签会持续接收这些帧并显示
 *
 * 真实环境：小车端会通过 RTSP/MJPEG 协议持续推送视频帧
 * 模拟环境：H5 模拟器定时通过 /api/video/upload 上传图片
 * 代理职责：把最新的帧以 MJPEG 格式持续推给前端
 */
@RestController
@RequestMapping("/api/video")
@CrossOrigin(origins = "*")
public class VideoStreamController {

    private static final Logger log = LoggerFactory.getLogger(VideoStreamController.class);

    // MJPEG 分隔符（每一帧的开头标记）
    private static final String BOUNDARY = "mjpegboundary";

    @Autowired
    private VideoFrameStore videoFrameStore;

    /**
     * MJPEG 视频流代理
     * GET /api/video/stream?deviceId=car-001
     *
     * 返回 multipart/x-mixed-replace 流，前端用 <img> 标签接收
     */
    @GetMapping(value = "/stream", produces = "multipart/x-mixed-replace;boundary=" + BOUNDARY)
    public StreamingResponseBody streamVideo(@RequestParam(value = "deviceId", required = false) String deviceId) {
        log.info("前端开始拉取视频流：deviceId={}", deviceId);

        return outputStream -> {
            // 调度器：每 200ms 检查一次是否有新帧
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
            try {
                byte[] lastFrame = null;
                long startTime = System.currentTimeMillis();
                // 最多推送 5 分钟（避免连接永远不释放）
                long maxDuration = 5 * 60 * 1000L;

                while (System.currentTimeMillis() - startTime < maxDuration) {
                    byte[] currentFrame = videoFrameStore.getFrame(deviceId);
                    if (currentFrame != null && currentFrame.length > 0) {
                        // 只有当帧有变化时才推送（避免重复发送）
                        if (lastFrame == null || currentFrame != lastFrame) {
                            // 写入 MJPEG 帧头
                            String header = "--" + BOUNDARY + "\r\n"
                                    + "Content-Type: image/jpeg\r\n"
                                    + "Content-Length: " + currentFrame.length + "\r\n\r\n";
                            outputStream.write(header.getBytes());
                            outputStream.write(currentFrame);
                            outputStream.write("\r\n".getBytes());
                            outputStream.flush();
                            lastFrame = currentFrame;
                            log.debug("推送视频帧：size={} bytes", currentFrame.length);
                        }
                    }
                    // 等待 200ms 再检查下一帧
                    Thread.sleep(200);
                }
            } catch (InterruptedException e) {
                log.info("视频流推送被中断");
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("视频流推送异常", e);
            } finally {
                scheduler.shutdownNow();
                log.info("视频流推送结束");
            }
        };
    }

    /**
     * 获取当前最新一帧（用于测试或快照）
     * GET /api/video/snapshot
     */
    @GetMapping(value = "/snapshot", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> getSnapshot(@RequestParam(value = "deviceId", required = false) String deviceId) {
        byte[] frame = videoFrameStore.getFrame(deviceId);
        if (frame == null || frame.length == 0) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(frame);
    }

    /**
     * 查询视频流状态
     * GET /api/video/status
     */
    @GetMapping("/status")
    public Result<Object> getStatus(@RequestParam(value = "deviceId", required = false) String deviceId) {
        boolean hasFrame = videoFrameStore.hasFrame();
        Long lastUpdate = videoFrameStore.getLastUpdateTime();
        return Result.success(new VideoStatus(hasFrame, lastUpdate, deviceId));
    }

    /**
     * 视频状态响应体
     */
    public static class VideoStatus {
        public boolean hasFrame;
        public Long lastUpdateTime;
        public String deviceId;

        public VideoStatus(boolean hasFrame, Long lastUpdateTime, String deviceId) {
            this.hasFrame = hasFrame;
            this.lastUpdateTime = lastUpdateTime;
            this.deviceId = deviceId;
        }
    }
}

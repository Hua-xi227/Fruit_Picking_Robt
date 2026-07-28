package org.example.fruitpickingrobt.controller;

import org.example.fruitpickingrobt.entity.Result;
import org.example.fruitpickingrobt.store.VideoFrameStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 视频上传接口
 * 小车端通过 multipart/form-data 方式上传 JPEG 图片帧
 * POST /api/video/upload
 */
@RestController
@RequestMapping("/api/video")
@CrossOrigin(origins = "*")
public class VideoUploadController {

    private static final Logger log = LoggerFactory.getLogger(VideoUploadController.class);

    @Autowired
    private VideoFrameStore videoFrameStore;

    /**
     * 上传一帧 JPEG 图片
     * 小车端调用示例（curl）：
     * curl -X POST http://localhost:8082/api/video/upload \
     *   -F "file=@frame.jpg" \
     *   -F "deviceId=car-001"
     */
    @PostMapping("/upload")
    public Result uploadFrame(@RequestParam("file") MultipartFile file,
                              @RequestParam(value = "deviceId", required = false) String deviceId) {
        if (file.isEmpty()) {
            return Result.failure("文件为空");
        }
        try {
            byte[] bytes = file.getBytes();
            if (deviceId != null && !deviceId.isEmpty()) {
                videoFrameStore.putFrame(deviceId, bytes);
            } else {
                videoFrameStore.putFrame(bytes);
            }
            log.info("收到视频帧：deviceId={}, size={} bytes", deviceId, bytes.length);
            return Result.success("上传成功");
        } catch (IOException e) {
            log.error("读取上传文件失败", e);
            return Result.failure("读取文件失败: " + e.getMessage());
        }
    }
}
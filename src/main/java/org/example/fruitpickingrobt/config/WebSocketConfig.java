package org.example.fruitpickingrobt.config;

import org.example.fruitpickingrobt.websocket.DeviceSessionRegistry;
import org.example.fruitpickingrobt.websocket.FrontendWebSocketHandler;
import org.example.fruitpickingrobt.websocket.LocationWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * WebSocket 端点注册。
 *   /ws/location   设备专用（小车 / 模拟器）
 *                  上行：location / status / armParams / heartbeat / ack / result / chassis(50Hz)
 *                  下行：command（任务级指令）+ motion（底盘目标速度）
 *
 *   /ws/frontend   浏览器专用
 *                  上行：subscribe / unsubscribe / motion / action
 *                  下行：20Hz 遥测推送 + subscribed 回执
 *
 * 为什么删掉原来的 /ws/chassis：
 *   它和 /ws/location 共用同一个 DeviceSessionRegistry，两个端点各注册一次会话，
 *   同一个 deviceId 会互相顶替。现在底盘逻辑已并入 /ws/location，
 *   一个设备只需连一条连接，顶替问题从根上消失。
 *   （车载脚本 scout_bridge.py 的连接地址已同步改为 /ws/location）
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final LocationWebSocketHandler locationHandler;
    private final FrontendWebSocketHandler frontendHandler;
    private final DeviceSessionRegistry deviceSessionRegistry;

    public WebSocketConfig(LocationWebSocketHandler locationHandler,
                           FrontendWebSocketHandler frontendHandler,
                           DeviceSessionRegistry deviceSessionRegistry) {
        this.locationHandler = locationHandler;
        this.frontendHandler = frontendHandler;
        this.deviceSessionRegistry = deviceSessionRegistry;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 任务2: 注册端点，监听 /ws/location
        // 任务6: setAllowedOrigins("*") 允许跨域
        // 重点二改造：这是唯一的设备端点，底盘 50Hz 遥测也走这里（type=chassis）
        registry.addHandler(locationHandler, "/ws/location")
                .setAllowedOrigins("*");

        // 前端专用（订阅 20Hz 推送 + 下发运动指令）
        registry.addHandler(frontendHandler, "/ws/frontend")
                .setAllowedOrigins("*");
    }

    /**
     * WebSocket 单条消息缓冲区大小。
     * 默认只有 8KB，一帧 480×360 的 JPEG 转 base64 后约 40KB，
     * 会被服务端直接断连，导致 Dashboard 收不到任何视频帧（黑屏）。
     * 这里设为 1MB，足够 1080P 质量 0.8 的 JPEG（约 200KB base64）通过。
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(1024 * 1024);       // 文本帧 1MB
        container.setMaxBinaryMessageBufferSize(4 * 1024 * 1024); // 二进制帧 4MB（720p WebM 关键帧）
        return container;
    }
}

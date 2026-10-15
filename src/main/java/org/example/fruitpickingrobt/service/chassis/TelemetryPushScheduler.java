package org.example.fruitpickingrobt.service.chassis;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.example.fruitpickingrobt.websocket.FrontendSessionRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
/**
 * 系统侧从云服务器拉取数据的刷新频率 的实现 ★★★
 * "降频推送"
 *   1) 浏览器渲染能力有限：ECharts/地图每秒重绘 50 次会明显掉帧，20 次则丝滑
 *   2) 人眼分辨不出：20Hz 和 50Hz 的仪表盘看起来完全一样（显示器通常才 60Hz）
 *   3) 省带宽：20Hz 推送比 50Hz 少 60% 流量，手机端尤其明显
 *   4) 数据不失真：Redis 里始终存着 50Hz 的最新值，REST /latest 拿到的永远是最新的，
 *      降频只是"转发频率"，不是"数据精度"的损失
 *
 * 为什么是"服务端定时推"而不是"收到就转发"：
 *   - 收到就转发 = 50Hz 灌给前端（卡）
 *   - 服务端定时推 = 频率完全可控，改一个配置就能从 20Hz 调到 10Hz 或 30Hz，
 *     不需要改前端代码，也不需要重启小车
 *
 * ============================ 配置项 ============================
 *   app.chassis.push-interval-ms = 50   -> 20Hz（默认，推荐）
 *                                = 100  -> 10Hz（网络差/手机端）
 *                                = 33   -> 30Hz（追求极致流畅，本地局域网可用）
 *
 * 注意：fixedDelay 的实际频率 = 1000 / (push-interval-ms + 单次推送耗时)，
 *       推送耗时通常 < 5ms，所以设 50 就约等于 20Hz。
 */
@Component
@Slf4j
public class TelemetryPushScheduler {
    private final ChassisTelemetryService telemetryService;
    private final FrontendSessionRegistry frontendRegistry;

    public TelemetryPushScheduler(ChassisTelemetryService telemetryService,
                                  FrontendSessionRegistry frontendRegistry) {
        this.telemetryService = telemetryService;
        this.frontendRegistry = frontendRegistry;
    }

    /**
     * 按固定频率把所有在线设备的最新快照推给订阅它的前端。
     *
     * 只推"有人订阅的设备"：如果没有任何浏览器在看 CAR-01，就不做无用功。
     */
    @Scheduled(fixedDelayString = "${app.chassis.push-interval-ms:50}")
    public void pushAll() {
        Map<String, ObjectNode> snapshots = telemetryService.getLiveSnapshots();
        if (snapshots.isEmpty()) {
            return;
        }
        for (String deviceId : frontendRegistry.subscribedDeviceIds()) {
            ObjectNode snapshot = snapshots.get(deviceId);
            if (snapshot == null) {
                continue;   // 该设备还没上报过数据
            }
            // 只在数据发生变化/存在时推送；这里直接推最新快照（即使与上次相同也推，
            // 好处是前端能据此判断"连接还活着"）
            frontendRegistry.broadcast(deviceId, snapshot.toString());
        }
    }
}

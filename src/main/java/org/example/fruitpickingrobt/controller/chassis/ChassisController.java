package org.example.fruitpickingrobt.controller.chassis;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.example.fruitpickingrobt.dao.chassis.ChassisCommandDAO;
import org.example.fruitpickingrobt.dao.chassis.ChassisTelemetryDAO;
import org.example.fruitpickingrobt.dto.chassis.MotionCommandDTO;
import org.example.fruitpickingrobt.entity.Result;
import org.example.fruitpickingrobt.entity.chassis.ChassisTelemetry;
import org.example.fruitpickingrobt.service.chassis.ChassisTelemetryService;
import org.example.fruitpickingrobt.service.control.ChassisCommandService;
import org.example.fruitpickingrobt.store.ChassisStateCache;
import org.example.fruitpickingrobt.utils.BaseContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 底盘 REST 接口。
 *
 * 定位：WebSocket 是主链路（低延迟），REST 是**补充**，用于
 *   - 页面首次加载拉历史/最新值（WS 推送还没开始时）
 *   - 自动化脚本、定时任务调用
 *   - 调试与验收（尤其是 /stats 频率统计接口）
 *
 * 鉴权：本类路径需在 application.yml 的 security.ignore-paths 里加 /api/chassis/**
 *      （与现有 /api/devices/** 一样免登录，方便模拟器调试）；
 *      若要求控制指令必须登录，则只放行 GET 类查询接口。
 */
@RestController
@RequestMapping("/api/chassis")
@Slf4j
public class ChassisController {
    private final ChassisTelemetryService telemetryService;
    private final ChassisCommandService commandService;
    private final ChassisTelemetryDAO dao;
    private final ChassisCommandDAO commandDao;
    private final ChassisStateCache cache;

    @Value("${app.chassis.history-max-limit:2000}")
    private int maxLimit;

    /** 服务端实际推送间隔（回显给 /stats，方便确认配置是否生效） */
    @Value("${app.chassis.push-interval-ms:50}")
    private long pushIntervalMs;

    /** 落库采样率（回显给 /stats） */
    @Value("${app.chassis.db-sample-ratio:10}")
    private int dbSampleRatio;

    public ChassisController(ChassisTelemetryService telemetryService,
                             ChassisCommandService commandService,
                             ChassisTelemetryDAO dao,
                             ChassisCommandDAO commandDao,
                             ChassisStateCache cache) {
        this.telemetryService = telemetryService;
        this.commandService = commandService;
        this.dao = dao;
        this.commandDao = commandDao;
        this.cache = cache;
    }

    /**
     * 最新一帧全参数（含速度、四轮、BMS、经纬度）。
     * 从 Redis 读，微秒级返回。前端首屏调用，之后靠 WS 推送。
     */
    @GetMapping("/{deviceId}/latest")
    public Result<?> latest(@PathVariable String deviceId) {
        return cache.readLatest(deviceId)
                .<Result<?>>map(Result::success)
                .orElseGet(() -> Result.failure("暂无数据，设备可能离线"));
    }

    /**
     * 历史数据（画轨迹 / 回放）。走 MySQL。
     * 例：GET /api/chassis/CAR-01/history?minutes=30&limit=500
     */
    @GetMapping("/{deviceId}/history")
    public Result<?> history(@PathVariable String deviceId,
                             @RequestParam(defaultValue = "30") int minutes,
                             @RequestParam(defaultValue = "500") int limit) {
        int realLimit = Math.min(Math.max(limit, 1), maxLimit);
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusMinutes(minutes);
        List<ChassisTelemetry> list = dao.selectRange(deviceId, from, to, realLimit);
        return Result.success(list);
    }

    /**
     * ★ 频率统计接口：调试"刷新频率"和"指令响应速度"全靠它。
     *
     * 返回：
     *   recvHz         实测上行频率（车载实际上报多快）
     *   lostFrames     丢帧数（判断网络/车载是否跟得上）
     *   lastFrameAgeMs 最后一帧距今毫秒（持续变大 = 断流）
     *   bufferSize     待落库积压（持续变大 = 数据库写不过来，要调大采样率）
     *   pushHz         服务端实际推送频率（配置值的真实落地情况）
     */
    @GetMapping("/{deviceId}/stats")
    public Result<?> stats(@PathVariable String deviceId) {
        ObjectNode n = telemetryService.getStats(deviceId);
        n.put("pushIntervalMs", pushIntervalMs);
        n.put("pushHz", pushIntervalMs > 0 ? Math.round(1000.0 / pushIntervalMs * 10) / 10.0 : 0);
        n.put("dbSampleRatio", dbSampleRatio);
        return Result.success(n);
    }

    /** 当前目标速度回显（前端可据此显示"正在前进 0.5m/s"） */
    @GetMapping("/{deviceId}/target")
    public Result<?> target(@PathVariable String deviceId) {
        return cache.readTarget(deviceId)
                .<Result<?>>map(Result::success)
                .orElseGet(() -> Result.failure("当前无目标速度"));
    }

    /**
     * REST 方式下发运动指令（兜底/脚本用）。
     * 注意：前端按住前进请走 WebSocket（/ws/frontend 的 motion 消息），
     *      HTTP 每 50ms 一次请求开销太大，只在脚本或降级时用这个。
     */
    @PostMapping("/{deviceId}/motion")
    public Result<?> motion(@PathVariable String deviceId, @RequestBody MotionCommandDTO dto) {
        commandService.forwardMotion(deviceId,
                dto.getLinear() == null ? 0.0 : dto.getLinear(),
                dto.getAngular() == null ? 0.0 : dto.getAngular(), "REST");
        return Result.success("目标速度已更新");
    }

    /** 使能：上电后必须先调用，否则底盘不响应任何代码指令 */
    @PostMapping("/{deviceId}/enable")
    public Result<?> enable(@PathVariable String deviceId) {
        return Result.success(commandService.sendAction(
                deviceId, "enable", null, BaseContext.getCurrentUserId(), "REST"));
    }

    /** 清错：急停按钮释放后必须调用，否则底盘一直锁在错误状态 */
    @PostMapping("/{deviceId}/clear-error")
    public Result<?> clearError(@PathVariable String deviceId) {
        return Result.success(commandService.sendAction(
                deviceId, "clear_error", null, BaseContext.getCurrentUserId(), "REST"));
    }

    /** 停止（速度归零，保持使能） */
    @PostMapping("/{deviceId}/stop")
    public Result<?> stop(@PathVariable String deviceId) {
        return Result.success(commandService.sendAction(
                deviceId, "stop", null, BaseContext.getCurrentUserId(), "REST"));
    }

    /** 急停 */
    @PostMapping("/{deviceId}/emergency-stop")
    public Result<?> emergencyStop(@PathVariable String deviceId) {
        return Result.success(commandService.sendAction(
                deviceId, "emergency_stop", null, BaseContext.getCurrentUserId(), "REST"));
    }

    /** 指令历史（动作级：使能/清错/急停/停止） */
    @GetMapping("/{deviceId}/commands")
    public Result<?> commands(@PathVariable String deviceId,
                              @RequestParam(defaultValue = "50") int limit) {
        return Result.success(commandDao.selectByDevice(deviceId, Math.min(limit, 200)));
    }
}

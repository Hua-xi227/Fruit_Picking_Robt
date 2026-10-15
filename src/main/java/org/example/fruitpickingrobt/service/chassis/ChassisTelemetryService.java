package org.example.fruitpickingrobt.service.chassis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.example.fruitpickingrobt.dao.chassis.ChassisTelemetryDAO;
import org.example.fruitpickingrobt.dto.chassis.ChassisTelemetryDTO;
import org.example.fruitpickingrobt.entity.chassis.ChassisTelemetry;
import org.example.fruitpickingrobt.store.ChassisStateCache;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 底盘遥测核心服务：接收 → 更新最新态 → 攒批落库 → 统计频率。
 *   车载 50Hz 上报 = 每秒 50 帧。如果每帧都单条 INSERT：
 *     - 每秒 50 次数据库往返，HikariPool(最大10连接) 很快排队
 *     - 磁盘 I/O 被打满，连登录接口都会变慢
 *     - 一天 432 万行，几周就撑爆磁盘
 *
 *   本类的做法（三层解耦）：
 *     ① 每帧 -> 更新内存 liveSnapshot（供 20Hz 推送线程读，零成本）
 *     ② 每帧 -> 更新 Redis 最新态（O(1)，保证 /latest 永远是最新的）
 *     ③ 每帧 -> 按采样率入队；每 1000ms 由 @Scheduled 统一批量 INSERT
 *
 *   结果：数据库每秒只写 1 次，但前端看到的永远是最新数据（来自 ①②）。
 *         历史数据略微"稀疏"（10Hz），但画轨迹、做分析完全够用。
 */
@Service
@Slf4j
public class ChassisTelemetryService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /**
     * 内存中的最新一帧快照。
     * 推送线程（20Hz）直接从这里读，不用每 50ms 去查 Redis，省一次网络往返。
     */
    private final Map<String, ObjectNode> liveSnapshot = new ConcurrentHashMap<>();

    /** 待落库缓冲队列。ConcurrentLinkedQueue 无界但由采样率控制实际入队速度 */
    private final Queue<ChassisTelemetry> buffer = new ConcurrentLinkedQueue<>();

    /** 每个设备的频率统计器 */
    private final Map<String, RateMeter> meters = new ConcurrentHashMap<>();

    private final ChassisTelemetryDAO dao;
    private final ChassisStateCache cache;
    private final ObjectMapper objectMapper;

    /** 采样落库：每 N 帧存 1 帧。10 = 50Hz 上报时按 10Hz 落库 */
    @Value("${app.chassis.db-sample-ratio:10}")
    private int dbSampleRatio;

    /** 批量落库触发间隔（毫秒） */
    @Value("${app.chassis.flush-interval-ms:1000}")
    private long flushIntervalMs;

    /** 单批最大条数，防止一批过大触发 MySQL max_allowed_packet 限制 */
    @Value("${app.chassis.max-batch-size:200}")
    private int maxBatchSize;

    public ChassisTelemetryService(ChassisTelemetryDAO dao,
                                   ChassisStateCache cache,
                                   ObjectMapper objectMapper) {
        this.dao = dao;
        this.cache = cache;
        this.objectMapper = objectMapper;
    }

    /**
     * 收到一帧车载遥测。这是数据入口，被 LocationWebSocketHandler 的 chassis 快速路径调用。
     *
     * 注意：这个方法必须"快"，因为它跑在 WebSocket 的 IO 线程上，
     *       任何耗时操作（数据库、网络）都会拖慢整条链路。
     *       所以这里只做内存操作和一次 Redis set，真正的写库交给 flushBuffer()。
     */
    public void onTelemetry(ChassisTelemetryDTO dto) {
        if (dto.getDeviceId() == null || dto.getDeviceId().isBlank()) {
            return;
        }
        LocalDateTime serverTs = LocalDateTime.now(ZONE);
        LocalDateTime recordedAt = parseDeviceTs(dto.getDeviceTs());

        // ① 组装推送给前端的 JSON（原样保留车载字段，前端直接用）
        ObjectNode snapshot = objectMapper.valueToTree(dto);
        snapshot.put("serverTs", serverTs.toString());
        liveSnapshot.put(dto.getDeviceId(), snapshot);

        // ② 更新 Redis 最新态（REST /latest 从这里读）
        cache.saveLatest(dto.getDeviceId(), snapshot);

        // ③ 按采样率入队
        RateMeter meter = meters.computeIfAbsent(dto.getDeviceId(), k -> new RateMeter());
        meter.tick(dto.getSeq());
        if (meter.getTotal() % Math.max(1, dbSampleRatio) == 0) {
            buffer.add(toEntity(dto, serverTs, recordedAt));
        }
    }

    /**
     * 定时批量落库。
     * fixedDelayString 表示"上次执行完再等 X 毫秒"，比 fixedRate 安全
     */
    @Scheduled(fixedDelayString = "${app.chassis.flush-interval-ms:1000}")
    public void flushBuffer() {
        if (buffer.isEmpty()) {
            return;
        }
        List<ChassisTelemetry> batch = new ArrayList<>(maxBatchSize);
        ChassisTelemetry item;
        while (batch.size() < maxBatchSize && (item = buffer.poll()) != null) {
            batch.add(item);
        }
        if (batch.isEmpty()) {
            return;
        }
        try {
            int rows = dao.insertBatch(batch);
            log.debug("[底盘落库] 批量写入 {} 帧", rows);
        } catch (Exception e) {
            // 写库失败不能静默吞掉：打日志 + 放回队列头，下一轮重试
            log.error("[底盘落库] 批量写入失败，{} 帧将重试: {}", batch.size(), e.getMessage());
            buffer.addAll(batch);
        }
    }

    /** 供推送线程读取最新快照（20Hz 调用） */
    public Map<String, ObjectNode> getLiveSnapshots() {
        return liveSnapshot;
    }

    public ObjectNode getLiveSnapshot(String deviceId) {
        return liveSnapshot.get(deviceId);
    }

    /** 设备是否还在上报数据（超过 5 秒没新帧认为已断流） */
    public boolean isStreaming(String deviceId) {
        RateMeter meter = meters.get(deviceId);
        return meter != null && meter.millisSinceLastFrame() < 5000;
    }

    /**
     * 频率统计：这是「调试刷新频率」的量化依据。
     * 返回实测的上行频率、丢包率、最后一帧距今多久。
     */
    public ObjectNode getStats(String deviceId) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("deviceId", deviceId);
        RateMeter meter = meters.get(deviceId);
        if (meter == null) {
            n.put("online", false);
            return n;
        }
        n.put("online", meter.millisSinceLastFrame() < 5000);
        // 实测上行频率：车载实际多久发一帧
        n.put("recvHz", Math.round(meter.currentHz() * 10) / 10.0);
        // 累计收到帧数
        n.put("totalFrames", meter.getTotal());
        // 丢包数：用 seq 的不连续次数估算（车载每帧 seq+1）
        n.put("lostFrames", meter.getLost());
        // 最后一帧距今毫秒数：持续变大说明断流了
        n.put("lastFrameAgeMs", meter.millisSinceLastFrame());
        // 待落库的缓冲积压量：持续变大说明数据库写不过来，需要调大采样率
        n.put("bufferSize", buffer.size());
        return n;
    }

    /** 设备离线时清理内存快照，避免推送线程一直推陈旧数据 */
    public void evict(String deviceId) {
        liveSnapshot.remove(deviceId);
        meters.remove(deviceId);
    }

    // ====================== 内部工具 ======================

    /** 把 DTO 转成实体。四个轮子按顺序取，不足 4 个时补 null，避免越界 */
    private ChassisTelemetry toEntity(ChassisTelemetryDTO dto,
                                      LocalDateTime serverTs, LocalDateTime recordedAt) {
        ChassisTelemetry e = new ChassisTelemetry();
        e.setDeviceId(dto.getDeviceId());
        e.setVehicleState(dto.getVehicleState());
        e.setControlMode(dto.getControlMode());
        e.setBatteryVoltage(dto.getBatteryVoltage());
        e.setErrorCode(dto.getErrorCode());
        e.setLinearVelocity(dto.getLinearVelocity());
        e.setAngularVelocity(dto.getAngularVelocity());
        e.setLateralVelocity(dto.getLateralVelocity());
        e.setSteeringAngle(dto.getSteeringAngle());
        e.setLatitude(dto.getLatitude());
        e.setLongitude(dto.getLongitude());
        e.setBearing(dto.getBearing());

        List<ChassisTelemetryDTO.MotorDTO> motors =
                dto.getMotors() == null ? List.of() : dto.getMotors();
        setMotor(e, 1, getOrNull(motors, 0));
        setMotor(e, 2, getOrNull(motors, 1));
        setMotor(e, 3, getOrNull(motors, 2));
        setMotor(e, 4, getOrNull(motors, 3));

        if (dto.getBms() != null) {
            e.setBatterySoc(dto.getBms().getSoc());
            e.setBatterySoh(dto.getBms().getSoh());
            e.setBmsVoltage(dto.getBms().getVoltage());
            e.setBmsCurrent(dto.getBms().getCurrent());
            e.setBmsTemperature(dto.getBms().getTemperature());
        }
        e.setSeq(dto.getSeq());
        e.setDeviceTs(recordedAt);
        e.setServerTs(serverTs);
        e.setRecordedAt(recordedAt == null ? serverTs : recordedAt);
        return e;
    }

    private static <T> T getOrNull(List<T> list, int i) {
        return i < list.size() ? list.get(i) : null;
    }

    /** 用反射太重，四个轮子直接 switch 赋值，可读性最好 */
    private void setMotor(ChassisTelemetry e, int index, ChassisTelemetryDTO.MotorDTO m) {
        if (m == null) {
            return;
        }
        switch (index) {
            case 1 -> { e.setMotor1Rpm(m.getRpm()); e.setMotor1Current(m.getCurrent());
                e.setMotor1Pulse(m.getPulse()); e.setMotor1DriverVoltage(m.getDriverVoltage());
                e.setMotor1DriverTemp(m.getDriverTemp()); e.setMotor1MotorTemp(m.getMotorTemp());
                e.setMotor1DriverState(m.getDriverState()); }
            case 2 -> { e.setMotor2Rpm(m.getRpm()); e.setMotor2Current(m.getCurrent());
                e.setMotor2Pulse(m.getPulse()); e.setMotor2DriverVoltage(m.getDriverVoltage());
                e.setMotor2DriverTemp(m.getDriverTemp()); e.setMotor2MotorTemp(m.getMotorTemp());
                e.setMotor2DriverState(m.getDriverState()); }
            case 3 -> { e.setMotor3Rpm(m.getRpm()); e.setMotor3Current(m.getCurrent());
                e.setMotor3Pulse(m.getPulse()); e.setMotor3DriverVoltage(m.getDriverVoltage());
                e.setMotor3DriverTemp(m.getDriverTemp()); e.setMotor3MotorTemp(m.getMotorTemp());
                e.setMotor3DriverState(m.getDriverState()); }
            case 4 -> { e.setMotor4Rpm(m.getRpm()); e.setMotor4Current(m.getCurrent());
                e.setMotor4Pulse(m.getPulse()); e.setMotor4DriverVoltage(m.getDriverVoltage());
                e.setMotor4DriverTemp(m.getDriverTemp()); e.setMotor4MotorTemp(m.getMotorTemp());
                e.setMotor4DriverState(m.getDriverState()); }
            default -> { }
        }
    }

    /** 解析车载时间戳。兼容 ISO-8601 字符串；解析失败就用服务端时间，不让一帧数据因此丢失 */
    private LocalDateTime parseDeviceTs(String ts) {
        if (ts == null || ts.isBlank()) {
            return LocalDateTime.now(ZONE);
        }
        try {
            return OffsetDateTime.parse(ts).atZoneSameInstant(ZONE).toLocalDateTime();
        } catch (Exception ignored) {
            try {
                return LocalDateTime.parse(ts);
            } catch (Exception e2) {
                return LocalDateTime.now(ZONE);
            }
        }
    }

    /**
     * 频率统计器：滑动窗口计数。
     *
     * 为什么不用简单的"总数/运行时长"：那样算出来的是平均值，
     * 小车刚断流的几秒钟内平均值还是很高，看不出问题。
     * 滑动窗口只统计最近 1 秒，断流后 recvHz 会立刻掉到 0，一眼就能发现。
     */
    public static class RateMeter {
        private final AtomicLong total = new AtomicLong();
        private final AtomicLong windowCount = new AtomicLong();
        private volatile long windowStart = System.currentTimeMillis();
        private volatile double hz = 0.0;
        private volatile long lastFrameAt = System.currentTimeMillis();
        private volatile long lost = 0;
        private volatile Long lastSeq = null;

        public synchronized void tick(Long seq) {
            long now = System.currentTimeMillis();
            total.incrementAndGet();
            windowCount.incrementAndGet();
            lastFrameAt = now;

            // 用 seq 判断是否丢帧（车载每帧 +1，若跳跃说明中间丢了）
            if (seq != null) {
                if (lastSeq != null && seq > lastSeq + 1) {
                    lost += (seq - lastSeq - 1);
                }
                lastSeq = seq;
            }

            // 每满 1 秒结算一次频率，然后重置窗口
            long elapsed = now - windowStart;
            if (elapsed >= 1000) {
                hz = windowCount.get() * 1000.0 / elapsed;
                windowCount.set(0);
                windowStart = now;
            }
        }

        public double currentHz() { return hz; }
        public long getTotal() { return total.get(); }
        public long getLost() { return lost; }
        public long millisSinceLastFrame() { return System.currentTimeMillis() - lastFrameAt; }
    }
}

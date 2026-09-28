package org.example.fruitpickingrobt.service.control;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.example.fruitpickingrobt.dao.command.CommandLogDAO;
import org.example.fruitpickingrobt.entity.command.CommandLog;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ACK 回执处理 + 指令超时检测。
 *
 * 设备回执协议（经 /ws/location 上行，由 LocationWebSocketHandler 路由到这里）：
 *   收到指令： { "type": "ack",    "commandId": 123, "code": 0, "message": "received" }
 *   执行完成： { "type": "result", "commandId": 123, "success": true }
 *
 * 状态流转：
 *   PENDING --ack(code=0)--> ACKED --result(success)--> DONE
 *   PENDING --ack(code!=0)-> FAILED
 *   PENDING --超过 timeout--> TIMEOUT（由 @Scheduled 扫描 pending 缓存触发）
 */
@Component
@Slf4j
public class CommandAckHandler {

    private final CommandLogDAO commandLogDAO;

    /** commandId -> 等待中的指令（含超时截止时间） */
    private final ConcurrentHashMap<Long, PendingCommand> pending = new ConcurrentHashMap<>();

    public CommandAckHandler(CommandLogDAO commandLogDAO) {
        this.commandLogDAO = commandLogDAO;
    }

    /** 登记一条等待 ACK 的指令 */
    public void register(Long commandId, String deviceId, int timeoutSeconds) {
        pending.put(commandId, new PendingCommand(deviceId,
                LocalDateTime.now().plusSeconds(timeoutSeconds)));
    }

    public void cancel(Long commandId) {
        pending.remove(commandId);
    }

    /** 小车收到指令后的回执 */
    public void onAck(JsonNode node) {
        if (!node.has("commandId")) {
            log.warn("收到 ACK 但缺少 commandId，忽略: {}", node);
            return;
        }
        long commandId = node.get("commandId").asLong();
        int code = node.path("code").asInt(0);
        String message = node.path("message").asText("");

        PendingCommand pc = pending.remove(commandId);
        if (pc == null) {
            log.warn("收到未知或重复 ACK，commandId={}（可能已超时或已处理）", commandId);
            return;
        }

        String status = (code == 0) ? CommandLog.STATUS_ACKED : CommandLog.STATUS_FAILED;
        commandLogDAO.markAck(commandId, status, LocalDateTime.now(), code, message);
        log.info("[指令ACK] commandId={}, code={}, status={}", commandId, code, status);
    }

    /** 小车执行完成后的结果上报 */
    public void onResult(JsonNode node) {
        if (!node.has("commandId")) {
            log.warn("收到 RESULT 但缺少 commandId，忽略: {}", node);
            return;
        }
        long commandId = node.get("commandId").asLong();
        boolean success = node.path("success").asBoolean(true);

        pending.remove(commandId);
        String status = success ? CommandLog.STATUS_DONE : CommandLog.STATUS_FAILED;
        commandLogDAO.markDone(commandId, status, LocalDateTime.now());
        log.info("[指令完成] commandId={}, success={}, status={}", commandId, success, status);
    }

    /**
     * 超时扫描：每 500ms 检查一次 pending 缓存。
     * 用 fixedDelay 而非 fixedRate，避免上一次未执行完就重叠触发。
     */
    @Scheduled(fixedDelay = 500)
    public void scanTimeout() {
        LocalDateTime now = LocalDateTime.now();
        for (var entry : pending.entrySet()) {
            Long commandId = entry.getKey();
            PendingCommand pc = entry.getValue();
            if (now.isAfter(pc.deadline())) {
                pending.remove(commandId);
                commandLogDAO.markStatus(commandId, CommandLog.STATUS_TIMEOUT, "指令超时未收到 ACK");
                log.warn("[指令超时] commandId={}, deviceId={}", commandId, pc.deviceId());
            }
        }
    }

    /** pending 缓存中的一条记录 */
    private record PendingCommand(String deviceId, LocalDateTime deadline) {
    }
}

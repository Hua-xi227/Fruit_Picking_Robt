package org.example.fruitpickingrobt.service.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.example.fruitpickingrobt.dao.command.CommandLogDAO;
import org.example.fruitpickingrobt.dto.command.CommandRequest;
import org.example.fruitpickingrobt.dto.command.CommandResult;
import org.example.fruitpickingrobt.entity.command.CommandLog;
import org.example.fruitpickingrobt.websocket.DeviceSessionRegistry;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 下行控制：把 Web 端下发的指令写入 command_logs，并原路转发给小车。
 *
 * 关键流程：
 *   1) 校验指令类型 & 设备是否在线
 *   2) 落库 command_logs（status=PENDING，回填 id）
 *   3) 通过 DeviceSessionRegistry 取出该设备的 WebSocketSession 发送 JSON
 *   4) 把指令登记到 CommandAckHandler 的 pending 缓存，等待 ACK / 超时
 */
@Service
@Slf4j
public class CommandService {

    /** 服务端支持的指令白名单（防止任意字符串下发给小车） */
    private static final Set<String> ALLOWED_COMMANDS = Set.of(
            "start_pick", "stop", "pause", "resume", "go_home", "emergency_stop");

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private final CommandLogDAO commandLogDAO;
    private final DeviceSessionRegistry registry;
    private final CommandAckHandler ackHandler;
    private final ObjectMapper objectMapper;

    public CommandService(CommandLogDAO commandLogDAO,
                          DeviceSessionRegistry registry,
                          CommandAckHandler ackHandler,
                          ObjectMapper objectMapper) {
        this.commandLogDAO = commandLogDAO;
        this.registry = registry;
        this.ackHandler = ackHandler;
        this.objectMapper = objectMapper;
    }

    /**
     * 下发控制指令。
     *
     * @param req        请求体 {deviceId, command, params, timeoutSeconds}
     * @param operatorId 操作者用户ID（来自 JWT，可能为 null）
     * @return 下发结果（含 commandId 供前端轮询）
     */
    public CommandResult sendCommand(CommandRequest req, Long operatorId) {
        // 1) 基础校验
        if (req.getDeviceId() == null || req.getDeviceId().isBlank()) {
            return CommandResult.fail("deviceId 不能为空");
        }
        if (req.getCommand() == null || req.getCommand().isBlank()) {
            return CommandResult.fail("command 不能为空");
        }
        if (!ALLOWED_COMMANDS.contains(req.getCommand())) {
            return CommandResult.fail("不支持的指令类型: " + req.getCommand());
        }

        // 2) 设备在线检查（直连 ConcurrentHashMap，O(1)）
        if (!registry.isOnline(req.getDeviceId())) {
            return CommandResult.fail("设备不在线: " + req.getDeviceId());
        }

        // 3) 组装日志并落库（PENDING）
        CommandLog cmdLog = new CommandLog();
        cmdLog.setDeviceId(req.getDeviceId());
        cmdLog.setCommand(req.getCommand());
        try {
            cmdLog.setParamsJson(req.getParams() == null ? "{}"
                    : objectMapper.writeValueAsString(req.getParams()));
        } catch (Exception e) {
            cmdLog.setParamsJson("{}");
        }
        cmdLog.setStatus(CommandLog.STATUS_PENDING);
        cmdLog.setSentAt(LocalDateTime.now());
        cmdLog.setTimeoutSeconds(req.getTimeoutSeconds() == null
                ? DEFAULT_TIMEOUT_SECONDS : req.getTimeoutSeconds());
        cmdLog.setOperatorId(operatorId);
        commandLogDAO.insert(cmdLog);
        Long commandId = cmdLog.getId();

        // 4) 组装下发给小车的 JSON（务必带上 commandId 以便回执关联）
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "command");
        payload.put("commandId", commandId);
        payload.put("command", req.getCommand());
        if (req.getParams() != null) {
            payload.set("params", objectMapper.valueToTree(req.getParams()));
        }
        payload.put("sentAt", cmdLog.getSentAt().toString());

        // 5) 通过设备会话转发（catch IOException，连接可能中途断开）
        WebSocketSession session = registry.getSession(req.getDeviceId());
        if (session == null || !session.isOpen()) {
            commandLogDAO.markStatus(commandId, CommandLog.STATUS_FAILED, "设备会话已断开");
            return CommandResult.fail("设备会话已断开: " + req.getDeviceId());
        }
        try {
            session.sendMessage(new TextMessage(payload.toString()));
            log.info("[指令下发] commandId={}, deviceId={}, command={}",
                    commandId, req.getDeviceId(), req.getCommand());
        } catch (IOException e) {
            commandLogDAO.markStatus(commandId, CommandLog.STATUS_FAILED,
                    "WebSocket 发送失败: " + e.getMessage());
            log.error("[指令下发失败] commandId={}, error={}", commandId, e.getMessage());
            return CommandResult.fail("WebSocket 发送失败: " + e.getMessage());
        }

        // 6) 登记到 pending 缓存，交由 @Scheduled 做 ACK 超时检测
        ackHandler.register(commandId, req.getDeviceId(), cmdLog.getTimeoutSeconds());

        // 7) 返回（前端可凭 commandId 轮询状态）
        return CommandResult.ok(commandId, req.getDeviceId(), req.getCommand(),
                CommandLog.STATUS_PENDING, "指令已下发，等待设备确认");
    }

    /** 按 commandId 查询指令日志 */
    public CommandLog getCommand(Long commandId) {
        return commandLogDAO.selectById(commandId);
    }

    /** 查询设备指令历史 */
    public List<CommandLog> getHistory(String deviceId, int limit) {
        return commandLogDAO.selectByDevice(deviceId, limit);
    }
}

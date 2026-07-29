package org.example.fruitpickingrobt.controller;

import org.example.fruitpickingrobt.dto.command.CommandRequest;
import org.example.fruitpickingrobt.dto.command.CommandResult;
import org.example.fruitpickingrobt.entity.Result;
import org.example.fruitpickingrobt.entity.command.CommandLog;
import org.example.fruitpickingrobt.service.control.CommandService;
import org.example.fruitpickingrobt.utils.BaseContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 下行控制 REST 接口。
 *
 * 注意：本接口不在 security.ignore-paths 内，需要携带 JWT（控制指令必须鉴权）。
 *       若希望测试工具免登录调用，可在 application.yml 的 ignore-paths 增加 /api/control。
 *
 *   POST /api/control        下发指令，body: {deviceId, command, params?, timeoutSeconds?}
 *   GET  /api/control/{id}   查询某条指令状态（用于轮询 ACK 结果）
 */
@RestController
@RequestMapping("/api/control")
public class CommandController {

    private final CommandService commandService;

    public CommandController(CommandService commandService) {
        this.commandService = commandService;
    }

    @PostMapping
    public Result<?> send(@RequestBody CommandRequest req) {
        Long operatorId = BaseContext.getCurrentUserId();
        CommandResult result = commandService.sendCommand(req, operatorId);
        return result.isSuccess()
                ? Result.success(result)
                : Result.failure(result.getMessage());
    }

    @GetMapping("/{commandId}")
    public Result<?> status(@PathVariable Long commandId) {
        CommandLog log = commandService.getCommand(commandId);
        if (log == null) {
            return Result.failure("指令不存在: " + commandId);
        }
        return Result.success(log);
    }
}

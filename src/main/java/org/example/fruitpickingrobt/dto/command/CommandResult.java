package org.example.fruitpickingrobt.dto.command;

import lombok.Data;

/**
 * 下发指令的统一返回结构。
 */
@Data
public class CommandResult {
    private boolean success;
    private Long commandId;
    private String deviceId;
    private String command;
    private String status;
    private String message;

    public static CommandResult ok(Long commandId, String deviceId, String command,
                                   String status, String message) {
        CommandResult r = new CommandResult();
        r.setSuccess(true);
        r.setCommandId(commandId);
        r.setDeviceId(deviceId);
        r.setCommand(command);
        r.setStatus(status);
        r.setMessage(message);
        return r;
    }

    public static CommandResult fail(String message) {
        CommandResult r = new CommandResult();
        r.setSuccess(false);
        r.setMessage(message);
        return r;
    }
}

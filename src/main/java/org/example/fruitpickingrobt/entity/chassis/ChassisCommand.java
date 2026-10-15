package org.example.fruitpickingrobt.entity.chassis;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ChassisCommand {
    public static final String RESULT_PENDING = "PENDING";
    public static final String RESULT_OK      = "OK";
    public static final String RESULT_FAILED  = "FAILED";
    public static final String RESULT_OFFLINE = "OFFLINE";

    private Long id;
    private String deviceId;
    private String action;        // enable / clear_error / move / stop / emergency_stop / set_light
    private Double linear;        // 仅move有值
    private Double angular;       // 仅move有值
    private String paramsJson;
    /** 下发来源：REST（HTTP 接口）/ WS（WebSocket） */
    private String source;
    private Long operatorId;
    private LocalDateTime sentAt;
    private String result;
    private String message;



}

package org.example.fruitpickingrobt.dto.chassis;

import lombok.Data;
import java.util.List;

/**
 * 车载上报的底盘遥测报文
 *
 * 报文示例：
 * {
 *   "type":"chassis","deviceId":"CAR-01","seq":1024,"deviceTs":"2026-10-04T00:00:00.123+08:00",
 *   "vehicleState":1,"controlMode":2,"batteryVoltage":26.4,"errorCode":0,
 *   "linearVelocity":0.5,"angularVelocity":0.0,"lateralVelocity":0,"steeringAngle":0,
 *   "latitude":31.2304,"longitude":121.4737,"bearing":45.0,
 *   "motors":[{"rpm":120,"current":3.2,"pulse":98123,"driverVoltage":26.3,
 *              "driverTemp":38.5,"motorTemp":42.1,"driverState":64}, ...共4个],
 *   "bms":{"soc":87,"soh":98,"voltage":26.4,"current":-5.2,"temperature":31.0}
 * }
 *
 * 注意：motors 必须按 [左前, 右前, 左后, 右后] 的顺序，与底盘电机编号一致，
 *       否则前端显示"1号轮"时会对不上实际物理位置。
 */
@Data
public class ChassisTelemetryDTO {
    private String deviceId;
    private Long seq;           //帧序号，车载端每发一帧 +1；服务端用它判断丢包
    private String deviceTs;    //车载端时间戳（ISO-8601 字符串，带时区）

    private Integer vehicleState;
    private Integer controlMode;
    private Double batteryVoltage;
    private Integer errorCode;

    private Double linearVelocity;
    private Double angularVelocity;
    private Double lateralVelocity;
    private Double steeringAngle;

    private Double latitude;
    private Double longitude;
    private Double bearing;

    /** 四个轮子的状态，顺序：左前 / 右前 / 左后 / 右后 */
    private List<MotorDTO> motors;
    private BmsDTO bms;

    /** 单个电机的状态（HS 高频 + LS 低频合并） */
    @Data
    public static class MotorDTO {
        private Integer rpm;            // 转速
        private Double current;         // 电流 A
        private Long pulse;             // 编码器累计脉冲（V2 协议才有，V1 为 null）
        private Double driverVoltage;   // 驱动器电压 V（V2 才有）
        private Double driverTemp;      // 驱动器温度 ℃
        private Double motorTemp;       // 电机温度 ℃
        private Integer driverState;    // 驱动器状态位掩码（V2 才有）
    }

    /** BMS 电池状态。Scout Mini 没有 BMS，整块可能为 null */
    @Data
    public static class BmsDTO {
        private Integer soc;            // 电量 %
        private Integer soh;            // 健康度 %
        private Double voltage;
        private Double current;         // 正=充电 负=放电
        private Double temperature;
    }
}

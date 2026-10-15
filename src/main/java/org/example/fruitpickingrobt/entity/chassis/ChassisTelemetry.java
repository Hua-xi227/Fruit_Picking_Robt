package org.example.fruitpickingrobt.entity.chassis;
import lombok.Data;
import java.time.LocalDateTime;
/**
 * 底盘遥测实体：与 robot_chassis_telemetry 表一一对应。
 * 字段来源：
 *   ScoutCoreState.system_state   -> vehicleState / controlMode / batteryVoltage / errorCode
 *   ScoutCoreState.motion_state   -> linearVelocity / angularVelocity / lateralVelocity / steeringAngle
 *   ScoutActuatorState.hs_state   -> motorN Rpm / Current / Pulse
 *   ScoutActuatorState.ls_state   -> motorN DriverVoltage / DriverTemp / MotorTemp / DriverState
 *   ScoutCommonSensorState.bms    -> batterySoc / batterySoh / bmsVoltage / bmsCurrent / bmsTemperature
 *   导航模块（非底盘原生）         -> latitude / longitude / bearing
 *
 * 单位约定：全部使用 SI 单位（m/s、rad/s、V、A、℃），与 ugv_sdk 保持一致，
 *          前端不需要做任何换算，避免单位混乱导致速度显示差 1000 倍这类问题。
 */

@Data
public class ChassisTelemetry {
    private Long id;
    private String deviceId;

    private Integer vehicleState;   //系统状态（CAN 0x211）   车辆状态：0待机 1正常 2急停 3故障。 前端可用它显示"急停已触发"红色告警
    private Integer controlMode;    //控制模式：0待机 1遥控 2指令控制。只有=2时我们的代码才控制得了小车
    private Double batteryVoltage;  //电池电压

    /**
     * 故障码位掩码
     * 0x0001 电池故障  0x0002 电池告警   0x0004 遥控信号丢失
     * 0x0080 转向编码器故障   0x0100 电机驱动故障   0x0200 高层通讯故障
     */
    private Integer errorCode;

    //运动状态（CAN 0x221）
    private Double linearVelocity;  //线速度 m/s，正=前进 负=后退（SCOUT 2.0 最大 1.67）
    private Double angularVelocity; //角速度 rad/s，正=逆时针 负=顺时针
    private Double lateralVelocity; //横向速度 m/s（差速底盘恒为 0）
    private Double steeringAngle;   //转向角 rad（差速底盘无效）

    //导航位姿（由小车导航模块上传后合并进来）
    private Double latitude;
    private Double longitude;
    private Double bearing;         //航向角 度，0=正北，顺时针增大。画车头朝向图标用

    //四轮电机（HS 高频帧 0x251 + LS 低频帧 0x261）
    /**
     * 展开成4组,是因为MyBatis无法直接映射数组到列。
     * 展开后查询和批量INSERT都最简单，且便于单独索引某个轮子。
     */
    private Integer motor1Rpm;      private Double motor1Current;      private Long motor1Pulse;
    private Double motor1DriverVoltage; private Double motor1DriverTemp; private Double motor1MotorTemp;
    private Integer motor1DriverState;

    private Integer motor2Rpm;      private Double motor2Current;      private Long motor2Pulse;
    private Double motor2DriverVoltage; private Double motor2DriverTemp; private Double motor2MotorTemp;
    private Integer motor2DriverState;

    private Integer motor3Rpm;      private Double motor3Current;      private Long motor3Pulse;
    private Double motor3DriverVoltage; private Double motor3DriverTemp; private Double motor3MotorTemp;
    private Integer motor3DriverState;

    private Integer motor4Rpm;      private Double motor4Current;      private Long motor4Pulse;
    private Double motor4DriverVoltage; private Double motor4DriverTemp; private Double motor4MotorTemp;
    private Integer motor4DriverState;

    //BMS电池（CAN 0x361，2Hz 低频）
    private Integer batterySoc;     //电量百分比 0-100
    private Integer batterySoh;     //电池健康度 0-100
    private Double bmsVoltage;      //电池电压 V
    private Double bmsCurrent;      //电流 A，正=充电 负=放电
    private Double bmsTemperature;  //电池温度 ℃

    //频率与时延测量（调试刷新频率的核心数据）
    /** 车载端递增帧序号：服务端用它算丢包率和真实接收频率 */
    private Long seq;
    /** 车载端采集时间戳 */
    private LocalDateTime deviceTs;
    /** 服务端接收时间戳。serverTs - deviceTs = 上行单跳时延 */
    private LocalDateTime serverTs;
    /** 归一化业务时间，历史查询以此为准 */
    private LocalDateTime recordedAt;




















}

package org.example.fruitpickingrobt.dao.chassis;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.fruitpickingrobt.entity.chassis.ChassisTelemetry;

import java.time.LocalDateTime;
import java.util.List;


/**
 * 底盘遥测表的 MyBatis 访问接口
 *
 * 核心：insertBatch 一条 SQL 写入多帧。
 *   攒到 50 帧（约 1 秒）一次性 INSERT，每秒只有 1 次往返，性能差 50 倍。
 *   MyBatis 的 <foreach> 会把 List 展开成 "VALUES (...),(...),(...)"，是一条语句，不是 N 条。
 */

@Mapper
public interface ChassisTelemetryDAO {
    /**
     * 批量插入。注意：MySQL 单条 SQL 有 max_allowed_packet 限制，一批别超过 500 条
     */
    @Insert({"<script>",
            "INSERT INTO robot_chassis_telemetry",
            "  (device_id, vehicle_state, control_mode, battery_voltage, error_code,",
            "   linear_velocity, angular_velocity, lateral_velocity, steering_angle,",
            "   latitude, longitude, bearing,",
            "   motor1_rpm, motor1_current, motor1_pulse, motor1_driver_voltage, motor1_driver_temp, motor1_motor_temp, motor1_driver_state,",
            "   motor2_rpm, motor2_current, motor2_pulse, motor2_driver_voltage, motor2_driver_temp, motor2_motor_temp, motor2_driver_state,",
            "   motor3_rpm, motor3_current, motor3_pulse, motor3_driver_voltage, motor3_driver_temp, motor3_motor_temp, motor3_driver_state,",
            "   motor4_rpm, motor4_current, motor4_pulse, motor4_driver_voltage, motor4_driver_temp, motor4_motor_temp, motor4_driver_state,",
            "   battery_soc, battery_soh, bms_voltage, bms_current, bms_temperature,",
            "   seq, device_ts, server_ts, recorded_at)",
            "VALUES",
            "<foreach collection='list' item='t' separator=','>",
            "  (#{t.deviceId}, #{t.vehicleState}, #{t.controlMode}, #{t.batteryVoltage}, #{t.errorCode},",
            "   #{t.linearVelocity}, #{t.angularVelocity}, #{t.lateralVelocity}, #{t.steeringAngle},",
            "   #{t.latitude}, #{t.longitude}, #{t.bearing},",
            "   #{t.motor1Rpm}, #{t.motor1Current}, #{t.motor1Pulse}, #{t.motor1DriverVoltage}, #{t.motor1DriverTemp}, #{t.motor1MotorTemp}, #{t.motor1DriverState},",
            "   #{t.motor2Rpm}, #{t.motor2Current}, #{t.motor2Pulse}, #{t.motor2DriverVoltage}, #{t.motor2DriverTemp}, #{t.motor2MotorTemp}, #{t.motor2DriverState},",
            "   #{t.motor3Rpm}, #{t.motor3Current}, #{t.motor3Pulse}, #{t.motor3DriverVoltage}, #{t.motor3DriverTemp}, #{t.motor3MotorTemp}, #{t.motor3DriverState},",
            "   #{t.motor4Rpm}, #{t.motor4Current}, #{t.motor4Pulse}, #{t.motor4DriverVoltage}, #{t.motor4DriverTemp}, #{t.motor4MotorTemp}, #{t.motor4DriverState},",
            "   #{t.batterySoc}, #{t.batterySoh}, #{t.bmsVoltage}, #{t.bmsCurrent}, #{t.bmsTemperature},",
            "   #{t.seq}, #{t.deviceTs}, #{t.serverTs}, #{t.recordedAt})",
            "</foreach>",
            "</script>"})
    int insertBatch(@Param("list") List<ChassisTelemetry> list);

    /**
     * 按时间范围查历史（画轨迹、回放用）。带 limit 防止一次拉爆内存
     */
    @Select("SELECT * FROM robot_chassis_telemetry " +
            "WHERE device_id = #{deviceId} " +
            "  AND recorded_at BETWEEN #{from} AND #{to} " +
            "ORDER BY recorded_at ASC LIMIT #{limit}")
    List<ChassisTelemetry> selectRange(@Param("deviceId") String deviceId,
                                       @Param("from") LocalDateTime from,
                                       @Param("to") LocalDateTime to,
                                       @Param("limit") int limit);

    /**
     * 最近 N 条（倒序取回来再反转，保证按时间正序给前端）
     */
    @Select("SELECT * FROM (SELECT * FROM robot_chassis_telemetry " +
            "WHERE device_id = #{deviceId} ORDER BY id DESC LIMIT #{limit}) t " +
            "ORDER BY t.id ASC")
    List<ChassisTelemetry> selectLatest(@Param("deviceId") String deviceId,
                                        @Param("limit") int limit);

    /**
     * 统计某段时间内实际存了多少帧，用于核对采样率是否符合预期
     */
    @Select("SELECT COUNT(*) FROM robot_chassis_telemetry " +
            "WHERE device_id = #{deviceId} AND recorded_at BETWEEN #{from} AND #{to}")
    long countRange(@Param("deviceId") String deviceId,
                    @Param("from") LocalDateTime from,
                    @Param("to") LocalDateTime to);

    /**
     * 删除指定时间之前的数据（归档时用，配合 SQL 文件里的归档脚本）
     */
    @Select("DELETE FROM robot_chassis_telemetry WHERE recorded_at < #{before}")
    int deleteBefore(@Param("before") LocalDateTime before);
}

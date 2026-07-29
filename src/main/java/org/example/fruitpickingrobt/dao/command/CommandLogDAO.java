package org.example.fruitpickingrobt.dao.command;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.example.fruitpickingrobt.entity.command.CommandLog;

import java.time.LocalDateTime;
import java.util.List;

/**
 * command_logs 表的 MyBatis 访问接口（注解方式，与 LocationRecordDAO 风格一致）。
 *
 * 列名 snake_case 与实体字段 camelCase 的映射由全局
 * mybatis.configuration.map-underscore-to-camel-case=true 自动完成。
 */
@Mapper
public interface CommandLogDAO {

    /** 插入一条指令日志，并回填自增主键 id */
    @Insert("""
            INSERT INTO command_logs
                (device_id, command, params_json, status, sent_at, timeout_seconds, operator_id)
            VALUES
                (#{deviceId}, #{command}, #{paramsJson}, #{status}, #{sentAt}, #{timeoutSeconds}, #{operatorId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(CommandLog log);

    /** 设备回执 ACK 时更新状态、回执时间、回执码 */
    @Update("""
            UPDATE command_logs
            SET status = #{status},
                ack_at = #{ackAt},
                ack_code = #{ackCode},
                ack_message = #{ackMessage}
            WHERE id = #{id}
            """)
    int markAck(@Param("id") Long id,
                @Param("status") String status,
                @Param("ackAt") LocalDateTime ackAt,
                @Param("ackCode") Integer ackCode,
                @Param("ackMessage") String ackMessage);

    /** 设备上报执行结果（完成 / 失败）时更新最终状态 */
    @Update("""
            UPDATE command_logs
            SET status = #{status}, done_at = #{doneAt}
            WHERE id = #{id}
            """)
    int markDone(@Param("id") Long id,
                 @Param("status") String status,
                 @Param("doneAt") LocalDateTime doneAt);

    /** 超时等场景只更新状态（不触碰 ack 字段，仅记录原因） */
    @Update("""
            UPDATE command_logs
            SET status = #{status}, ack_message = #{ackMessage}
            WHERE id = #{id}
            """)
    int markStatus(@Param("id") Long id,
                   @Param("status") String status,
                   @Param("ackMessage") String ackMessage);

    /** 按主键查询单条指令 */
    @Select("""
            SELECT id, device_id, command, params_json, status,
                   sent_at, ack_at, done_at, ack_code, ack_message,
                   timeout_seconds, operator_id
            FROM command_logs
            WHERE id = #{id}
            """)
    CommandLog selectById(@Param("id") Long id);

    /** 查询某设备的指令历史（按下发时间倒序） */
    @Select("""
            SELECT id, device_id, command, params_json, status,
                   sent_at, ack_at, done_at, ack_code, ack_message,
                   timeout_seconds, operator_id
            FROM command_logs
            WHERE device_id = #{deviceId}
            ORDER BY sent_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<CommandLog> selectByDevice(@Param("deviceId") String deviceId,
                                    @Param("limit") int limit);
}

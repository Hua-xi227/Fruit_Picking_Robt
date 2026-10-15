package org.example.fruitpickingrobt.dao.chassis;

import org.apache.ibatis.annotations.*;
import org.example.fruitpickingrobt.entity.chassis.ChassisCommand;
import java.util.List;

/** 底盘动作指令表访问接口（低频写入，不需要批量） */
@Mapper
public interface ChassisCommandDAO {
    @Insert("INSERT INTO robot_chassis_command " +
            " (`device_id`, `action`, `linear`, `angular`, `params_json`, `source`, `operator_id`, `sent_at`, `result`) " +
            "VALUES (#{deviceId}, #{action}, #{linear}, #{angular}, #{paramsJson}, " +
            "        #{source}, #{operatorId}, #{sentAt}, #{result})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ChassisCommand cmd);

    @Update("UPDATE robot_chassis_command SET result = #{result}, message = #{message} WHERE id = #{id}")
    int markResult(@Param("id") Long id, @Param("result") String result, @Param("message") String message);

    @Select("SELECT * FROM robot_chassis_command WHERE device_id = #{deviceId} " +
            "ORDER BY sent_at DESC LIMIT #{limit}")
    List<ChassisCommand> selectByDevice(@Param("deviceId") String deviceId, @Param("limit") int limit);
}

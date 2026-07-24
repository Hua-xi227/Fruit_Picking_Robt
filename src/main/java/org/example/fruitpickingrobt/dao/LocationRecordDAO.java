package org.example.fruitpickingrobt.dao;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.example.fruitpickingrobt.entity.LocationRecord;

import java.util.List;

/** location_records 表的 MySQL 访问接口。 */
@Mapper
public interface LocationRecordDAO {
    @Insert("""
            INSERT INTO location_records
                (device_id, record_type, latitude, longitude, payload_json, recorded_at)
            VALUES
                (#{deviceId}, #{recordType}, #{latitude}, #{longitude}, #{payloadJson}, #{recordedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(LocationRecord record);

    @Select("""
            SELECT id, device_id, record_type, latitude, longitude,
                   payload_json, recorded_at, created_at
            FROM location_records
            WHERE device_id = #{deviceId} AND record_type = 'LOCATION'
            ORDER BY recorded_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<LocationRecord> selectLocationHistory(@Param("deviceId") String deviceId,
                                               @Param("limit") int limit);

    @Select("""
            SELECT id, device_id, record_type, latitude, longitude,
                   payload_json, recorded_at, created_at
            FROM location_records
            WHERE device_id = #{deviceId} AND record_type = #{recordType}
            ORDER BY recorded_at DESC, id DESC
            LIMIT 1
            """)
    LocationRecord selectLatestByType(@Param("deviceId") String deviceId,
                                      @Param("recordType") String recordType);
}

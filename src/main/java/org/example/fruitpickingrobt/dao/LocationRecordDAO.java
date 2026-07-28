package org.example.fruitpickingrobt.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.fruitpickingrobt.entity.LocationRecord;

import java.util.List;

@Mapper
public interface LocationRecordDAO {

    int insert(LocationRecord record);

    int insertRecord(LocationRecord record);

    List<LocationRecord> selectLatestRecords(int limit);

    LocationRecord selectLatestRecord();

    int deleteOldRecords(int keepCount);

    List<LocationRecord> selectLocationHistory(@Param("deviceId") String deviceId,
                                               @Param("limit") int limit);

    LocationRecord selectLatestByType(@Param("deviceId") String deviceId,
                                       @Param("type") String type);
}

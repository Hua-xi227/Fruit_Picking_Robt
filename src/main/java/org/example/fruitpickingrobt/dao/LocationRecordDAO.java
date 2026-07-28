package org.example.fruitpickingrobt.dao;

import org.apache.ibatis.annotations.Mapper;
import org.example.fruitpickingrobt.entity.LocationRecord;

import java.util.List;

@Mapper
public interface LocationRecordDAO {
    void insertRecord(LocationRecord record);

    List<LocationRecord> selectLatestRecords(int limit);

    LocationRecord selectLatestRecord();

    int deleteOldRecords(int keepCount);
}
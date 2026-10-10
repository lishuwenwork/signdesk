package com.signdesk.mapper;

import com.signdesk.domain.model.RequestStatusRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RequestStatusQueryMapper {
    List<RequestStatusRow> queryAll(
            @Param("shanghaiDate") String shanghaiDate, @Param("utcDate") String utcDate);
}

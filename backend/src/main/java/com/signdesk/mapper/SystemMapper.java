package com.signdesk.mapper;

import com.signdesk.domain.model.DashboardCounts;

import org.apache.ibatis.annotations.*;

@Mapper
public interface SystemMapper {
    DashboardCounts counts(@Param("localDate") String localDate, @Param("utcDate") String utcDate);
}

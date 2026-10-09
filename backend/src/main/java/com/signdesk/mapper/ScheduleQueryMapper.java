package com.signdesk.mapper;

import com.signdesk.domain.PlatformSchedule;
import com.signdesk.domain.model.ScheduleRow;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ScheduleQueryMapper {
    List<ScheduleRow> queryList();

    List<PlatformSchedule> enabledPlans();
}

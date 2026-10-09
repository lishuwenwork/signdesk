package com.signdesk.mapper;

import com.signdesk.domain.*;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface BackupMapper {
    List<RequestRevision> currentRevisions();

    List<DailyCompletion> completed();

    List<RequestDayState> pending();

    int clearPlatforms();
}

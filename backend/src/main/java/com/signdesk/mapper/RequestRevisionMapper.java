package com.signdesk.mapper;

import com.signdesk.domain.RequestRevision;

import org.apache.ibatis.annotations.*;

@Mapper
public interface RequestRevisionMapper {
    RequestRevision query(@Param("requestId") String requestId, @Param("revision") int revision);

    int insert(RequestRevision revision);
}

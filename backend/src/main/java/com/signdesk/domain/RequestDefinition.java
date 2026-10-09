package com.signdesk.domain;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("requests")
public class RequestDefinition {
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String accountId;
    private String name;
    private Integer enabled;
    private Integer currentRevision;
    private String rulesJson;
    private Integer authPaused;
    private Integer sortOrder;
    @Version private Integer version;
    private String safeHost;
    private String method;
}

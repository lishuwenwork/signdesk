package com.signdesk.domain;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("request_templates")
public class RequestTemplate {
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String platformId;
    private String name;
    private String rulesJson;
    @Version private Integer version;
}

package com.signdesk.domain;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("platforms")
public class Platform {
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String name;
    private String note;
    private Integer enabled;
    private Integer sortOrder;
    @Version private Integer version;
}

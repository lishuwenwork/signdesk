package com.signdesk.domain;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("accounts")
public class Account {
    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String platformId;
    private String alias;
    private Integer enabled;
    private Integer sortOrder;
    @Version private Integer version;
}

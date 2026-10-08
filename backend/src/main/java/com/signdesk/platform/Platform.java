package com.signdesk.platform;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("platforms")
public class Platform {
    @TableId public String id;
    public String name;
    public String note;
    public Integer enabled;
    public Integer sortOrder;
    public Integer version;
}

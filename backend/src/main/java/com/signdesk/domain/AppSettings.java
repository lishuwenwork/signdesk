package com.signdesk.domain;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("settings")
public class AppSettings {
    @TableId(type = IdType.INPUT)
    private Integer id;

    private Integer paused;
    private Integer concurrency;
    private Integer timeoutSeconds;
    private Integer retentionDays;
    @Version private Integer version;
    private String proxyMode;
    private String proxyHost;
    private Integer proxyPort;
}

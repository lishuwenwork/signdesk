package com.signdesk.domain.vo;

import com.signdesk.engine.ResultRules;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RequestTemplateVo {
    private String id;
    private String platformId;
    private String name;
    private ResultRules rules;
    private Integer version;
}

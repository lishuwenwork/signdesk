package com.signdesk.domain.vo;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AccountVo {
    private String id;
    private String platformId;
    private String alias;
    private Integer enabled;
    private Integer sortOrder;
    private Integer version;
    private java.util.List<RequestVo> requests;
}

package com.signdesk.domain.vo;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PlatformVo {
    private String id;
    private String name;
    private String note;
    private Integer enabled;
    private Integer sortOrder;
    private Integer version;
    private java.util.List<AccountVo> accounts;
}

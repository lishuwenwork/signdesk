package com.signdesk.domain;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName(value = "run_responses", autoResultMap = true)
public class RunResponse {
    @TableId(type = IdType.INPUT)
    private String runId;

    // SQLite supports getBytes/setBytes, not JDBC getBlob.
    @TableField(typeHandler = org.apache.ibatis.type.ByteArrayTypeHandler.class)
    private byte[] bodyBytes;

    private String contentType;
    private String charset;
    private String captureState;

    public byte[] getBodyBytes() {
        return bodyBytes == null ? null : bodyBytes.clone();
    }

    public void setBodyBytes(byte[] value) {
        bodyBytes = value == null ? null : value.clone();
    }
}

package com.signdesk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.signdesk.domain.AppSettings;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AppSettingsMapper extends BaseMapper<AppSettings> {}

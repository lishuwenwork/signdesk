package com.signdesk.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.signdesk.domain.Account;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AccountMapper extends BaseMapper<Account> {}

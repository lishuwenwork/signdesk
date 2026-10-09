package com.signdesk.service.impl;

import com.signdesk.common.*;
import com.signdesk.domain.Account;
import com.signdesk.domain.bo.AccountBo;
import com.signdesk.domain.bo.NewAccountBo;
import com.signdesk.mapper.*;
import com.signdesk.service.IAccountService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccountServiceImpl implements IAccountService {
    private final AccountMapper accounts;
    private final PlatformMapper platforms;
    private final CatalogGuard guard;

    @Override
    @Transactional
    public String insert(String platformId, NewAccountBo b) {
        Checks.found(platforms.selectById(platformId));
        var a = new Account();
        a.setPlatformId(platformId);
        a.setAlias(Checks.name(b.alias(), 40));
        a.setEnabled(b.enabled() ? 1 : 0);
        a.setSortOrder(0);
        a.setVersion(1);
        accounts.insert(a);
        return a.getId();
    }

    @Override
    @Transactional
    public void update(String id, AccountBo b) {
        var a = new Account();
        a.setId(id);
        a.setAlias(Checks.name(b.alias(), 40));
        a.setEnabled(b.enabled() ? 1 : 0);
        a.setVersion(b.version());
        Checks.changed(accounts.updateById(a));
    }

    @Override
    @Transactional
    public void delete(String id) {
        guard.account(id);
        if (accounts.deleteById(id) != 1) throw new ApiException(404, "对象不存在");
    }
}

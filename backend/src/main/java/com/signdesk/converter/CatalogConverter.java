package com.signdesk.converter;

import com.signdesk.common.Json;
import com.signdesk.domain.*;
import com.signdesk.domain.vo.*;
import com.signdesk.engine.ResultRules;

import java.util.List;

public final class CatalogConverter {
    private CatalogConverter() {}

    public static PlatformVo platform(Platform p, List<AccountVo> accounts) {
        var v = new PlatformVo();
        v.setId(p.getId());
        v.setName(p.getName());
        v.setNote(p.getNote());
        v.setEnabled(p.getEnabled());
        v.setSortOrder(p.getSortOrder());
        v.setVersion(p.getVersion());
        v.setAccounts(accounts);
        return v;
    }

    public static AccountVo account(Account a, List<RequestVo> requests) {
        var v = new AccountVo();
        v.setId(a.getId());
        v.setPlatformId(a.getPlatformId());
        v.setAlias(a.getAlias());
        v.setEnabled(a.getEnabled());
        v.setSortOrder(a.getSortOrder());
        v.setVersion(a.getVersion());
        v.setRequests(requests);
        return v;
    }

    public static RequestVo request(RequestDefinition r) {
        var v = new RequestVo();
        v.setId(r.getId());
        v.setAccountId(r.getAccountId());
        v.setName(r.getName());
        v.setEnabled(r.getEnabled());
        v.setCurrentRevision(r.getCurrentRevision());
        v.setRules(Json.read(r.getRulesJson(), ResultRules.class));
        v.setAuthPaused(r.getAuthPaused());
        v.setSortOrder(r.getSortOrder());
        v.setVersion(r.getVersion());
        v.setSafeHost(r.getSafeHost());
        v.setMethod(r.getMethod());
        return v;
    }

    public static RequestTemplateVo template(RequestTemplate t) {
        var v = new RequestTemplateVo();
        v.setId(t.getId());
        v.setPlatformId(t.getPlatformId());
        v.setName(t.getName());
        v.setRules(Json.read(t.getRulesJson(), ResultRules.class));
        v.setVersion(t.getVersion());
        return v;
    }
}

package com.signdesk.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.signdesk.common.ApiException;
import com.signdesk.common.Checks;
import com.signdesk.converter.*;
import com.signdesk.domain.*;
import com.signdesk.domain.model.RequestStatusRow;
import com.signdesk.domain.bo.PlatformBo;
import com.signdesk.domain.vo.*;
import com.signdesk.mapper.*;
import com.signdesk.service.IPlatformService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PlatformServiceImpl implements IPlatformService {
    private final PlatformMapper platforms;
    private final AccountMapper accounts;
    private final RequestDefinitionMapper requests;
    private final RequestStatusQueryMapper requestStatuses;
    private final PlatformScheduleMapper schedules;
    private final CatalogGuard guard;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public List<PlatformVo> queryList() {
        // Four batch queries, independent of tree size; no request/response payloads or summaries.
        var ps =
                platforms.selectList(
                        new LambdaQueryWrapper<Platform>()
                                .orderByAsc(Platform::getSortOrder, Platform::getId));
        var as =
                accounts.selectList(
                        new LambdaQueryWrapper<Account>()
                                .orderByAsc(Account::getSortOrder, Account::getId));
        var rs =
                requests.selectList(
                        new LambdaQueryWrapper<RequestDefinition>()
                                .orderByAsc(
                                        RequestDefinition::getSortOrder, RequestDefinition::getId));
        var now = clock.instant();
        var statuses =
                requestStatuses.queryAll(
                                now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString(),
                                now.atZone(ZoneOffset.UTC).toLocalDate().toString())
                        .stream()
                        .collect(Collectors.toMap(RequestStatusRow::getRequestId, s -> s));
        var byAccount =
                rs.stream()
                        .collect(
                                Collectors.groupingBy(
                                        RequestDefinition::getAccountId,
                                        Collectors.mapping(
                                                r -> {
                                                    var vo = CatalogConverter.request(r);
                                                    var status = statuses.get(r.getId());
                                                    if (status != null) {
                                                        vo.setTodayState(status.getTodayState());
                                                        vo.setLastRun(status.getLastRun());
                                                    }
                                                    return vo;
                                                }, Collectors.toList())));
        var byPlatform =
                as.stream()
                        .collect(
                                Collectors.groupingBy(
                                        Account::getPlatformId,
                                        Collectors.mapping(
                                                a ->
                                                        CatalogConverter.account(
                                                                a,
                                                                byAccount.getOrDefault(
                                                                        a.getId(), List.of())),
                                                Collectors.toList())));
        return ps.stream()
                .map(
                        p ->
                                CatalogConverter.platform(
                                        p, byPlatform.getOrDefault(p.getId(), List.of())))
                .toList();
    }

    private void validate(PlatformBo b) {
        Checks.name(b.getName(), 40);
        if (b.getNote() != null && b.getNote().length() > 500) throw new ApiException("备注最多500字符");
    }

    @Override
    @Transactional
    public String insert(PlatformBo b) {
        validate(b);
        var p = new Platform();
        p.setName(b.getName().trim());
        p.setNote(b.getNote() == null ? "" : b.getNote());
        p.setEnabled(b.isEnabled() ? 1 : 0);
        p.setSortOrder(0);
        p.setVersion(1);
        platforms.insert(p);
        schedules.insert(ScheduleConverter.initial(p.getId(), clock.instant().toString()));
        return p.getId();
    }

    @Override
    @Transactional
    public void update(String id, PlatformBo b) {
        validate(b);
        var p = new Platform();
        p.setId(id);
        p.setName(b.getName().trim());
        p.setNote(b.getNote() == null ? "" : b.getNote());
        p.setEnabled(b.isEnabled() ? 1 : 0);
        p.setVersion(b.getVersion());
        Checks.changed(platforms.updateById(p));
    }

    @Override
    @Transactional
    public void delete(String id) {
        guard.platform(id);
        if (platforms.deleteById(id) != 1) throw new ApiException(404, "对象不存在");
    }
}

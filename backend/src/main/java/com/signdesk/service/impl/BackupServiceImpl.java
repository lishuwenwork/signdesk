package com.signdesk.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.signdesk.common.*;
import com.signdesk.converter.ScheduleConverter;
import com.signdesk.domain.*;
import com.signdesk.domain.bo.*;
import com.signdesk.domain.vo.*;
import com.signdesk.engine.*;
import com.signdesk.mapper.*;
import com.signdesk.service.*;
import com.signdesk.storage.DatabaseInitializer;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BackupServiceImpl implements IBackupService {
    private final PlatformMapper platforms;
    private final AccountMapper accounts;
    private final RequestDefinitionMapper requests;
    private final RequestTemplateMapper templates;
    private final PlatformScheduleMapper schedules;
    private final RequestRevisionMapper revisions;
    private final AppSettingsMapper settings;
    private final BackupMapper backup;
    private final QueueMapper queue;
    private final ISettingsService settingService;
    private final IRunService runs;
    private final BackupValidator validator;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Override
    public BackupFile export(BackupExportBo input) {
        boolean include = Boolean.TRUE.equals(input.includeRequests());
        var file = transactions.execute(tx -> snapshot(include));
        validator.validate(file);
        return file;
    }

    private BackupFile snapshot(boolean include) {
        var current =
                include
                        ? backup.currentRevisions().stream()
                                .collect(
                                        Collectors.toMap(
                                                RequestRevision::getRequestId,
                                                RequestRevision::getRawCurl))
                        : Map.<String, String>of();
        var ps =
                platforms
                        .selectList(new LambdaQueryWrapper<Platform>().orderByAsc(Platform::getId))
                        .stream()
                        .map(
                                p ->
                                        new BackupFile.PlatformEntry(
                                                p.getId(),
                                                p.getName(),
                                                p.getNote(),
                                                Checks.flag(p.getEnabled()),
                                                p.getSortOrder()))
                        .toList();
        var as =
                accounts
                        .selectList(new LambdaQueryWrapper<Account>().orderByAsc(Account::getId))
                        .stream()
                        .map(
                                a ->
                                        new BackupFile.AccountEntry(
                                                a.getId(),
                                                a.getPlatformId(),
                                                a.getAlias(),
                                                Checks.flag(a.getEnabled()),
                                                a.getSortOrder()))
                        .toList();
        var rs =
                requests
                        .selectList(
                                new LambdaQueryWrapper<RequestDefinition>()
                                        .orderByAsc(RequestDefinition::getId))
                        .stream()
                        .map(
                                r ->
                                        new BackupFile.RequestEntry(
                                                r.getId(),
                                                r.getAccountId(),
                                                r.getName(),
                                                Checks.flag(r.getEnabled()),
                                                Checks.flag(r.getAuthPaused()),
                                                r.getSortOrder(),
                                                Json.read(r.getRulesJson(), ResultRules.class),
                                                current.get(r.getId())))
                        .toList();
        var ts =
                templates
                        .selectList(
                                new LambdaQueryWrapper<RequestTemplate>()
                                        .orderByAsc(RequestTemplate::getId))
                        .stream()
                        .map(
                                t ->
                                        new BackupFile.TemplateEntry(
                                                t.getId(),
                                                t.getPlatformId(),
                                                t.getName(),
                                                Json.read(t.getRulesJson(), ResultRules.class)))
                        .toList();
        var ss =
                schedules
                        .selectList(
                                new LambdaQueryWrapper<PlatformSchedule>()
                                        .orderByAsc(PlatformSchedule::getPlatformId))
                        .stream()
                        .map(
                                r -> {
                                    var s = ScheduleConverter.toVo(r);
                                    return new BackupFile.ScheduleEntry(
                                            r.getPlatformId(),
                                            new BackupFile.Schedule(
                                                    s.enabled(),
                                                    s.frequency(),
                                                    s.weekdays(),
                                                    s.times(),
                                                    s.timezone(),
                                                    s.intervalSeconds(),
                                                    s.catchupMinutes(),
                                                    s.skipCompletedDaily(),
                                                    s.revision()));
                                })
                        .toList();
        var completed =
                include
                        ? backup.completed().stream()
                                .map(
                                        c ->
                                                new BackupFile.DayMarker(
                                                        c.getRequestId(), c.getBusinessDate()))
                                .toList()
                        : List.<BackupFile.DayMarker>of();
        var pending =
                include
                        ? backup.pending().stream()
                                .map(
                                        c ->
                                                new BackupFile.DayMarker(
                                                        c.getRequestId(), c.getBusinessDate()))
                                .toList()
                        : List.<BackupFile.DayMarker>of();
        var v = settingService.query();
        var settingsEntry =
                new BackupFile.Settings(
                        v.paused(),
                        v.concurrency(),
                        v.timeoutSeconds(),
                        v.retentionDays(),
                        v.version(),
                        new BackupFile.Proxy(v.proxy().mode(), v.proxy().host(), v.proxy().port()));
        return new BackupFile(
                DatabaseInitializer.FORMAT,
                new BackupFile.Payload(
                        include, settingsEntry, ps, as, rs, ts, ss, completed, pending));
    }

    @Override
    public BackupPreviewVo preview(BackupPreviewBo input) {
        var s = validator.validate(input.backup()).file().payload();
        return new BackupPreviewVo(
                s.platforms().size(),
                s.accounts().size(),
                s.requests().size(),
                s.templates().size(),
                s.includesRequests(),
                "replace",
                "导入将替换所有现有平台、账号、请求、计划和执行记录；导入后保持全局定时暂停");
    }

    @Override
    public void restore(BackupImportBo input) {
        if (!Boolean.TRUE.equals(input.replace())) throw new ApiException("需要明确确认替换现有配置");
        var validated = validator.validate(input.backup());
        runs.beginMaintenance();
        try {
            transactions.executeWithoutResult(
                    tx -> {
                        var s = validated.file().payload();
                        String now = clock.instant().toString();
                        backup.clearPlatforms();
                        for (var p : s.platforms()) {
                            var r = new Platform();
                            r.setId(p.id());
                            r.setName(p.name());
                            r.setNote(p.note());
                            r.setEnabled(p.enabled() ? 1 : 0);
                            r.setSortOrder(p.sortOrder());
                            r.setVersion(1);
                            platforms.insert(r);
                        }
                        for (var a : s.accounts()) {
                            var r = new Account();
                            r.setId(a.id());
                            r.setPlatformId(a.platformId());
                            r.setAlias(a.alias());
                            r.setEnabled(a.enabled() ? 1 : 0);
                            r.setSortOrder(a.sortOrder());
                            r.setVersion(1);
                            accounts.insert(r);
                        }
                        for (var prepared : validated.requests()) {
                            var b = prepared.entry();
                            var spec = prepared.spec();
                            var r = new RequestDefinition();
                            r.setId(b.id());
                            r.setAccountId(b.accountId());
                            r.setName(b.name());
                            r.setEnabled(spec != null && b.enabled() ? 1 : 0);
                            r.setCurrentRevision(1);
                            r.setRulesJson(Json.write(b.rules()));
                            r.setAuthPaused(spec == null || b.authPaused() ? 1 : 0);
                            r.setSortOrder(b.sortOrder());
                            r.setVersion(1);
                            r.setSafeHost(
                                    spec == null
                                            ? ""
                                            : CurlParser.validateUrl(spec.rawUrl()).getHost());
                            r.setMethod(spec == null ? "GET" : spec.method());
                            requests.insert(r);
                            if (spec != null) {
                                var rev = new RequestRevision();
                                rev.setRequestId(b.id());
                                rev.setRevision(1);
                                rev.setRawCurl(b.rawCurl());
                                rev.setSpecJson(Json.write(spec));
                                rev.setCreatedAt(now);
                                revisions.insert(rev);
                            }
                        }
                        for (var t : s.templates()) {
                            var r = new RequestTemplate();
                            r.setId(t.id());
                            r.setPlatformId(t.platformId());
                            r.setName(t.name());
                            r.setRulesJson(Json.write(t.rules()));
                            r.setVersion(1);
                            templates.insert(r);
                        }
                        for (var plan : s.schedules()) {
                            var p = plan.spec().toSpec().validated();
                            var r = ScheduleConverter.toEntity(plan.platformId(), p, now);
                            r.setRevision(1);
                            schedules.insert(r);
                        }
                        for (var c : s.completed())
                            queue.saveCompletion(c.requestId(), c.businessDate(), Ids.next());
                        for (var c : s.pending())
                            queue.savePending(c.requestId(), c.businessDate(), 1);
                        var target = Checks.found(settings.selectById(1));
                        target.setPaused(1);
                        target.setConcurrency(s.settings().concurrency());
                        target.setTimeoutSeconds(s.settings().timeoutSeconds());
                        target.setRetentionDays(s.settings().retentionDays());
                        target.setProxyMode(s.settings().proxy().mode());
                        target.setProxyHost(s.settings().proxy().host());
                        target.setProxyPort(s.settings().proxy().port());
                        Checks.changed(settings.updateById(target));
                    });
        } finally {
            runs.endMaintenance();
        }
    }
}

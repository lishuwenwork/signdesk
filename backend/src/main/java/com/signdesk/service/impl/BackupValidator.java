package com.signdesk.service.impl;

import com.signdesk.common.*;
import com.signdesk.domain.model.ValidatedBackup;
import com.signdesk.domain.vo.BackupFile;
import com.signdesk.engine.*;
import com.signdesk.storage.DatabaseInitializer;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

@Component
@RequiredArgsConstructor
final class BackupValidator {
    static final int PAYLOAD_LIMIT = 8388608;
    static final int FILE_LIMIT = 12582912;
    private final CurlParser parser;

    ValidatedBackup validate(BackupFile file) {
        try {
            return validateFile(file);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("配置文件结构、ID、关系或字段不正确");
        }
    }

    private ValidatedBackup validateFile(BackupFile file) {
        if (file == null
                || !DatabaseInitializer.FORMAT.equals(file.format())
                || file.payload() == null)
            throw new ApiException("只支持 signdesk-plain-v1 明文配置，不支持旧格式");
        if (Json.write(file).getBytes(StandardCharsets.UTF_8).length > FILE_LIMIT)
            throw new ApiException("配置文件超过12 MiB");
        var s = file.payload();
        if (Json.write(s).getBytes(StandardCharsets.UTF_8).length > PAYLOAD_LIMIT)
            throw new ApiException("配置内容超过8 MiB");
        Objects.requireNonNull(s.settings());
        Objects.requireNonNull(s.settings().proxy());
        var proxy = s.settings().proxy();
        Objects.requireNonNull(proxy.mode());
        Objects.requireNonNull(proxy.host());
        new ProxySettings(proxy.mode(), proxy.host(), proxy.port()).validated();
        SettingsServiceImpl.validateRanges(
                s.settings().concurrency(),
                s.settings().timeoutSeconds(),
                s.settings().retentionDays());
        if (s.settings().version() < 1) throw new IllegalArgumentException();
        count(s.platforms(), 1000);
        count(s.accounts(), 10000);
        count(s.requests(), 10000);
        count(s.templates(), 10000);
        count(s.schedules(), 1000);
        count(s.completed(), 100000);
        count(s.pending(), 100000);
        Set<String> platforms = new HashSet<>(),
                accounts = new HashSet<>(),
                requests = new HashSet<>(),
                templates = new HashSet<>(),
                schedules = new HashSet<>();
        for (var p : s.platforms()) {
            id(p.id());
            Checks.name(p.name(), 40);
            Objects.requireNonNull(p.note());
            if (!platforms.add(p.id()) || p.note().length() > 500)
                throw new IllegalArgumentException();
        }
        for (var a : s.accounts()) {
            id(a.id());
            id(a.platformId());
            Checks.name(a.alias(), 40);
            if (!platforms.contains(a.platformId()) || !accounts.add(a.id()))
                throw new IllegalArgumentException();
        }
        var prepared = new ArrayList<ValidatedBackup.PreparedRequest>();
        for (var r : s.requests()) {
            id(r.id());
            id(r.accountId());
            Checks.name(r.name(), 60);
            Objects.requireNonNull(r.rules());
            ResultRules.validate(r.rules());
            if (!accounts.contains(r.accountId()) || !requests.add(r.id()))
                throw new IllegalArgumentException();
            if (!s.includesRequests() && r.rawCurl() != null) throw new IllegalArgumentException();
            RequestSpec spec = null;
            if (s.includesRequests()) {
                if (r.rawCurl() == null) {
                    // A configuration-only import has no saved revision yet. It stays a disabled
                    // placeholder.
                    if (r.enabled() || !r.authPaused()) throw new IllegalArgumentException();
                } else spec = parser.parse(r.rawCurl()).spec();
            }
            prepared.add(new ValidatedBackup.PreparedRequest(r, spec));
        }
        for (var t : s.templates()) {
            id(t.id());
            id(t.platformId());
            Checks.name(t.name(), 60);
            Objects.requireNonNull(t.rules());
            ResultRules.validate(t.rules());
            if (!platforms.contains(t.platformId()) || !templates.add(t.id()))
                throw new IllegalArgumentException();
        }
        for (var plan : s.schedules()) {
            id(plan.platformId());
            Objects.requireNonNull(plan.spec());
            if (!platforms.contains(plan.platformId())
                    || !schedules.add(plan.platformId())
                    || plan.spec().revision() < 1) throw new IllegalArgumentException();
            plan.spec().toSpec().validated();
        }
        if (!schedules.equals(platforms)) throw new IllegalArgumentException();
        if (!s.includesRequests() && (!s.completed().isEmpty() || !s.pending().isEmpty()))
            throw new IllegalArgumentException();
        markers(s.completed(), requests);
        markers(s.pending(), requests);
        return new ValidatedBackup(file, List.copyOf(prepared));
    }

    private void markers(List<BackupFile.DayMarker> markers, Set<String> requests) {
        Set<String> keys = new HashSet<>();
        for (var c : markers) {
            id(c.requestId());
            if (!requests.contains(c.requestId())
                    || c.businessDate() == null
                    || !c.businessDate().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
                    || !LocalDate.parse(c.businessDate()).toString().equals(c.businessDate())
                    || !keys.add(c.requestId() + ":" + c.businessDate()))
                throw new IllegalArgumentException();
        }
    }

    private void count(List<?> list, int limit) {
        if (list == null || list.size() > limit) throw new IllegalArgumentException();
    }

    private void id(String id) {
        if (id == null || !id.matches("[1-9][0-9]{0,18}") || Long.parseLong(id) <= 0)
            throw new IllegalArgumentException();
    }
}

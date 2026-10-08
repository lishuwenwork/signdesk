package com.signdesk.platform;

import cn.hutool.core.util.IdUtil;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.signdesk.common.ApiException;
import com.signdesk.common.Json;
import com.signdesk.engine.CurlParser;
import com.signdesk.engine.RequestSpec;
import com.signdesk.engine.ResultRules;
import com.signdesk.storage.Db;
import com.signdesk.storage.SecretStore;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;

@Service
public class CatalogService {
    private final Db db;
    private final PlatformMapper platforms;
    private final CurlParser parser;
    private final SecretStore secrets;
    private final Clock clock;

    public record StoredRequest(String rawCurl, RequestSpec spec) {}

    public CatalogService(
            Db db, PlatformMapper platforms, CurlParser parser, SecretStore secrets, Clock clock) {
        this.db = db;
        this.platforms = platforms;
        this.parser = parser;
        this.secrets = secrets;
        this.clock = clock;
    }

    public List<Platform> platforms() {
        return platforms.selectList(new QueryWrapper<Platform>().orderByAsc("sort_order", "id"));
    }

    public List<Map<String, Object>> tree() {
        List<Map<String, Object>> list =
                db.rows("SELECT * FROM platforms ORDER BY sort_order,rowid");
        for (var p : list) {
            var accounts =
                    db.rows(
                            "SELECT * FROM accounts WHERE platform_id=? ORDER BY sort_order,rowid",
                            p.get("id"));
            for (var a : accounts) {
                var requests =
                        db.rows(
                                "SELECT * FROM requests WHERE account_id=? ORDER BY"
                                        + " sort_order,rowid",
                                a.get("id"));
                for (var r : requests) {
                    r.put("rules", Json.read(Db.text(r, "rulesJson"), ResultRules.class));
                    r.remove("rulesJson");
                }
                a.put("requests", requests);
            }
            p.put("accounts", accounts);
        }
        return list;
    }

    @Transactional
    public String addPlatform(String name, String note, boolean enabled) {
        Platform p = new Platform();
        p.id = IdUtil.fastSimpleUUID();
        p.name = name.trim();
        p.note = note == null ? "" : note;
        p.enabled = enabled ? 1 : 0;
        p.sortOrder = 0;
        p.version = 1;
        platforms.insert(p);
        db.update(
                "INSERT INTO platform_schedules(platform_id,effective_from) VALUES(?,?)",
                p.id,
                clock.instant().toString());
        return p.id;
    }

    @Transactional
    public void updatePlatform(String id, String name, String note, boolean enabled, int version) {
        if (db.update(
                        "UPDATE platforms SET name=?,note=?,enabled=?,version=version+1 WHERE id=?"
                                + " AND version=?",
                        name.trim(),
                        note == null ? "" : note,
                        enabled ? 1 : 0,
                        id,
                        version)
                != 1) conflict();
    }

    @Transactional
    public String addAccount(String platformId, String alias, boolean enabled) {
        db.one("SELECT id FROM platforms WHERE id=?", platformId);
        String id = IdUtil.fastSimpleUUID();
        db.update(
                "INSERT INTO accounts(id,platform_id,alias,enabled) VALUES(?,?,?,?)",
                id,
                platformId,
                alias.trim(),
                enabled ? 1 : 0);
        return id;
    }

    @Transactional
    public void updateAccount(String id, String alias, boolean enabled, int version) {
        if (db.update(
                        "UPDATE accounts SET alias=?,enabled=?,version=version+1 WHERE id=? AND"
                                + " version=?",
                        alias.trim(),
                        enabled ? 1 : 0,
                        id,
                        version)
                != 1) conflict();
    }

    @Transactional
    public String addRequest(
            String accountId, String name, String curl, ResultRules rules, boolean enabled) {
        db.one("SELECT id FROM accounts WHERE id=?", accountId);
        var parsed = parser.parse(curl);
        rules = ResultRules.validate(rules);
        String id = IdUtil.fastSimpleUUID();
        db.update(
                "INSERT INTO requests(id,account_id,name,enabled,rules_json,safe_host,method)"
                        + " VALUES(?,?,?,?,?,?,?)",
                id,
                accountId,
                name.trim(),
                enabled ? 1 : 0,
                Json.write(rules),
                CurlParser.validateUrl(parsed.spec().rawUrl()).getHost(),
                parsed.spec().method());
        saveRevision(id, 1, curl, parsed.spec());
        return id;
    }

    @Transactional
    public int replaceRequest(String id, String curl, int version) {
        var row = db.one("SELECT * FROM requests WHERE id=?", id);
        var parsed = parser.parse(curl);
        int revision = Db.integer(row, "currentRevision") + 1;
        if (db.update(
                        "UPDATE requests SET"
                            + " current_revision=?,auth_paused=0,safe_host=?,method=?,version=version+1"
                            + " WHERE id=? AND version=?",
                        revision,
                        CurlParser.validateUrl(parsed.spec().rawUrl()).getHost(),
                        parsed.spec().method(),
                        id,
                        version)
                != 1) conflict();
        saveRevision(id, revision, curl, parsed.spec());
        return revision;
    }

    private void saveRevision(String id, int revision, String raw, RequestSpec spec) {
        db.update(
                "INSERT INTO request_revisions VALUES(?,?,?,?)",
                id,
                revision,
                secrets.encrypt(Json.write(new StoredRequest(raw, spec)), id + ":" + revision),
                clock.instant().toString());
    }

    @Transactional
    public void updateRequest(
            String id, String name, boolean enabled, ResultRules rules, int version) {
        rules = ResultRules.validate(rules);
        if (db.update(
                        "UPDATE requests SET name=?,enabled=?,rules_json=?,version=version+1 WHERE"
                                + " id=? AND version=?",
                        name.trim(),
                        enabled ? 1 : 0,
                        Json.write(rules),
                        id,
                        version)
                != 1) conflict();
    }

    public StoredRequest revision(String id, int revision) {
        var row =
                db.one(
                        "SELECT ciphertext FROM request_revisions WHERE request_id=? AND"
                                + " revision=?",
                        id,
                        revision);
        return Json.read(
                secrets.decrypt(Db.text(row, "ciphertext"), id + ":" + revision),
                StoredRequest.class);
    }

    @Transactional
    public void delete(String table, String id) {
        if (!List.of("platforms", "accounts", "requests").contains(table))
            throw new IllegalArgumentException();
        String where =
                switch (table) {
                    case "platforms" -> "p.id=?";
                    case "accounts" -> "a.id=?";
                    default -> "r.id=?";
                };
        if (db.count(
                        "SELECT COUNT(*) FROM run_items i JOIN requests r ON r.id=i.request_id JOIN"
                                + " accounts a ON a.id=r.account_id JOIN platforms p ON"
                                + " p.id=a.platform_id WHERE "
                                + where
                                + " AND i.status IN('queued','running')",
                        id)
                > 0) throw new ApiException(409, "存在排队或运行中的任务，请先取消并等待结束");
        // Completed queued batches with no items may otherwise reference a deleted request;
        // foreign-key cascades handle them.
        if (db.update("DELETE FROM " + table + " WHERE id=?", id) != 1)
            throw new ApiException(404, "对象不存在");
    }

    public static void conflict() {
        throw new ApiException(409, "配置已被其他页面更新，请刷新后重试");
    }
}

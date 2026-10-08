package com.signdesk.common;

import com.signdesk.engine.CurlParser;
import com.signdesk.engine.ResultRules;
import com.signdesk.platform.CatalogService;
import com.signdesk.run.RunService;
import com.signdesk.run.SettingsService;
import com.signdesk.schedule.ScheduleService;
import com.signdesk.schedule.ScheduleSpec;
import com.signdesk.storage.Db;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {
    public record PlatformInput(
            @NotBlank @Size(max = 40) String name,
            @Size(max = 500) String note,
            boolean enabled,
            int version) {}

    public record AccountInput(
            @NotBlank @Size(max = 40) String alias, boolean enabled, int version) {}

    public record RequestInput(
            @NotBlank @Size(max = 60) String name,
            boolean enabled,
            ResultRules rules,
            int version) {}

    public record NewRequest(
            @NotBlank @Size(max = 60) String name,
            @NotBlank @Size(max = 262144) String curl,
            boolean enabled,
            ResultRules rules) {}

    public record NewTemplate(@NotBlank @Size(max = 60) String name, ResultRules rules) {}

    public record TemplateInput(
            @NotBlank @Size(max = 60) String name, ResultRules rules, @Min(1) int version) {}

    public record CurlPreviewInput(@NotBlank @Size(max = 262144) String curl) {}

    public record CurlInput(@NotBlank @Size(max = 262144) String curl, @Min(1) int version) {}

    public record RuleTest(ResultRules rules, int httpStatus, @Size(max = 1048576) String body) {}

    private final CatalogService catalog;
    private final CurlParser parser;
    private final ScheduleService schedules;
    private final RunService runs;
    private final SettingsService settings;
    private final Db db;
    private final Clock clock;

    public ApiController(
            CatalogService catalog,
            CurlParser parser,
            ScheduleService schedules,
            RunService runs,
            SettingsService settings,
            Db db,
            Clock clock) {
        this.catalog = catalog;
        this.parser = parser;
        this.schedules = schedules;
        this.runs = runs;
        this.settings = settings;
        this.db = db;
        this.clock = clock;
    }

    @GetMapping("/platforms")
    public Object platforms() {
        return catalog.tree();
    }

    @PostMapping("/platforms")
    public Object platform(@Valid @RequestBody PlatformInput r) {
        return Map.of("id", catalog.addPlatform(r.name(), r.note(), r.enabled()));
    }

    @PutMapping("/platforms/{id}")
    public Object platform(@PathVariable String id, @Valid @RequestBody PlatformInput r) {
        catalog.updatePlatform(id, r.name(), r.note(), r.enabled(), r.version());
        return Map.of("saved", true);
    }

    @DeleteMapping("/platforms/{id}")
    public Object deletePlatform(@PathVariable String id) {
        catalog.delete("platforms", id);
        return Map.of("deleted", true);
    }

    @GetMapping("/platforms/{platformId}/templates")
    public Object templates(@PathVariable String platformId) {
        return catalog.templates(platformId);
    }

    @PostMapping("/platforms/{platformId}/templates")
    public Object template(@PathVariable String platformId, @Valid @RequestBody NewTemplate r) {
        return Map.of("id", catalog.addTemplate(platformId, r.name(), r.rules()));
    }

    @PutMapping("/platforms/{platformId}/templates/{id}")
    public Object template(
            @PathVariable String platformId, @PathVariable String id,
            @Valid @RequestBody TemplateInput r) {
        catalog.updateTemplate(platformId, id, r.name(), r.rules(), r.version());
        return Map.of("saved", true);
    }

    @DeleteMapping("/platforms/{platformId}/templates/{id}")
    public Object deleteTemplate(@PathVariable String platformId, @PathVariable String id) {
        catalog.deleteTemplate(platformId, id);
        return Map.of("deleted", true);
    }

    @PostMapping("/platforms/{id}/accounts")
    public Object addAccount(@PathVariable String id, @Valid @RequestBody AccountInput r) {
        return Map.of("id", catalog.addAccount(id, r.alias(), r.enabled()));
    }

    @PutMapping("/accounts/{id}")
    public Object account(@PathVariable String id, @Valid @RequestBody AccountInput r) {
        catalog.updateAccount(id, r.alias(), r.enabled(), r.version());
        return Map.of("saved", true);
    }

    @DeleteMapping("/accounts/{id}")
    public Object deleteAccount(@PathVariable String id) {
        catalog.delete("accounts", id);
        return Map.of("deleted", true);
    }

    @PostMapping("/requests/preview")
    public Object preview(@Valid @RequestBody CurlPreviewInput r) {
        return parser.parse(r.curl());
    }

    @PostMapping("/accounts/{id}/requests")
    public Object request(@PathVariable String id, @Valid @RequestBody NewRequest r) {
        return Map.of("id", catalog.addRequest(id, r.name(), r.curl(), r.rules(), r.enabled()));
    }

    @PutMapping("/requests/{id}")
    public Object request(@PathVariable String id, @Valid @RequestBody RequestInput r) {
        catalog.updateRequest(id, r.name(), r.enabled(), r.rules(), r.version());
        return Map.of("saved", true);
    }

    @PostMapping("/requests/{id}/revisions")
    public Object revision(@PathVariable String id, @Valid @RequestBody CurlInput r) {
        return Map.of("revision", catalog.replaceRequest(id, r.curl(), r.version()));
    }

    @GetMapping("/requests/{id}/revision")
    public Object revision(
            @PathVariable String id, @RequestParam(required = false) Integer revision) {
        int selected =
                revision == null
                        ? Db.integer(
                                db.one("SELECT current_revision FROM requests WHERE id=?", id),
                                "currentRevision")
                        : revision;
        return catalog.revision(id, selected);
    }

    @DeleteMapping("/requests/{id}")
    public Object deleteRequest(@PathVariable String id) {
        catalog.delete("requests", id);
        return Map.of("deleted", true);
    }

    @PostMapping("/rules/test")
    public Object rules(@Valid @RequestBody RuleTest r) {
        return Map.of(
                "status",
                ResultRules.validate(r.rules())
                        .classify(r.httpStatus(), r.body() == null ? "" : r.body()));
    }

    @GetMapping("/schedules")
    public Object schedules() {
        return schedules.list();
    }

    @GetMapping("/platforms/{id}/schedule")
    public Object schedule(@PathVariable String id) {
        return schedules.get(id);
    }

    @PutMapping("/platforms/{id}/schedule")
    public Object schedule(@PathVariable String id, @RequestBody ScheduleSpec r) {
        schedules.save(id, r);
        return Map.of("saved", true);
    }

    @PostMapping("/runs")
    public ResponseEntity<?> execute(@RequestBody RunService.ManualRun r) {
        return ResponseEntity.accepted().body(Map.of("batchIds", runs.manual(r)));
    }

    @GetMapping("/batches")
    public Object batches() {
        return runs.active();
    }

    @GetMapping("/batches/{id}")
    public Object batch(@PathVariable String id) {
        return runs.batch(id);
    }

    @PostMapping("/batches/{id}/cancel")
    public Object cancel(@PathVariable String id) {
        runs.cancel(id);
        return Map.of("cancelled", true);
    }

    @GetMapping("/runs")
    public Object logs(
            @RequestParam(required = false) String platformId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return runs.logs(platformId, status, source, page, size);
    }

    @GetMapping("/runs/{id}")
    public Object run(@PathVariable String id) {
        return db.one(
                "SELECT"
                    + " i.id,i.batch_id,i.request_id,i.request_revision,i.status,i.http_status,i.duration_ms,i.safe_summary,i.started_at,i.finished_at,b.business_date,b.source,b.created_at"
                    + " FROM run_items i JOIN run_batches b ON b.id=i.batch_id WHERE i.id=?",
                id);
    }

    @GetMapping("/settings")
    public Object settings() {
        return settings.get();
    }

    @PutMapping("/settings")
    public Object settings(@RequestBody SettingsService.Settings r) {
        settings.save(r);
        return Map.of("saved", true);
    }

    @GetMapping("/system/status")
    public Object system() {
        return Map.of(
                "ready",
                runs.ready(),
                "paused",
                settings.get().paused(),
                "activeBatches",
                runs.active().size(),
                "now",
                clock.instant().toString(),
                "version",
                "0.1.0");
    }

    @GetMapping("/dashboard")
    public Object dashboard() {
        String date = clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString();
        String utcDate = clock.instant().atZone(ZoneId.of("UTC")).toLocalDate().toString();
        String join =
                " FROM requests r JOIN accounts a ON a.id=r.account_id JOIN platform_schedules s ON"
                        + " s.platform_id=a.platform_id";
        String businessDate = "CASE WHEN s.timezone='UTC' THEN ? ELSE ? END";
        long completed =
                db.count(
                        "SELECT COUNT(*)"
                                + join
                                + " WHERE EXISTS(SELECT 1 FROM daily_completions d WHERE"
                                + " d.request_id=r.id AND d.business_date="
                                + businessDate
                                + ")",
                        utcDate,
                        date);
        long needsAttention =
                db.count(
                        "SELECT COUNT(*)"
                                + join
                                + " WHERE r.auth_paused=1 OR EXISTS(SELECT 1 FROM"
                                + " request_day_states d WHERE d.request_id=r.id AND"
                                + " d.business_date="
                                + businessDate
                                + " AND d.unknown_pending=1)",
                        utcDate,
                        date);
        return Map.of(
                "date",
                date,
                "platforms",
                db.count("SELECT COUNT(*) FROM platforms"),
                "accounts",
                db.count("SELECT COUNT(*) FROM accounts"),
                "requests",
                db.count("SELECT COUNT(*) FROM requests"),
                "completed",
                completed,
                "needsAttention",
                needsAttention,
                "active",
                runs.active(),
                "schedules",
                schedules.list(),
                "recent",
                runs.logs(null, null, null, 1, 6).get("items"));
    }
}

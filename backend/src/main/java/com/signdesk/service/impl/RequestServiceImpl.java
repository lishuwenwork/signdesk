package com.signdesk.service.impl;

import com.signdesk.common.*;
import com.signdesk.domain.*;
import com.signdesk.domain.bo.*;
import com.signdesk.domain.vo.*;
import com.signdesk.engine.*;
import com.signdesk.mapper.*;
import com.signdesk.service.IRequestService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
@RequiredArgsConstructor
public class RequestServiceImpl implements IRequestService {
    private final RequestDefinitionMapper requests;
    private final AccountMapper accounts;
    private final RequestRevisionMapper revisions;
    private final CurlParser parser;
    private final CatalogGuard guard;
    private final Clock clock;

    @Override
    @Transactional
    public String insert(String accountId, NewRequestBo b) {
        Checks.found(accounts.selectById(accountId));
        var parsed = parser.parse(b.curl());
        var r = new RequestDefinition();
        r.setAccountId(accountId);
        r.setName(Checks.name(b.name(), 60));
        r.setEnabled(b.enabled() ? 1 : 0);
        r.setCurrentRevision(1);
        r.setRulesJson(Json.write(ResultRules.validate(b.rules())));
        r.setAuthPaused(0);
        r.setSortOrder(0);
        r.setVersion(1);
        r.setSafeHost(CurlParser.validateUrl(parsed.spec().rawUrl()).getHost());
        r.setMethod(parsed.spec().method());
        requests.insert(r);
        saveRevision(r.getId(), 1, b.curl(), parsed.spec());
        return r.getId();
    }

    @Override
    @Transactional
    public void update(String id, RequestBo b) {
        var r = new RequestDefinition();
        r.setId(id);
        r.setName(Checks.name(b.name(), 60));
        r.setEnabled(b.enabled() ? 1 : 0);
        r.setRulesJson(Json.write(ResultRules.validate(b.rules())));
        r.setVersion(b.version());
        Checks.changed(requests.updateById(r));
    }

    @Override
    @Transactional
    public int replaceCurl(String id, CurlBo b) {
        var old = Checks.found(requests.selectById(id));
        var parsed = parser.parse(b.curl());
        int revision = old.getCurrentRevision() + 1;
        var r = new RequestDefinition();
        r.setId(id);
        r.setCurrentRevision(revision);
        r.setAuthPaused(0);
        r.setSafeHost(CurlParser.validateUrl(parsed.spec().rawUrl()).getHost());
        r.setMethod(parsed.spec().method());
        r.setVersion(b.version());
        Checks.changed(requests.updateById(r));
        saveRevision(id, revision, b.curl(), parsed.spec());
        return revision;
    }

    private void saveRevision(String id, int number, String raw, RequestSpec spec) {
        var r = new RequestRevision();
        r.setRequestId(id);
        r.setRevision(number);
        r.setRawCurl(raw);
        r.setSpecJson(Json.write(spec));
        r.setCreatedAt(clock.instant().toString());
        revisions.insert(r);
    }

    @Override
    public RevisionVo queryRevision(String id, Integer revision) {
        int selected =
                revision == null
                        ? Checks.found(requests.selectById(id)).getCurrentRevision()
                        : revision;
        var r = Checks.found(revisions.query(id, selected));
        return new RevisionVo(r.getRawCurl(), Json.read(r.getSpecJson(), RequestSpec.class));
    }

    @Override
    public CurlPreviewVo preview(CurlPreviewBo b) {
        var p = parser.parse(b.curl());
        return new CurlPreviewVo(p.spec(), p.warnings());
    }

    @Override
    public RuleTestVo testRules(RuleTestBo b) {
        return new RuleTestVo(
                ResultRules.validate(b.rules())
                        .classify(b.httpStatus(), b.body() == null ? "" : b.body()));
    }

    @Override
    @Transactional
    public void delete(String id) {
        guard.request(id);
        if (requests.deleteById(id) != 1) throw new ApiException(404, "对象不存在");
    }
}

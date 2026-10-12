package com.signdesk.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.signdesk.common.*;
import com.signdesk.converter.CatalogConverter;
import com.signdesk.domain.*;
import com.signdesk.domain.bo.*;
import com.signdesk.domain.model.RequestRuleConfig;
import com.signdesk.domain.vo.*;
import com.signdesk.engine.ResultRules;
import com.signdesk.mapper.*;
import com.signdesk.service.IRequestTemplateService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RequestTemplateServiceImpl implements IRequestTemplateService {
    private final RequestTemplateMapper templates;
    private final PlatformMapper platforms;
    private final AccountMapper accounts;
    private final RequestDefinitionMapper requests;

    private RequestTemplate scoped(String platformId, String id) {
        return Checks.found(templates.selectOne(new LambdaQueryWrapper<RequestTemplate>()
                .eq(RequestTemplate::getId, id).eq(RequestTemplate::getPlatformId, platformId)));
    }

    private List<Account> platformAccounts(String platformId) {
        return accounts.selectList(new LambdaQueryWrapper<Account>()
                .eq(Account::getPlatformId, platformId).orderByAsc(Account::getSortOrder, Account::getId));
    }

    private List<RequestDefinition> platformRequests(List<Account> accounts) {
        if (accounts.isEmpty()) return List.of();
        return requests.selectList(new LambdaQueryWrapper<RequestDefinition>()
                .in(RequestDefinition::getAccountId, accounts.stream().map(Account::getId).toList())
                .orderByAsc(RequestDefinition::getSortOrder, RequestDefinition::getId));
    }

    private void validateMode(String mode) {
        if (!"selected".equals(mode) && !"linked".equals(mode)) throw new ApiException("应用方式不正确");
    }

    @Override
    @Transactional(readOnly = true)
    public List<RequestTemplateVo> queryList(String platformId) {
        Checks.found(platforms.selectById(platformId));
        var ts = templates.selectList(new LambdaQueryWrapper<RequestTemplate>()
                .eq(RequestTemplate::getPlatformId, platformId).orderByAsc(RequestTemplate::getId));
        var counts = new HashMap<String, Integer>();
        if (!ts.isEmpty()) {
            for (var request : platformRequests(platformAccounts(platformId))) {
                var id = RequestRuleConfig.read(request.getRulesJson()).templateId();
                if (id != null) counts.merge(id, 1, Integer::sum);
            }
        }
        return ts.stream().map(t -> {
            var vo = CatalogConverter.template(t);
            vo.setLinkedRequestCount(counts.getOrDefault(t.getId(), 0));
            return vo;
        }).toList();
    }

    @Override
    @Transactional
    public String insert(String platformId, NewRequestTemplateBo b) {
        Checks.found(platforms.selectById(platformId));
        var t = new RequestTemplate();
        t.setPlatformId(platformId);
        t.setName(Checks.name(b.name(), 60));
        t.setRulesJson(Json.write(ResultRules.validate(b.rules())));
        t.setVersion(1);
        templates.insert(t);
        return t.getId();
    }

    @Override
    @Transactional
    public void update(String platformId, String id, RequestTemplateBo b) {
        scoped(platformId, id);
        var t = new RequestTemplate();
        t.setId(id);
        t.setName(Checks.name(b.name(), 60));
        t.setRulesJson(Json.write(ResultRules.validate(b.rules())));
        t.setVersion(b.version());
        Checks.changed(templates.updateById(t));
    }

    @Override
    @Transactional(readOnly = true)
    public TemplateApplyPreviewVo previewApplication(String platformId, String id, String mode) {
        validateMode(mode);
        var template = scoped(platformId, id);
        var as = platformAccounts(platformId);
        var aliases = as.stream().collect(Collectors.toMap(Account::getId, Account::getAlias));
        var names = templates.selectList(new LambdaQueryWrapper<RequestTemplate>()
                .eq(RequestTemplate::getPlatformId, platformId)).stream()
                .collect(Collectors.toMap(RequestTemplate::getId, RequestTemplate::getName));
        var targets = new ArrayList<TemplateApplyPreviewVo.Target>();
        for (var r : platformRequests(as)) {
            var linked = RequestRuleConfig.read(r.getRulesJson()).templateId();
            if (mode.equals("linked") && !id.equals(linked)) continue;
            targets.add(new TemplateApplyPreviewVo.Target(r.getId(), r.getName(), aliases.get(r.getAccountId()),
                    r.getVersion(), Checks.flag(r.getEnabled()), linked, names.get(linked)));
        }
        return new TemplateApplyPreviewVo(id, template.getName(), template.getVersion(), mode,
                Json.read(template.getRulesJson(), ResultRules.class), List.copyOf(targets));
    }

    @Override
    @Transactional
    public TemplateAppliedVo apply(String platformId, String id, TemplateApplyBo b) {
        validateMode(b.mode());
        if (b.targets() == null || b.targets().isEmpty() || b.targets().size() > 10000)
            throw new ApiException("请选择 1～10000 个请求");
        var template = scoped(platformId, id);
        if (template.getVersion() != b.templateVersion())
            throw new ApiException(409, "模板已被更新，请重新加载应用范围");
        var current = platformRequests(platformAccounts(platformId));
        var byId = current.stream().collect(Collectors.toMap(RequestDefinition::getId, r -> r));
        var configs = current.stream().collect(Collectors.toMap(RequestDefinition::getId,
                r -> RequestRuleConfig.read(r.getRulesJson())));
        var selected = new HashSet<String>();
        for (var target : b.targets()) {
            if (target == null || target.id() == null || !selected.add(target.id()))
                throw new ApiException("请求选择不能重复或为空");
            var r = Checks.found(byId.get(target.id()));
            if (r.getVersion() != target.version())
                throw new ApiException(409, "请求配置已被更新，请重新加载应用范围");
        }
        if (b.mode().equals("linked")) {
            var linked = current.stream().filter(r -> id.equals(configs.get(r.getId()).templateId()))
                    .map(RequestDefinition::getId).collect(Collectors.toSet());
            if (!linked.equals(selected))
                throw new ApiException(409, "关联请求范围已变化，请重新加载后确认");
        }
        var rules = ResultRules.validate(Json.read(template.getRulesJson(), ResultRules.class));
        String json = new RequestRuleConfig(rules, id).toJson();
        int newlyLinked = 0;
        // All preconditions are checked before any write; optimistic conflicts roll back the batch.
        for (var target : b.targets()) {
            if (!id.equals(configs.get(target.id()).templateId())) newlyLinked++;
            var r = new RequestDefinition();
            r.setId(target.id());
            r.setRulesJson(json);
            r.setVersion(target.version());
            Checks.changed(requests.updateById(r));
        }
        return new TemplateAppliedVo(b.targets().size(), newlyLinked);
    }

    @Override
    @Transactional
    public void delete(String platformId, String id) {
        scoped(platformId, id);
        for (var r : platformRequests(platformAccounts(platformId))) {
            var config = RequestRuleConfig.read(r.getRulesJson());
            if (!id.equals(config.templateId())) continue;
            var patch = new RequestDefinition();
            patch.setId(r.getId());
            patch.setRulesJson(new RequestRuleConfig(config.rules(), null).toJson());
            patch.setVersion(r.getVersion());
            Checks.changed(requests.updateById(patch));
        }
        if (templates.deleteById(id) != 1) throw new ApiException(404, "模板不存在或不属于此平台");
    }
}

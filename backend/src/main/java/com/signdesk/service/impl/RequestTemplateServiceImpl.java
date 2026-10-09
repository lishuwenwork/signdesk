package com.signdesk.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.signdesk.common.*;
import com.signdesk.converter.CatalogConverter;
import com.signdesk.domain.RequestTemplate;
import com.signdesk.domain.bo.NewRequestTemplateBo;
import com.signdesk.domain.bo.RequestTemplateBo;
import com.signdesk.domain.vo.RequestTemplateVo;
import com.signdesk.engine.ResultRules;
import com.signdesk.mapper.*;
import com.signdesk.service.IRequestTemplateService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RequestTemplateServiceImpl implements IRequestTemplateService {
    private final RequestTemplateMapper templates;
    private final PlatformMapper platforms;

    private RequestTemplate scoped(String platformId, String id) {
        return Checks.found(
                templates.selectOne(
                        new LambdaQueryWrapper<RequestTemplate>()
                                .eq(RequestTemplate::getId, id)
                                .eq(RequestTemplate::getPlatformId, platformId)));
    }

    @Override
    public List<RequestTemplateVo> queryList(String platformId) {
        Checks.found(platforms.selectById(platformId));
        return templates
                .selectList(
                        new LambdaQueryWrapper<RequestTemplate>()
                                .eq(RequestTemplate::getPlatformId, platformId)
                                .orderByAsc(RequestTemplate::getId))
                .stream()
                .map(CatalogConverter::template)
                .toList();
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
    @Transactional
    public void delete(String platformId, String id) {
        scoped(platformId, id);
        if (templates.deleteById(id) != 1) throw new ApiException(404, "模板不存在或不属于此平台");
    }
}

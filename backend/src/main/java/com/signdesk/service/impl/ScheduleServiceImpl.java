package com.signdesk.service.impl;

import com.signdesk.common.Checks;
import com.signdesk.converter.ScheduleConverter;
import com.signdesk.domain.bo.ScheduleBo;
import com.signdesk.domain.model.SchedulePlan;
import com.signdesk.domain.vo.*;
import com.signdesk.mapper.*;
import com.signdesk.service.IScheduleService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ScheduleServiceImpl implements IScheduleService {
    private final PlatformScheduleMapper schedules;
    private final ScheduleQueryMapper queries;
    private final Clock clock;

    @Override
    public ScheduleVo queryById(String id) {
        return ScheduleConverter.toVo(Checks.found(schedules.selectById(id)));
    }

    @Override
    @Transactional
    public void save(String id, ScheduleBo b) {
        var s = b.toSpec().validated();
        var r = ScheduleConverter.toEntity(id, s, clock.instant().toString());
        Checks.changed(schedules.updateById(r));
    }

    @Override
    public List<ScheduleListVo> queryList() {
        return queries.queryList().stream()
                .map(
                        row -> {
                            var spec = ScheduleConverter.toVo(row);
                            var next =
                                    Checks.flag(row.getPlatformEnabled())
                                            ? spec.toSpec().next(clock.instant())
                                            : null;
                            var v = new ScheduleListVo();
                            v.setPlatformId(row.getPlatformId());
                            v.setRevision(row.getRevision());
                            v.setEnabled(row.getEnabled());
                            v.setFrequency(row.getFrequency());
                            v.setTimezone(row.getTimezone());
                            v.setIntervalSeconds(row.getIntervalSeconds());
                            v.setCatchupMinutes(row.getCatchupMinutes());
                            v.setSkipCompletedDaily(row.getSkipCompletedDaily());
                            v.setEffectiveFrom(row.getEffectiveFrom());
                            v.setName(row.getName());
                            v.setPlatformEnabled(row.getPlatformEnabled());
                            v.setSpec(spec);
                            v.setNextAt(next == null ? null : next.toString());
                            return v;
                        })
                .toList();
    }

    @Override
    public List<SchedulePlan> enabledPlans() {
        return queries.enabledPlans().stream()
                .map(
                        r ->
                                new SchedulePlan(
                                        r.getPlatformId(),
                                        ScheduleConverter.toVo(r).toSpec(),
                                        Instant.parse(r.getEffectiveFrom())))
                .toList();
    }
}

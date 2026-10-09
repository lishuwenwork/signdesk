package com.signdesk.service;

import com.signdesk.domain.*;
import com.signdesk.domain.bo.*;
import com.signdesk.domain.model.*;
import com.signdesk.domain.vo.*;

import java.util.List;

public interface IScheduleService {
    ScheduleVo queryById(String platformId);

    List<ScheduleListVo> queryList();

    List<SchedulePlan> enabledPlans();

    void save(String platformId, ScheduleBo input);
}

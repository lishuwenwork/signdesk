package com.signdesk.service;

import com.signdesk.domain.*;
import com.signdesk.domain.bo.*;
import com.signdesk.domain.model.*;
import com.signdesk.domain.vo.*;

import java.util.List;

public interface IRequestTemplateService {
    List<RequestTemplateVo> queryList(String platformId);

    String insert(String platformId, NewRequestTemplateBo input);

    void update(String platformId, String id, RequestTemplateBo input);

    void delete(String platformId, String id);
}

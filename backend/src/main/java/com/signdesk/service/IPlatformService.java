package com.signdesk.service;

import com.signdesk.domain.*;
import com.signdesk.domain.bo.*;
import com.signdesk.domain.model.*;
import com.signdesk.domain.vo.*;

import java.util.List;

public interface IPlatformService {
    List<PlatformVo> queryList();

    String insert(PlatformBo input);

    void update(String id, PlatformBo input);

    void delete(String id);
}

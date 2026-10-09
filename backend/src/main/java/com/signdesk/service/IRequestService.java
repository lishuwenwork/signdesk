package com.signdesk.service;

import com.signdesk.domain.*;
import com.signdesk.domain.bo.*;
import com.signdesk.domain.model.*;
import com.signdesk.domain.vo.*;

public interface IRequestService {
    String insert(String accountId, NewRequestBo input);

    void update(String id, RequestBo input);

    int replaceCurl(String id, CurlBo input);

    RevisionVo queryRevision(String id, Integer revision);

    CurlPreviewVo preview(CurlPreviewBo input);

    RuleTestVo testRules(RuleTestBo input);

    void delete(String id);
}

package com.signdesk.service;

import com.signdesk.domain.*;
import com.signdesk.domain.bo.*;
import com.signdesk.domain.model.*;
import com.signdesk.domain.vo.*;

public interface IAccountService {
    String insert(String platformId, NewAccountBo input);

    void update(String id, AccountBo input);

    void delete(String id);
}

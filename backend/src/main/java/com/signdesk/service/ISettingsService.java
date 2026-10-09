package com.signdesk.service;

import com.signdesk.domain.*;
import com.signdesk.domain.bo.*;
import com.signdesk.domain.model.*;
import com.signdesk.domain.vo.*;

public interface ISettingsService {
    SettingsVo query();

    void save(SettingsBo input);
}

package com.signdesk.service;

import com.signdesk.domain.bo.LoginBo;

public interface IAuthService {
    String authenticate(LoginBo input, String remoteAddress);
}

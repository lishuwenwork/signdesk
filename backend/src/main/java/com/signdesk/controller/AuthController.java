package com.signdesk.controller;

import com.signdesk.config.AuthSession;
import com.signdesk.domain.bo.LoginBo;
import com.signdesk.domain.vo.AuthSessionVo;
import com.signdesk.domain.vo.SavedVo;
import com.signdesk.service.IAuthService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final IAuthService service;

    @PostMapping("/login")
    public AuthSessionVo login(@Valid @RequestBody LoginBo input, HttpServletRequest request) {
        String username = service.authenticate(input, request.getRemoteAddr());
        return AuthSession.login(request, username);
    }

    @GetMapping("/session")
    public AuthSessionVo session(HttpServletRequest request) {
        return AuthSession.current(request);
    }

    @PostMapping("/logout")
    public SavedVo logout(HttpServletRequest request, HttpServletResponse response) {
        AuthSession.logout(request, response);
        return new SavedVo(true);
    }
}

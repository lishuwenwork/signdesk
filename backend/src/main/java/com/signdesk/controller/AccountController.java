package com.signdesk.controller;

import com.signdesk.domain.bo.*;
import com.signdesk.domain.vo.*;
import com.signdesk.service.*;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AccountController {
    private final IAccountService service;

    @PostMapping("/platforms/{id}/accounts")
    public IdVo insert(@PathVariable String id, @Valid @RequestBody NewAccountBo b) {
        return new IdVo(service.insert(id, b));
    }

    @PutMapping("/accounts/{id}")
    public SavedVo update(@PathVariable String id, @Valid @RequestBody AccountBo b) {
        service.update(id, b);
        return new SavedVo(true);
    }

    @DeleteMapping("/accounts/{id}")
    public DeletedVo delete(@PathVariable String id) {
        service.delete(id);
        return new DeletedVo(true);
    }
}

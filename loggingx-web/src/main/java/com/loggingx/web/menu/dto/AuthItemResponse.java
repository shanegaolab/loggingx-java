package com.loggingx.web.menu.dto;

import com.loggingx.dao.menu.Menu;

/**
 * A button permission inside {@code meta.authList}.
 */
public record AuthItemResponse(Long id, String title, String authMark, int sort) {

    public static AuthItemResponse from(Menu button) {
        return new AuthItemResponse(button.id(), button.title(), button.authMark(), button.sort());
    }
}

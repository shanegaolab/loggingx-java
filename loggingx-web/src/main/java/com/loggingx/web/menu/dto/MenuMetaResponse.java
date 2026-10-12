package com.loggingx.web.menu.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.loggingx.common.util.DateTimes;
import com.loggingx.dao.menu.Menu;

import java.util.List;

/**
 * Route meta in the frontend's {@code RouteMeta} shape.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MenuMetaResponse(
        String title,
        String icon,
        int sort,
        @JsonProperty("isEnable") boolean isEnable,
        boolean keepAlive,
        @JsonProperty("isHide") boolean isHide,
        @JsonProperty("isHideTab") boolean isHideTab,
        String link,
        @JsonProperty("isIframe") boolean isIframe,
        boolean showBadge,
        String showTextBadge,
        boolean fixedTab,
        String activePath,
        @JsonProperty("isFullPage") boolean isFullPage,
        List<AuthItemResponse> authList,
        String updateTime
) {

    public static MenuMetaResponse from(Menu menu, List<Menu> buttons) {
        List<AuthItemResponse> authList = buttons.isEmpty()
                ? null
                : buttons.stream().map(AuthItemResponse::from).toList();
        return new MenuMetaResponse(menu.title(), menu.icon(), menu.sort(), menu.enabled(), menu.keepAlive(),
                menu.hidden(), menu.hideTab(), menu.link(), menu.iframe(), menu.showBadge(), menu.textBadge(),
                menu.fixedTab(), menu.activePath(), menu.fullPage(), authList, DateTimes.format(menu.updatedAt()));
    }
}

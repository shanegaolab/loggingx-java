package com.loggingx.web.menu.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.loggingx.biz.menu.MenuCommand;
import com.loggingx.dao.menu.MenuType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The menu dialog's form. For {@code menuType = "menu"}, {@code name} is the title and {@code label} the route
 * name; for {@code "button"} only {@code authName}, {@code authLabel} and {@code authSort} are used.
 *
 * @param parentId parent menu id when creating; {@code null} or 0 for a top-level menu, ignored on update
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MenuRequest(
        @NotBlank(message = "menuType must not be blank")
        @Pattern(regexp = "menu|button", message = "menuType must be menu or button")
        String menuType,
        Long parentId,
        @Size(max = 50, message = "name must be at most 50 characters") String name,
        @Size(max = 200, message = "path must be at most 200 characters") String path,
        @Size(max = 50, message = "label must be at most 50 characters") String label,
        @Size(max = 200, message = "component must be at most 200 characters") String component,
        @Size(max = 100, message = "icon must be at most 100 characters") String icon,
        @JsonProperty("isEnable") boolean isEnable,
        int sort,
        boolean keepAlive,
        @JsonProperty("isHide") boolean isHide,
        @JsonProperty("isHideTab") boolean isHideTab,
        @Size(max = 500, message = "link must be at most 500 characters") String link,
        @JsonProperty("isIframe") boolean isIframe,
        boolean showBadge,
        @Size(max = 20, message = "showTextBadge must be at most 20 characters") String showTextBadge,
        boolean fixedTab,
        @Size(max = 200, message = "activePath must be at most 200 characters") String activePath,
        @JsonProperty("isFullPage") boolean isFullPage,
        @Size(max = 50, message = "authName must be at most 50 characters") String authName,
        @Size(max = 50, message = "authLabel must be at most 50 characters") String authLabel,
        int authSort
) {

    public MenuCommand toCommand() {
        if ("button".equals(menuType)) {
            return new MenuCommand(MenuType.BUTTON, parentId, authName, null, null, null, null, authSort, true,
                    false, false, false, null, false, false, null, false, null, false, authLabel);
        }
        return new MenuCommand(MenuType.MENU, parentId, name, label, path, component, icon, sort, isEnable,
                keepAlive, isHide, isHideTab, link, isIframe, showBadge, showTextBadge, fixedTab, activePath,
                isFullPage, null);
    }
}

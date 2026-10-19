package com.loggingx.biz.menu;

import com.loggingx.biz.permission.PermissionService;
import com.loggingx.biz.support.Updates;
import com.loggingx.common.exception.BadRequestException;
import com.loggingx.common.exception.ConflictException;
import com.loggingx.common.exception.NotFoundException;
import com.loggingx.common.util.Strings;
import com.loggingx.dao.account.UserAccount;
import com.loggingx.dao.account.UserAccountRepository;
import com.loggingx.dao.menu.Menu;
import com.loggingx.dao.menu.MenuRepository;
import com.loggingx.dao.menu.MenuType;
import com.loggingx.dao.role.RoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class MenuService {

    private static final Logger log = LoggerFactory.getLogger(MenuService.class);
    /** Component of a top-level directory: the layout that hosts its child pages. */
    public static final String LAYOUT_COMPONENT = "/index/index";

    private static final String MENU_NOT_FOUND = "Menu was not found";

    private static final Comparator<Menu> DISPLAY_ORDER = Comparator.comparingInt(Menu::sort).thenComparing(Menu::id);

    private final MenuRepository menuRepository;
    private final RoleRepository roleRepository;
    private final UserAccountRepository userAccountRepository;
    private final PermissionService permissionService;

    public MenuService(
            MenuRepository menuRepository,
            RoleRepository roleRepository,
            UserAccountRepository userAccountRepository,
            PermissionService permissionService
    ) {
        this.menuRepository = menuRepository;
        this.roleRepository = roleRepository;
        this.userAccountRepository = userAccountRepository;
        this.permissionService = permissionService;
    }

    /**
     * @return every menu and button, including disabled ones, for the management page
     */
    public List<MenuNode> tree() {
        return buildTree(menuRepository.findAll());
    }

    /**
     * @return the enabled menus and buttons the user may access, without directories left empty by filtering
     */
    public List<MenuNode> routesFor(String username) {
        UserAccount account = userAccountRepository.findByUsername(username)
                .orElseThrow(() -> new NotFoundException("User account was not found"));
        Set<Long> accessible = permissionService.accessibleMenuIds(account);
        List<Menu> visible = menuRepository.findAll().stream()
                .filter(menu -> menu.enabled() && accessible.contains(menu.id()))
                .toList();
        return withoutEmptyDirectories(buildTree(visible));
    }

    public Menu create(MenuCommand command) {
        long parentId = command.parentId() == null ? Menu.ROOT_PARENT_ID : command.parentId();
        if (parentId != Menu.ROOT_PARENT_ID && requireMenu(parentId).isButton()) {
            throw new BadRequestException("A button cannot have children");
        }
        if (command.type() == MenuType.BUTTON) {
            if (parentId == Menu.ROOT_PARENT_ID) {
                throw new BadRequestException("A button must belong to a menu");
            }
            ensureAuthMarkUnique(parentId, command.authMark(), null);
        }
        Menu menu = toMenu(null, parentId, command.type(), command);
        Menu created = menuRepository.insert(menu).orElseThrow(() -> duplicateRouteName(menu.name()));
        log.info("event=menu.create.success menuId={} type={} name={}",
                created.id(), created.type(), created.name() == null ? created.title() : created.name());
        return created;
    }

    public Menu update(Long id, MenuCommand command) {
        Menu existing = requireMenu(id);
        if (existing.isButton()) {
            ensureAuthMarkUnique(existing.parentId(), command.authMark(), id);
        }
        Menu updated = toMenu(id, existing.parentId(), existing.type(), command);
        Updates.require(menuRepository.update(existing, updated), MENU_NOT_FOUND,
                () -> duplicateRouteName(updated.name()));
        log.info("event=menu.update.success menuId={} type={} name={}",
                updated.id(), updated.type(), updated.name() == null ? updated.title() : updated.name());
        return updated;
    }

    /**
     * Deletes the menu together with its descendants and buttons, and revokes them from every role.
     */
    public void delete(Long id) {
        requireMenu(id);
        Set<Long> removed = subtreeIds(id);
        removed.forEach(menuRepository::deleteById);

        Instant now = Instant.now();
        roleRepository.findAll().stream()
                .filter(role -> role.menuIds().stream().anyMatch(removed::contains))
                // Applied to the current role, so a concurrent menu assignment is not overwritten.
                .forEach(role -> roleRepository.modify(role.id(), current -> current.withMenuIds(
                        current.menuIds().stream()
                                .filter(menuId -> !removed.contains(menuId))
                                .collect(Collectors.toSet()),
                        now)));
        log.info("event=menu.delete.success menuId={} removedCount={}", id, removed.size());
    }

    private List<MenuNode> buildTree(List<Menu> menus) {
        Map<Long, List<Menu>> byParent = menus.stream().collect(Collectors.groupingBy(Menu::parentId));
        return childrenOf(Menu.ROOT_PARENT_ID, byParent);
    }

    private List<MenuNode> childrenOf(Long parentId, Map<Long, List<Menu>> byParent) {
        return byParent.getOrDefault(parentId, List.of()).stream()
                .filter(menu -> !menu.isButton())
                .sorted(DISPLAY_ORDER)
                .map(menu -> new MenuNode(menu, childrenOf(menu.id(), byParent), buttonsOf(menu.id(), byParent)))
                .toList();
    }

    private List<Menu> buttonsOf(Long menuId, Map<Long, List<Menu>> byParent) {
        return byParent.getOrDefault(menuId, List.of()).stream()
                .filter(Menu::isButton)
                .sorted(DISPLAY_ORDER)
                .toList();
    }

    private List<MenuNode> withoutEmptyDirectories(List<MenuNode> nodes) {
        return nodes.stream()
                .map(node -> new MenuNode(node.menu(), withoutEmptyDirectories(node.children()), node.buttons()))
                .filter(node -> !node.children().isEmpty() || !isDirectory(node.menu()))
                .toList();
    }

    private static boolean isDirectory(Menu menu) {
        return Strings.isBlank(menu.link())
                && (Strings.isBlank(menu.component()) || LAYOUT_COMPONENT.equals(menu.component()));
    }

    private Set<Long> subtreeIds(Long rootId) {
        Map<Long, List<Menu>> byParent = menuRepository.findAll().stream()
                .collect(Collectors.groupingBy(Menu::parentId));
        Set<Long> ids = new HashSet<>();
        Deque<Long> pending = new ArrayDeque<>(List.of(rootId));
        while (!pending.isEmpty()) {
            Long current = pending.pop();
            if (ids.add(current)) {
                byParent.getOrDefault(current, List.of()).forEach(child -> pending.push(child.id()));
            }
        }
        return ids;
    }

    private void ensureAuthMarkUnique(Long parentId, String authMark, Long selfId) {
        // Marks are stored trimmed, so compare the trimmed value; a blank mark is rejected by toMenu.
        String mark = Strings.trimToNull(authMark);
        boolean taken = mark != null && menuRepository.findAll().stream()
                .anyMatch(menu -> menu.isButton() && menu.parentId().equals(parentId)
                        && mark.equals(menu.authMark()) && !menu.id().equals(selfId));
        if (taken) {
            throw new ConflictException("Permission mark '" + mark + "' already exists on this menu");
        }
    }

    private Menu toMenu(Long id, Long parentId, MenuType type, MenuCommand command) {
        Instant now = Instant.now();
        if (type == MenuType.BUTTON) {
            requireText(command.title(), "Permission name must not be blank");
            requireText(command.authMark(), "Permission mark must not be blank");
            return new Menu(id, parentId, type, null, null, null, command.title().trim(), null, command.sort(),
                    true, false, false, false, null, false, false, null, false, null, false,
                    command.authMark().trim(), now);
        }
        requireText(command.title(), "Menu name must not be blank");
        requireText(command.name(), "Route name must not be blank");
        requireText(command.path(), "Route path must not be blank");
        return new Menu(id, parentId, type, command.name().trim(), command.path().trim(),
                Strings.trimToNull(command.component()), command.title().trim(), Strings.trimToNull(command.icon()),
                command.sort(), command.enabled(), command.keepAlive(), command.hidden(), command.hideTab(),
                Strings.trimToNull(command.link()), command.iframe(), command.showBadge(),
                Strings.trimToNull(command.textBadge()), command.fixedTab(), Strings.trimToNull(command.activePath()),
                command.fullPage(), null, now);
    }

    private Menu requireMenu(Long id) {
        return menuRepository.findById(id).orElseThrow(() -> new NotFoundException(MENU_NOT_FOUND));
    }

    private static ConflictException duplicateRouteName(String name) {
        return new ConflictException("Route name '" + name + "' is already used by another menu");
    }

    private static void requireText(String value, String message) {
        if (Strings.isBlank(value)) {
            throw new BadRequestException(message);
        }
    }
}

package com.loggingx.web.bootstrap;

import com.loggingx.biz.permission.PermissionService;
import com.loggingx.dao.account.UserAccount;
import com.loggingx.dao.account.UserAccountRepository;
import com.loggingx.dao.dept.Dept;
import com.loggingx.dao.dept.DeptRepository;
import com.loggingx.dao.post.Post;
import com.loggingx.dao.post.PostRepository;
import com.loggingx.dao.role.Role;
import com.loggingx.dao.role.RoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

/**
 * Seeds the demo accounts the frontend expects, on top of the roles from {@link SystemDataInitializer}, and demo
 * departments and posts. Each kind is seeded only while its store is empty.
 * <p>
 * Demo accounts {@code super}, {@code admin} and {@code user} all use the password {@value #DEMO_PASSWORD}, which
 * is shorter than {@code PasswordPolicy} allows so that it matches the frontend's demo login; it is only ever
 * seeded under the {@code dev} profile.
 */
@Component
@Profile("dev")
@Order(2)
public class DemoDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);
    private static final String DEMO_PASSWORD = "123456";
    private static final String SYSTEM = "system";

    private final RoleRepository roleRepository;
    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final DeptRepository deptRepository;
    private final PostRepository postRepository;

    public DemoDataInitializer(
            RoleRepository roleRepository,
            UserAccountRepository userAccountRepository,
            PasswordEncoder passwordEncoder,
            DeptRepository deptRepository,
            PostRepository postRepository
    ) {
        this.roleRepository = roleRepository;
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.deptRepository = deptRepository;
        this.postRepository = postRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (deptRepository.findAll().isEmpty()) {
            seedDepts();
        }
        if (postRepository.findAll().isEmpty()) {
            seedPosts();
        }
        if (userAccountRepository.findAll().isEmpty()) {
            seedAccounts();
        }
    }

    private void seedDepts() {
        Long company = dept(Dept.ROOT_PARENT_ID, "LoggingX 科技", 1, "张三", "13800000010");
        Long rnd = dept(company, "研发部", 1, "李四", "13800000011");
        dept(rnd, "后端组", 1, null, null);
        dept(rnd, "前端组", 2, null, null);
        dept(company, "运维部", 2, "王五", "13800000012");
        dept(company, "市场部", 3, null, null);
        log.info("Seeded demo data: 6 departments");
    }

    private void seedPosts() {
        post("CEO", "董事长", 1);
        post("PM", "项目经理", 2);
        post("DEV", "开发工程师", 3);
        post("OPS", "运维工程师", 4);
        log.info("Seeded demo data: 4 posts");
    }

    private Long dept(Long parentId, String name, int sort, String leader, String phone) {
        Instant now = Instant.now();
        return deptRepository.insert(new Dept(null, parentId, name, sort, leader, phone, null, true, now, now))
                .orElseThrow().id();
    }

    private void post(String code, String name, int sort) {
        Instant now = Instant.now();
        postRepository.insert(new Post(null, code, name, sort, true, null, now, now)).orElseThrow();
    }

    private void seedAccounts() {
        account("super", "超级管理员", "1", "13800000001", "super@loggingx.local", PermissionService.SUPER_ROLE_CODE);
        account("admin", "管理员", "1", "13800000002", "admin@loggingx.local", SystemDataInitializer.ADMIN_ROLE_CODE);
        account("user", "普通用户", "2", "13800000003", "user@loggingx.local", SystemDataInitializer.USER_ROLE_CODE);
        log.info("Seeded demo data: 3 accounts");
    }

    private void account(String username, String nickName, String gender, String phone, String email,
                         String roleCode) {
        Long roleId = roleRepository.findByCode(roleCode).map(Role::id).orElseThrow();
        Instant now = Instant.now();
        userAccountRepository.insert(new UserAccount(null, username, passwordEncoder.encode(DEMO_PASSWORD), 0,
                nickName, gender, phone, email, null, UserAccount.STATUS_ONLINE, Set.of(roleId), now, SYSTEM,
                now, SYSTEM));
    }
}

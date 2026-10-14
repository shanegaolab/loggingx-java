package com.loggingx.dao.support;

import com.loggingx.dao.account.MybatisUserAccountRepository;
import com.loggingx.dao.account.UserAccountMapper;
import com.loggingx.dao.menu.MenuMapper;
import com.loggingx.dao.menu.MybatisMenuRepository;
import com.loggingx.dao.role.MybatisRoleRepository;
import com.loggingx.dao.role.RoleMapper;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.UUID;

/**
 * The production repositories on a fresh, empty H2 database in MySQL mode with the production schema. Shared with
 * other modules' tests through the test-jar.
 */
public final class TestDatabase {

    public final MybatisUserAccountRepository userAccountRepository;
    public final MybatisRoleRepository roleRepository;
    public final MybatisMenuRepository menuRepository;

    public TestDatabase() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(dataSource);

        // Mirrors the mybatis.* settings in loggingx-web's application.yml.
        SqlSessionTemplate session;
        try {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setMapperLocations(
                    new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml"));
            factory.setTypeHandlers(new UtcInstantTypeHandler());
            session = new SqlSessionTemplate(factory.getObject());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot set up MyBatis", e);
        }

        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        userAccountRepository = new MybatisUserAccountRepository(session.getMapper(UserAccountMapper.class),
                transactionManager);
        roleRepository = new MybatisRoleRepository(session.getMapper(RoleMapper.class), transactionManager);
        menuRepository = new MybatisMenuRepository(session.getMapper(MenuMapper.class), transactionManager);
    }
}

package com.uav.lowaltitude.modules.directory.api;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
/** 在专门建立的隔离测试库运行相同的目录数据范围用例，不连接业务库。 */
@EnabledIfEnvironmentVariable(named="POSTGRES_TEST_URL",matches="jdbc:postgresql://[^/]+/(advisory_verify_evidence_org_[a-z0-9_]+|directory_verify_[a-z0-9_]+)")
class DirectoryScopePostgresTest extends DirectoryScopeApiTest {
 @DynamicPropertySource static void database(DynamicPropertyRegistry r){String url=System.getenv("POSTGRES_TEST_URL");
  if(url==null||!url.matches("jdbc:postgresql://[^/]+/(advisory_verify_evidence_org_[a-z0-9_]+|directory_verify_[a-z0-9_]+)"))throw new IllegalArgumentException("仅允许显式隔离目录测试库");
  r.add("spring.datasource.url",()->url);r.add("spring.datasource.username",()->System.getenv("POSTGRES_TEST_USER"));r.add("spring.datasource.password",()->System.getenv("POSTGRES_TEST_PASSWORD"));r.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");r.add("spring.flyway.locations",()->"classpath:db/migration,classpath:db/postgresql");
 }
}

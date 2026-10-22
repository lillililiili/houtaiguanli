package com.uav.lowaltitude.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.platform.database.MigrationLineage;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class UnitRoleCopyOriginMigrationTest {
    protected record Database(DriverManagerDataSource source, String schema, boolean postgres, Runnable cleanup)
            implements AutoCloseable {
        @Override public void close() { cleanup.run(); }
    }

    protected Database database() {
        return new Database(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+
                ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1","sa",""),
                "public",false,()->{});
    }

    @Test void upgradeUsesExactAuditRelationshipsAndPreservesPermissionsMembershipAndDeletedSourceName() throws Exception {
        try (Database db=database()) {
            migrate(db,"202610200007");
            JdbcTemplate jdbc=new JdbcTemplate(db.source());
            String suffix=UUID.randomUUID().toString().substring(0,8), org="org-"+suffix, other="other-"+suffix;
            String source="ORIGINAL-"+suffix, copy="COPY-"+suffix, name="原始值班角色 "+suffix;
            organization(jdbc,org);organization(jdbc,other);
            role(jdbc,source,name,null);role(jdbc,copy,name,org);
            jdbc.update("INSERT INTO app_role_permission(role_code,permission_code,permission_level,menu_enabled) VALUES(?,'alarms','READ',true)",copy);
            String user="member-"+suffix;
            jdbc.update("""
                    INSERT INTO app_user(user_id,account,name,org_id,role_code,status,password_hash,fail_count,
                      scope_mode,scope_org_rule,must_change_password,permission_version,created_at,updated_at,version)
                    VALUES(?,?,?,?,?,'ACTIVE','test-hash',0,'ASSIGNED','OWN_ORG',false,4,1200,1400,3)
                    """,user,user,user,org,copy);
            String detail=detail(org,user,source,name,copy);
            audit(jdbc,org,detail,"SUCCESS",1700);
            jdbc.update("DELETE FROM app_role WHERE role_code=?",source);
            String manual="MANUAL-"+suffix;
            role(jdbc,manual,name,other); // Identical display names do not prove a copy relationship.
            var roleBefore=jdbc.queryForMap("SELECT * FROM app_role WHERE role_code=?",copy);
            var usersBefore=jdbc.queryForList("SELECT * FROM app_user ORDER BY user_id");
            var grantsBefore=jdbc.queryForList("SELECT * FROM app_role_permission ORDER BY role_code,permission_code");
            var auditsBefore=jdbc.queryForList("SELECT * FROM audit_log ORDER BY audit_id");

            var latest=migrate(db,null);
            var roleAfter=jdbc.queryForMap("SELECT * FROM app_role WHERE role_code=?",copy);
            assertThat(roleAfter.remove("copied_from_role_code")).isEqualTo(source);
            assertThat(roleAfter.remove("copied_from_role_name")).isEqualTo(name);
            assertThat(roleAfter.remove("copied_at")).isEqualTo(1300L);
            assertThat(roleAfter).isEqualTo(roleBefore);
            assertThat(jdbc.queryForList("SELECT * FROM app_user ORDER BY user_id")).isEqualTo(usersBefore);
            assertThat(jdbc.queryForList("SELECT * FROM app_role_permission ORDER BY role_code,permission_code")).isEqualTo(grantsBefore);
            assertThat(jdbc.queryForList("SELECT * FROM audit_log ORDER BY audit_id")).isEqualTo(auditsBefore);
            assertThat(jdbc.queryForObject("SELECT copied_from_role_code FROM app_role WHERE role_code=?",String.class,manual)).isNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_role WHERE role_code=?",Integer.class,source)).isZero();
            assertThat(latest.migrate().migrationsExecuted).isZero();
            latest.validate();
        }
    }

    @Test void unknownConflictingCrossUnitAndMalformedEvidenceCannotInventAnOrigin() throws Exception {
        try (Database db=database()) {
            migrate(db,"202610200007");
            JdbcTemplate jdbc=new JdbcTemplate(db.source());
            String suffix=UUID.randomUUID().toString().substring(0,8), org="unit-"+suffix, other="other-"+suffix;
            organization(jdbc,org);organization(jdbc,other);
            for (String kind:List.of("missing","conflict","foreign","failed","future","user","duplicate","unchanged","invalid"))
                role(jdbc,kind+suffix,kind,kind.equals("foreign")?other:org);
            var json=new ObjectMapper();
            var missingName=json.readTree(detail(org,"u","src","source","missing"+suffix));
            ((com.fasterxml.jackson.databind.node.ObjectNode)missingName.path("members_before").get(0)).remove("role_name");
            audit(jdbc,org,missingName.toString(),"SUCCESS",1700);
            audit(jdbc,org,detail(org,"u","src-a","source A","conflict"+suffix),"SUCCESS",1700);
            audit(jdbc,org,detail(org,"u","src-b","source B","conflict"+suffix),"SUCCESS",1800);
            audit(jdbc,org,detail(org,"u","src","source","foreign"+suffix),"SUCCESS",1700);
            audit(jdbc,org,detail(org,"u","src","source","failed"+suffix),"FAILURE",1700);
            audit(jdbc,org,detail(org,"u","src","source","future"+suffix),"SUCCESS",1200);
            var wrongUser=json.readTree(detail(org,"u","src","source","user"+suffix));
            ((com.fasterxml.jackson.databind.node.ObjectNode)wrongUser.path("members_after").get(0)).put("user_id","different");
            audit(jdbc,org,wrongUser.toString(),"SUCCESS",1700);
            var duplicate=json.readTree(detail(org,"u","src","source","duplicate"+suffix));
            ((com.fasterxml.jackson.databind.node.ArrayNode)duplicate.path("members_before")).add(duplicate.path("members_before").get(0).deepCopy());
            audit(jdbc,org,duplicate.toString(),"SUCCESS",1700);
            audit(jdbc,org,detail(org,"u","unchanged"+suffix,"source","unchanged"+suffix),"SUCCESS",1700);
            audit(jdbc,org,"not a valid snapshot","SUCCESS",1700);
            migrate(db,null);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_role WHERE owner_org_id IN (?,?) AND copied_from_role_code IS NOT NULL",Integer.class,org,other)).isZero();
            assertThatThrownBy(()->jdbc.update("UPDATE app_role SET copied_from_role_code='partial' WHERE role_code=?","invalid"+suffix))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    private static Flyway migrate(Database db,String target) {
        var config=Flyway.configure().dataSource(db.source()).schemas(db.schema()).defaultSchema(db.schema())
                .cleanDisabled(true).locations(db.postgres()?new String[]{"classpath:db/migration","classpath:db/postgresql"}:new String[]{"classpath:db/migration"});
        if (target!=null) config.target(target);
        MigrationLineage.configure(config);
        Flyway flyway=config.load();flyway.migrate();return flyway;
    }
    private static void organization(JdbcTemplate jdbc,String org) {
        jdbc.update("INSERT INTO app_org(org_id,org_code,name,enabled,created_at,updated_at,version) VALUES(?,?,?,true,1000,1000,0)",org,org,org);
    }
    private static void role(JdbcTemplate jdbc,String role,String name,String org) {
        jdbc.update("INSERT INTO app_role(role_code,name,builtin,enabled,created_at,updated_at,version,owner_org_id,name_scope) VALUES(?,?,false,true,1300,1500,5,?,?)",
                role,name,org,org==null?"GLOBAL":org);
    }
    private static String detail(String org,String user,String source,String name,String target) throws Exception {
        return new ObjectMapper().writeValueAsString(Map.of(
                "members_before",List.of(Map.of("org_id",org,"user_id",user,"role_code",source,"role_name",name)),
                "members_after",List.of(Map.of("org_id",org,"user_id",user,"role_code",target,"role_name",name))));
    }
    private static void audit(JdbcTemplate jdbc,String org,String detail,String result,long at) {
        jdbc.update("INSERT INTO audit_log(audit_id,action,object_type,object_id,detail,occurred_at,result) VALUES(?,'unit_delegation_updated','organization',?,?,?,?)",
                UUID.randomUUID().toString(),org,detail,at,result);
    }
}

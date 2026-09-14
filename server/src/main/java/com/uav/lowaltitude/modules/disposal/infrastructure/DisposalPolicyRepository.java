package com.uav.lowaltitude.modules.disposal.infrastructure;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.lowaltitude.modules.disposal.domain.DisposalPolicy;
import com.uav.lowaltitude.modules.fusion.infrastructure.FusionConfigRepository;
import com.uav.lowaltitude.platform.api.ApiException;

/** 处置策略读取。策略是"凭什么允许动手"的依据，因此只读不改：改参数走迁移发新版本，不在运行期改。 */
@Repository
public class DisposalPolicyRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public DisposalPolicyRepository(JdbcTemplate jdbcTemplate, ObjectMapper json) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
        this.json = json;
    }

    /** 当前生效策略。没有生效策略时不能"先放行再说"——没有依据就不该有授权。 */
    public DisposalPolicy active() {
        List<DisposalPolicy> rows = jdbc.query(
                "SELECT policy_code,schema_status,params FROM disposal_policy WHERE status='ACTIVE' ORDER BY policy_code ASC",
                Map.of(), (rs, i) -> new DisposalPolicy(rs.getString("policy_code"), rs.getString("schema_status"),
                        parse(FusionConfigRepository.jsonText(rs.getObject("params")))));
        if (rows.isEmpty())
            throw new ApiException(HttpStatus.CONFLICT, "POLICY_NOT_CONFIGURED", "尚未配置生效的处置策略，不能发起处置授权");
        return rows.get(0);
    }

    public List<DisposalPolicy> all() {
        return jdbc.query("SELECT policy_code,schema_status,params FROM disposal_policy ORDER BY policy_code ASC",
                Map.of(), (rs, i) -> new DisposalPolicy(rs.getString("policy_code"), rs.getString("schema_status"),
                        parse(FusionConfigRepository.jsonText(rs.getObject("params")))));
    }

    /**
     * H2 与 PostgreSQL 存 JSON 列的结果不同：PG 里读回来就是 JSON 对象，H2 会把整段再包一层成 JSON 字符串
     * （双重编码）。因此先 readTree，若拿到的是字符串节点就再解析一次——与 FusionConfigService 同一处理。
     */
    private Map<String, Object> parse(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            JsonNode node = json.readTree(raw);
            if (node != null && node.isTextual()) node = json.readTree(node.textValue());
            if (node == null || !node.isObject()) throw new IllegalStateException("params is not a JSON object");
            return json.convertValue(node, new TypeReference<Map<String, Object>>() { });
        } catch (Exception ex) {
            // 参数读不出来时宁可 500 也不能当成空 Map：空 Map 会让每个 getter 都报"缺参数"，
            // 把"配置坏了"伪装成"配置漏了"，运维会去补一个本来就存在的参数。
            throw new IllegalStateException("disposal policy params are not valid JSON: <"
                    + raw.substring(0, Math.min(160, raw.length())) + ">", ex);
        }
    }
}

package com.uav.lowaltitude.modules.identity.application;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.uav.lowaltitude.modules.identity.domain.DataScope;
import com.uav.lowaltitude.modules.identity.domain.IdentityRows.OrgRow;
import com.uav.lowaltitude.modules.identity.domain.IdentityRows.ScopeRuleRow;
import com.uav.lowaltitude.modules.identity.infrastructure.UserDataScopeMapper;

/**
 * “本单位 / 本单位及下级单位”数据范围的授权元组维护（ZT-14）。
 *
 * <p>业务查询早已按 app_user_data_scope 的“单位 + 区域”元组给 ASSIGNED 账号取交集（告警、目标、设备、证据、
 * 统计、导出等约四十处），这里不另写一套判断，而是把规则展开成元组：所属单位（及其全部下级单位）× 全部区域。
 * 元组只会落在该单位子树内，所以元组陈旧时最多少看，不会多看；会让账号多看的变化（单位换上级、账号换单位）
 * 都在同一事务里重建。
 */
@Service
public class UserDataScopeService {

    private final UserDataScopeMapper mapper;

    public UserDataScopeService(UserDataScopeMapper mapper) {
        this.mapper = mapper;
    }

    /** 账号的规则或所属单位变化后重建它的元组；不按规则维护的账号不动。 */
    @Transactional
    public void refreshUser(String userId) {
        ScopeRuleRow user = mapper.lockUserRule(userId);
        if (user == null || user.getScopeOrgRule() == null) return;
        rebuild(user, childrenByParent());
    }

    /** 单位层级或区域目录变化后重建全部按规则维护的账号。 */
    @Transactional
    public void refreshAll() {
        List<ScopeRuleRow> users = mapper.lockRuleUsers();
        if (users.isEmpty()) return;
        Map<String, List<String>> children = childrenByParent();
        for (ScopeRuleRow user : users) rebuild(user, children);
    }

    /** 改为手工授权元组前先去掉规则，免得下次重建把手工元组覆盖掉。 */
    @Transactional
    public void clearRule(String userId) {
        mapper.clearRule(userId);
    }

    private void rebuild(ScopeRuleRow user, Map<String, List<String>> children) {
        mapper.deleteUserScopes(user.getUserId());
        Set<String> orgIds = scopeOrgIds(user.getOrgId(), user.getScopeOrgRule(), children);
        if (!orgIds.isEmpty()) mapper.insertOrgScopes(user.getUserId(), new ArrayList<>(orgIds));
    }

    private Map<String, List<String>> childrenByParent() {
        Map<String, List<String>> children = new HashMap<>();
        for (OrgRow org : mapper.listOrgLinks()) {
            if (org.getParentId() != null) {
                children.computeIfAbsent(org.getParentId(), key -> new ArrayList<>()).add(org.getOrgId());
            }
        }
        return children;
    }

    static Set<String> scopeOrgIds(String orgId, String rule, Map<String, List<String>> children) {
        Set<String> ids = new LinkedHashSet<>();
        if (orgId == null || rule == null) return ids;
        ids.add(orgId);
        if (!DataScope.OWN_ORG_TREE.name().equals(rule)) return ids;
        Deque<String> pending = new ArrayDeque<>(List.of(orgId));
        while (!pending.isEmpty()) {
            for (String child : children.getOrDefault(pending.pop(), List.of())) {
                if (ids.add(child)) pending.push(child);
            }
        }
        return ids;
    }
}

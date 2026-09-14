package com.uav.lowaltitude.modules.alarm.application;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import java.time.format.DateTimeFormatter;
import org.springframework.http.ResponseEntity;
import com.uav.lowaltitude.platform.audit.AuditService;
import com.uav.lowaltitude.platform.export.CsvExport;
import com.uav.lowaltitude.platform.security.AuthContext;
import com.uav.lowaltitude.platform.security.AuthUser;
import com.uav.lowaltitude.platform.time.AppClock;
import com.uav.lowaltitude.modules.alarm.api.AlarmDtos.DistrictOptionDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import com.uav.lowaltitude.modules.alarm.api.AlarmDtos.AlarmDto;
import com.uav.lowaltitude.modules.alarm.api.AlarmDtos.PageDto;
import com.uav.lowaltitude.modules.alarm.infrastructure.AlarmReadRepository;
import com.uav.lowaltitude.modules.alarm.infrastructure.AlarmReadRepository.AlarmQuery;
import com.uav.lowaltitude.modules.alarm.infrastructure.AlarmReadRepository.AlarmRow;
import com.uav.lowaltitude.modules.identity.application.AccessControlService;
import com.uav.lowaltitude.modules.identity.domain.AccessDecision;
import com.uav.lowaltitude.modules.identity.domain.PermissionCode;
import com.uav.lowaltitude.platform.api.ApiException;

@Service
public class AlarmReadService {
    // 参数白名单：写错的参数必须报错而不是被忽略，否则调用方以为筛过了、拿到的却是全量。
    // 阶段 15 新增 sort/order/alarm_type（决策 15-6 / 15-7）。
    private static final Set<String> ALLOWED = Set.of("state", "severity", "target_id", "occurred_from", "occurred_to",
            "owner_org_id", "district_id", "source_mode", "page", "size", "sort", "order", "alarm_type");
    private final AccessControlService access;
    private final AlarmReadRepository repository;
    private final AuditService audit;
    private final AppClock clock;

    public AlarmReadService(AccessControlService access, AlarmReadRepository repository, AuditService audit,
            AppClock clock) {
        this.access = access; this.repository = repository; this.audit = audit; this.clock = clock;
    }

    /**
     * 列表导出（决策 15-7）。权限、范围、筛选、次序与列表**完全一致**——
     * 导出与页面看到的不是同一批数据，比没有导出更糟。
     * 超过上限拒绝而不是截断：悄悄截断给出的是一份"看起来完整"的表。
     */
    // 导出要写一条审计，因此**不是只读事务**（决策 15-20）：
    // PostgreSQL 的只读事务禁止 INSERT（25006），标了 readOnly 的话这个接口在 PG 上必 500；
    // 而 H2 不落实只读语义，单测照样绿——又一处"H2 绿、PG 红"。
    @Transactional
    public ResponseEntity<byte[]> export(MultiValueMap<String, String> values) {
        AccessDecision decision = access.require(PermissionCode.ALARM_READ);
        AccessDecision targetDecision = values.containsKey("target_id") ? access.require(PermissionCode.TARGET_READ) : null;
        Request request = new Request(values);
        AlarmQuery query = new AlarmQuery(request.optional("state", 32), request.optional("severity", 16),
                request.optional("target_id", 36), request.timeFrom(), request.timeTo(),
                request.optional("owner_org_id", 36), request.optional("district_id", 36),
                request.optional("source_mode", 8), request.optional("alarm_type", 32));
        if (query.targetId() != null && !repository.targetReadable(query.targetId(), targetDecision)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权筛选该关联目标");
        }
        String sort = sortKey(values), order = orderDirection(values);
        long total = repository.count(query, decision);
        if (total > CsvExport.MAX_ROWS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EXPORT_TOO_LARGE",
                    "导出行数超过 " + CsvExport.MAX_ROWS + " 条，请先缩小筛选范围");
        }
        List<AlarmRow> rows = repository.listForExport(query, decision, CsvExport.MAX_ROWS, sort, order);
        List<List<String>> cells = rows.stream().map(AlarmReadService::exportRow).toList();
        AuthUser actor = AuthContext.require();
        audit.record(actor.userId(), actor.account(), actor.roleCode(), "alarms", "alarms_exported", "alarm", null,
                "filters=" + describe(query, sort, order) + "; rows=" + cells.size(), "SUCCESS", "", "");
        return CsvExport.response(CsvExport.fileName("alarms", Instant.ofEpochMilli(clock.nowMillis())),
                EXPORT_HEADERS, cells);
    }

    /** 列头用中文，与页面列一致——导出给的是给人看的表，不是接口字段名。 */
    private static final List<String> EXPORT_HEADERS = List.of(
            "告警编号", "告警类别", "等级", "状态", "发生时间", "接收时间", "目标编号", "所属组织", "所属区域", "来源");

    private static List<String> exportRow(AlarmRow row) {
        // 枚举列翻中文（决策 15-32）：列头是中文、正文却是 HIGH/PENDING_VERIFICATION，拿到的是半中半英的表。
        return java.util.Arrays.asList(row.displayNo(),
                com.uav.lowaltitude.platform.export.CsvLabels.alarmType(row.alarmType()),
                com.uav.lowaltitude.platform.export.CsvLabels.severity(row.severity()),
                com.uav.lowaltitude.platform.export.CsvLabels.uavEventState(row.state()),
                time(row.occurredAt()), time(row.receivedAt()), row.targetNo(), row.ownerOrgName(),
                row.districtName(), row.sourceName());
    }

    private static String time(OffsetDateTime at) {
        return at == null ? null : EXPORT_TIME.format(at.toInstant());
    }

    private static final DateTimeFormatter EXPORT_TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss").withZone(java.time.ZoneId.of("Asia/Shanghai"));

    /** 审计里记下筛选条件与行数：事后要能回答"这份表是谁、按什么条件导出去的"。 */
    private static String describe(AlarmQuery query, String sort, String order) {
        return "state=" + query.state() + ",severity=" + query.severity() + ",alarm_type=" + query.alarmType()
                + ",district_id=" + query.districtId() + ",target_id=" + query.targetId()
                + ",sort=" + sort + ",order=" + order;
    }

    @Transactional(readOnly = true)
    public PageDto<AlarmDto> list(MultiValueMap<String, String> values) {
        AccessDecision decision = access.require(PermissionCode.ALARM_READ);
        // 筛选字段本身已是关联目标的探测入口；在解析其值（甚至分页）之前完成 target:read 鉴权。
        AccessDecision targetDecision = values.containsKey("target_id") ? access.require(PermissionCode.TARGET_READ) : null;
        Request request = new Request(values);
        Page page = request.page();
        AlarmQuery query = new AlarmQuery(request.optional("state", 32), request.optional("severity", 16),
                request.optional("target_id", 36), request.timeFrom(), request.timeTo(),
                request.optional("owner_org_id", 36), request.optional("district_id", 36),
                request.optional("source_mode", 8), request.optional("alarm_type", 32));
        String sort = sortKey(values), order = orderDirection(values);
        if (query.targetId() != null) {
            // target_id 是关联对象筛选，不允许仅以告警读权限用 total 是否变化猜测目标存在或归属。
            if (!repository.targetReadable(query.targetId(), targetDecision)) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权筛选该关联目标");
        }
        long total = repository.count(query, decision);
        return new PageDto<>(repository.list(query, decision, page.offset(), page.size(), sort, order).stream()
                .map(this::dto).toList(), page.page(), page.size(), total);
    }

    /**
     * 排序键与方向都走白名单（决策 15-6）：白名单外**直接 400**，不悄悄回落到默认次序——
     * 悄悄回落会让调用方以为自己排好了序，拿到的却是另一种顺序，而这种错在页面上很难看出来。
     */
    private static String sortKey(MultiValueMap<String, String> values) {
        String sort = values.getFirst("sort");
        if (sort == null || sort.isBlank()) return null;
        if (!AlarmReadRepository.SORT_KEYS.contains(sort)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "排序字段不在允许范围内");
        }
        return sort;
    }

    private static String orderDirection(MultiValueMap<String, String> values) {
        String order = values.getFirst("order");
        if (order == null || order.isBlank()) return null;
        if (!"asc".equalsIgnoreCase(order) && !"desc".equalsIgnoreCase(order)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "排序方向只能是 asc 或 desc");
        }
        return order;
    }

    /**
     * 区域筛选项（决策 15-22）。权限与列表相同，**不要求 `users.read`**——
     * 原来页面拉的是系统管理的 `/districts`，业务角色没有那项权限，筛选框就是空的：
     * 功能在，但只有管理员用得了。
     */
    @Transactional(readOnly = true)
    public List<DistrictOptionDto> districts() {
        AccessDecision decision = access.require(PermissionCode.ALARM_READ);
        return repository.districts(decision).stream()
                .map(row -> new DistrictOptionDto(row.districtId(), row.name())).toList();
    }

    @Transactional(readOnly = true)
    public AlarmDto detail(String alarmId) {
        AccessDecision decision = access.require(PermissionCode.ALARM_READ);
        AlarmRow row = repository.find(id(alarmId), decision);
        if (row == null) throw new ApiException(HttpStatus.NOT_FOUND, "ALARM_NOT_FOUND", "告警不存在");
        return dto(row);
    }

    private AlarmDto dto(AlarmRow row) {
        // target_id 是独立敏感引用：没有 target:read 时宁可省略，也不能以 0 坐标或可猜 ID 替代。
        String targetId = targetReferenceVisible(row.targetId(), row.ownerOrgId(), row.districtId()) ? row.targetId() : null;
        return new AlarmDto(row.alarmId(), row.eventId(), row.state(), row.alarmType(), row.severity(), millis(row.occurredAt()),
                requiredMillis(row.receivedAt()), row.sourceCode(), row.sourceMode(), row.ownerOrgId(), row.districtId(), targetId,
                row.displayNo(), row.sourceName(), row.ownerOrgName(), row.districtName(), targetId == null ? null : row.targetNo());
    }

    private boolean targetReferenceVisible(String targetId, String orgId, String districtId) {
        try { return repository.targetVisible(targetId, orgId, districtId, access.require(PermissionCode.TARGET_READ)); }
        catch (ApiException ignored) { return false; }
    }

    private static String id(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 36) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "ID 格式无效");
        return normalized;
    }
    private static Long millis(OffsetDateTime value) { return value == null ? null : value.toInstant().toEpochMilli(); }
    private static long requiredMillis(OffsetDateTime value) { if (value == null) throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务内部错误"); return value.toInstant().toEpochMilli(); }

    private static final class Request {
        private final MultiValueMap<String, String> values;
        private Request(MultiValueMap<String, String> values) {
            this.values = values;
            values.keySet().stream().filter(key -> !ALLOWED.contains(key)).findFirst().ifPresent(key -> { throw invalid("参数无效"); });
        }
        private Page page() { int page = integer("page", 1), size = integer("size", 20); if (page < 1 || size < 1 || size > 100) throw invalid("分页参数无效"); return new Page(page, size); }
        private int integer(String name, int fallback) { if (!values.containsKey(name)) return fallback; String value = single(name); try { return Integer.parseInt(value); } catch (RuntimeException ex) { throw invalid("分页参数无效"); } }
        private String optional(String name, int max) { if (!values.containsKey(name)) return null; String value = single(name); if (value.length() > max) throw invalid(name + " 参数无效"); return value; }
        private OffsetDateTime timeFrom() { return time("occurred_from", true); }
        private OffsetDateTime timeTo() { OffsetDateTime to = time("occurred_to", false); OffsetDateTime from = time("occurred_from", false); if ((from == null) != (to == null) || (from != null && !from.isBefore(to))) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE", "时间范围无效"); return to; }
        private OffsetDateTime time(String name, boolean ignored) { if (!values.containsKey(name)) return null; try { return Instant.ofEpochMilli(Long.parseLong(single(name))).atOffset(ZoneOffset.UTC); } catch (RuntimeException ex) { throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE", "时间范围无效"); } }
        private String single(String name) { List<String> found = values.get(name); if (found == null || found.size() != 1 || found.get(0) == null || found.get(0).isBlank()) throw invalid(name + " 参数无效"); return found.get(0).trim(); }
        private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message); }
    }
    private record Page(int page, int size) { private int offset() { try { return Math.multiplyExact(page - 1, size); } catch (ArithmeticException ex) { throw Request.invalid("分页参数无效"); } } }
}

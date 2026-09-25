package com.acme.salary.common.audit;

import com.acme.salary.common.audit.dto.AuditEventResponse;
import com.acme.salary.common.web.PageResponse;
import com.acme.salary.common.web.PageableSanitizer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The audit trail (FR-8.2).
 *
 * <p><b>ADMIN only</b>, and more strictly than the rest of the API: this is the one read
 * that spans every feature at once. A single page of it can show which salaries changed,
 * who changed them and what the figures were, so HR's own access to employee data does not
 * extend to the record of everybody's actions — including their own.
 *
 * <p>Read-only by construction. There is no {@code POST}, {@code PUT} or {@code DELETE}
 * here and there will not be: rows are written inside the transaction of the change they
 * describe (ADR-013), and a trail with an edit endpoint is not evidence of anything.
 */
@RestController
@RequestMapping("/api/v1/audit-events")
@Tag(name = "Audit trail", description = "Who changed what, and when (ADMIN only)")
public class AuditController {

    /** Client sort key to entity property (NFR-1.2 keeps sorting to a whitelist). */
    private static final Map<String, String> SORTABLE_PROPERTIES = sortableProperties();

    /**
     * Most recent first, with the id as a tiebreaker.
     *
     * <p>Two events in the same transaction share an instant to the microsecond, so a
     * timestamp alone is not a total order — and paging without one can repeat or skip
     * rows. The id is monotonic, so it settles ties in the order they were written.
     */
    private static final Sort DEFAULT_SORT =
            Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"));

    private final AuditQueryService auditEvents;

    public AuditController(AuditQueryService auditEvents) {
        this.auditEvents = auditEvents;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Query the audit trail",
            description = """
                    Filtered by actor, entity type, entity, action and date range (FR-8.2).
                    Every filter is optional; with none, this is the whole trail, newest
                    first.

                    `from` and `to` are calendar days and both are inclusive, so asking for
                    the same date twice returns that whole day. The boundaries are UTC,
                    which is what the timestamps are stored and returned in.

                    `entityType` is one of `Employee`, `SalaryStructure`, `SalaryComponent`
                    or `PayrollRun`; `action` is a recorded event name such as
                    `PAYROLL_RUN_FINALISED`. Both are matched exactly — an unknown value
                    returns an empty page rather than an error, because the set grows as
                    the system does and a client should not have to be redeployed to read a
                    new one.""")
    public PageResponse<AuditEventResponse> search(
            @Parameter(description = "Restrict to one actor's actions, by user id")
            @RequestParam(required = false) Long actorUserId,
            @Parameter(description = "Employee, SalaryStructure, SalaryComponent or PayrollRun")
            @RequestParam(required = false) String entityType,
            @Parameter(description = "Restrict to one record; most useful with entityType")
            @RequestParam(required = false) Long entityId,
            @Parameter(description = "A recorded action name, e.g. EMPLOYEE_DEACTIVATED")
            @RequestParam(required = false) String action,
            @Parameter(description = "First day in range, inclusive (yyyy-MM-dd, UTC)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @Parameter(description = "Last day in range, inclusive (yyyy-MM-dd, UTC)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @Parameter(description = "Standard page, size and sort parameters")
            @PageableDefault(size = 25) Pageable pageable) {

        AuditSearch search = new AuditSearch(actorUserId, entityType, entityId, action, from, to);
        return auditEvents.search(
                search, PageableSanitizer.sanitize(pageable, SORTABLE_PROPERTIES, DEFAULT_SORT));
    }

    private static Map<String, String> sortableProperties() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("occurredAt", "occurredAt");
        properties.put("entityType", "entityType");
        properties.put("action", "action");
        properties.put("actorUserId", "actorUserId");
        return Collections.unmodifiableMap(properties);
    }
}

package com.acme.salary.employee.dto;

/**
 * A reference-data entry as it appears inside another resource: enough to display and to
 * filter by, without embedding the whole record.
 *
 * @param id the reference-data id, so a client can filter a subsequent query by it
 * @param label the human-readable value — a department name, a job title, a grade name
 */
public record ReferenceResponse(Long id, String label) {
}

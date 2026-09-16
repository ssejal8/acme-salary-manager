package com.acme.salary.orgdata.dto;

import com.acme.salary.orgdata.Designation;

/** A job title as the API exposes it (FR-3.2). */
public record DesignationResponse(Long id, String title) {

    public static DesignationResponse from(Designation designation) {
        return new DesignationResponse(designation.getId(), designation.getTitle());
    }
}

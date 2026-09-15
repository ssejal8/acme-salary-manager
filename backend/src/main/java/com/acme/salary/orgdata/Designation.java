package com.acme.salary.orgdata;

import com.acme.salary.common.error.ValidationException;
import com.acme.salary.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** A job title an employee holds (FR-3.2). Titles are unique. */
@Entity
@Table(name = "designations")
public class Designation extends AuditableEntity {

    @Column(name = "title", nullable = false, length = 120)
    private String title;

    protected Designation() {
        // for JPA
    }

    public Designation(String title) {
        this.title = requireTitle(title);
    }

    public void retitle(String title) {
        this.title = requireTitle(title);
    }

    private static String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw ValidationException.field("title", "must not be blank");
        }
        return title.strip();
    }

    public String getTitle() {
        return title;
    }
}

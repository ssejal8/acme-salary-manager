package com.acme.salary.security;

/**
 * The three user classes (requirements §2.2). One role per user, deliberately flat
 * (ADR-015): a capability that cuts across roles is a code change, not configuration.
 */
public enum Role {
    ADMIN,
    HR,
    EMPLOYEE
}

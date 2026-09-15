package com.acme.salary;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the ACME Salary Management API.
 *
 * <p>The application is a modular monolith (ADR-001): feature packages under this one own
 * their controllers, services, repositories and entities, and talk to each other only
 * through service interfaces.
 */
@SpringBootApplication
public class SalaryManagementApplication {

    public static void main(String[] args) {
        SpringApplication.run(SalaryManagementApplication.class, args);
    }
}

-- Development seed data. This location is on the Flyway path only under the dev profile
-- (see application-dev.yml), so these rows can never reach a deployed schema (ADR-007).
-- Numbered from V900 to stay clear of real migrations, and written idempotently so it is
-- safe against a database that already holds some of it.
--
-- Credentials below are documented in the README and are dev-only by construction:
-- prod never loads this file.
--   admin@acme.test / Admin@123
--   hr@acme.test    / Hr@12345

INSERT INTO users (email, password_hash, role)
VALUES
    ('admin@acme.test', '$2a$10$Cgt5gpY5Vwc5AB4ZnreMJuUxbqpLf.e.ZsrgW4cgn/rEETLTKgL1m', 'ADMIN'),
    ('hr@acme.test',    '$2a$10$1fAySofA9eLq9jSHrqwXDOjNLxG41uU7DWKs76ETLb4tcrrm0ohy2', 'HR')
ON CONFLICT DO NOTHING;

INSERT INTO departments (code, name)
VALUES
    ('ENG', 'Engineering'),
    ('HR',  'Human Resources'),
    ('FIN', 'Finance'),
    ('SAL', 'Sales')
ON CONFLICT DO NOTHING;

INSERT INTO designations (title)
VALUES
    ('Software Engineer'),
    ('Senior Software Engineer'),
    ('Engineering Manager'),
    ('HR Executive'),
    ('Finance Analyst'),
    ('Sales Executive')
ON CONFLICT DO NOTHING;

INSERT INTO grades (name, min_ctc, max_ctc)
VALUES
    ('G1', 400000.00,  800000.00),
    ('G2', 800000.00,  1500000.00),
    ('G3', 1500000.00, 2500000.00),
    ('G4', 2500000.00, 4000000.00)
ON CONFLICT DO NOTHING;

-- A conventional Indian monthly structure: earnings as flat monthly amounts, statutory
-- deductions as a percentage of basic or a flat figure (ADR-016).
INSERT INTO salary_components (code, name, type, calculation_type, default_value, taxable)
VALUES
    ('BASIC',      'Basic Salary',      'EARNING',   'FLAT',             0.00, TRUE),
    ('HRA',        'House Rent Allowance', 'EARNING', 'FLAT',            0.00, TRUE),
    ('CONVEYANCE', 'Conveyance Allowance', 'EARNING', 'FLAT',            0.00, TRUE),
    ('SPECIAL',    'Special Allowance', 'EARNING',   'FLAT',             0.00, TRUE),
    ('PF',         'Provident Fund',    'DEDUCTION', 'PERCENT_OF_BASIC', 12.00, FALSE),
    ('PROF_TAX',   'Professional Tax',  'DEDUCTION', 'FLAT',             200.00, FALSE),
    ('TDS',        'Tax Deducted at Source', 'DEDUCTION', 'FLAT',        0.00, FALSE)
ON CONFLICT DO NOTHING;

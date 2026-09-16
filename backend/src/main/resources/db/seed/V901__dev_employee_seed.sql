-- Development employee seed: twelve people, nine compensation packages, one raise, one
-- leaver and two deliberate gaps.
--
-- Loaded only under the dev profile (see application-dev.yml), like every file in this
-- location, so none of it can reach a deployed schema (ADR-007).
--
-- Deterministic on purpose. Ids, dates and row timestamps are all fixed literals, and
-- nothing derives from now() or a sequence, so:
--   * employee 1001 is the same person on every machine, in every demo and in any test
--     that names an id;
--   * the compensation analytics figures below are reproducible rather than "roughly
--     right", which is what makes them safe to assert on and to quote in the README.
-- Sequences are advanced past the explicit ids at the end, or the next real insert would
-- collide with them.
--
-- The seeded packages sit inside their grade's CTC band, so nothing here would have been
-- rejected had it gone through the API (FR-4.3).
--
-- Expected state after this runs:
--   headcount 12, active 11, active with a package 9
--   total active monthly gross 950,000.00 · deductions 58,800.00 · net 891,200.00
--   by department: Engineering 580,000 · Finance 140,000 · Sales 140,000 · HR 90,000

------------------------------------------------------------------------------------------
-- An employee login, so self-service access can be exercised once authentication lands.
-- Dev-only by construction: prod never loads this file.
--   asha.menon@acme.test / Employee@123
------------------------------------------------------------------------------------------
INSERT INTO users (email, password_hash, role, created_at, updated_at)
VALUES ('asha.menon@acme.test',
        '$2a$10$Ax/2jTNFgm21xQQowpFxU.Na6zkMZ6j85daFgLlt1TRDLbkCZlnYi',
        'EMPLOYEE',
        TIMESTAMPTZ '2026-09-01 00:00:00+00',
        TIMESTAMPTZ '2026-09-01 00:00:00+00')
ON CONFLICT DO NOTHING;

------------------------------------------------------------------------------------------
-- Employees. Reference data is resolved by its natural key rather than by an assumed id,
-- so this stays correct however V900 was applied.
------------------------------------------------------------------------------------------
INSERT INTO employees (
    id, employee_code, first_name, last_name, work_email,
    date_of_joining, exit_date, status,
    department_id, designation_id, grade_id, created_at, updated_at)
SELECT
    seed.id,
    seed.employee_code,
    seed.first_name,
    seed.last_name,
    seed.work_email,
    seed.date_of_joining,
    seed.exit_date,
    seed.status,
    department.id,
    designation.id,
    grade.id,
    TIMESTAMPTZ '2026-09-01 00:00:00+00',
    TIMESTAMPTZ '2026-09-01 00:00:00+00'
FROM (VALUES
    -- id,  code,     first,      last,        email,                        joined,            exited,            status,     dept,  designation,                grade
    (1001, 'E-1001', 'Asha',     'Menon',     'asha.menon@acme.test',       DATE '2022-06-01', NULL,              'ACTIVE',   'ENG', 'Senior Software Engineer', 'G3'),
    (1002, 'E-1002', 'Ravi',     'Iyer',      'ravi.iyer@acme.test',        DATE '2023-01-16', NULL,              'ACTIVE',   'ENG', 'Software Engineer',        'G2'),
    (1003, 'E-1003', 'Neha',     'Sharma',    'neha.sharma@acme.test',      DATE '2024-07-01', NULL,              'ACTIVE',   'ENG', 'Software Engineer',        'G2'),
    (1004, 'E-1004', 'Arjun',    'Rao',       'arjun.rao@acme.test',        DATE '2021-03-15', NULL,              'ACTIVE',   'ENG', 'Engineering Manager',      'G4'),
    (1005, 'E-1005', 'Divya',    'Nair',      'divya.nair@acme.test',       DATE '2023-09-01', NULL,              'ACTIVE',   'FIN', 'Finance Analyst',          'G2'),
    (1006, 'E-1006', 'Karan',    'Gupta',     'karan.gupta@acme.test',      DATE '2025-02-03', NULL,              'ACTIVE',   'FIN', 'Finance Analyst',          'G1'),
    (1007, 'E-1007', 'Meera',    'Krishnan',  'meera.krishnan@acme.test',   DATE '2022-11-21', NULL,              'ACTIVE',   'HR',  'HR Executive',             'G2'),
    (1008, 'E-1008', 'Vikram',   'Singh',     'vikram.singh@acme.test',     DATE '2024-04-08', NULL,              'ACTIVE',   'SAL', 'Sales Executive',          'G1'),
    (1009, 'E-1009', 'Priya',    'Desai',     'priya.desai@acme.test',      DATE '2025-06-16', NULL,              'ACTIVE',   'SAL', 'Sales Executive',          'G2'),
    -- No package yet: a recent joiner. A payroll run skips them, and compensation
    -- analytics reports them as a coverage gap rather than pricing them at zero.
    (1010, 'E-1010', 'Rahul',    'Menon',     'rahul.menon@acme.test',      DATE '2026-08-03', NULL,              'ACTIVE',   'ENG', 'Software Engineer',        'G1'),
    (1011, 'E-1011', 'Sneha',    'Pillai',    'sneha.pillai@acme.test',     DATE '2026-09-01', NULL,              'ACTIVE',   'SAL', 'Sales Executive',          'G1'),
    -- A leaver, kept rather than deleted (ADR-014). Their package stays on record; the
    -- cost report excludes them, and payroll excludes them for periods after the exit.
    (1012, 'E-1012', 'Anil',     'Kumar',     'anil.kumar@acme.test',       DATE '2020-05-04', DATE '2026-03-31', 'INACTIVE', 'FIN', 'Finance Analyst',          'G2')
) AS seed(
    id, employee_code, first_name, last_name, work_email,
    date_of_joining, exit_date, status, department_code, designation_title, grade_name)
JOIN departments department ON department.code = seed.department_code
JOIN designations designation ON designation.title = seed.designation_title
JOIN grades grade ON grade.name = seed.grade_name
ON CONFLICT DO NOTHING;

-- Link the one seeded employee login (FR-2.7).
UPDATE employees
SET user_id = (SELECT id FROM users WHERE email = 'asha.menon@acme.test')
WHERE employee_code = 'E-1001' AND user_id IS NULL;

------------------------------------------------------------------------------------------
-- Salary structures. Revision 5001 is superseded by 5002 on the same date the successor
-- takes effect, which is how a raise is recorded (ADR-009) — employee 1001 therefore has
-- a two-entry history rather than a rewritten package.
------------------------------------------------------------------------------------------
INSERT INTO salary_structures (
    id, employee_id, effective_from, superseded_on, override_reason, created_by, created_at)
SELECT
    seed.id,
    seed.employee_id,
    seed.effective_from,
    seed.superseded_on,
    NULL,
    author.id,
    TIMESTAMPTZ '2026-09-01 00:00:00+00'
FROM (VALUES
    (5001, 1001, DATE '2022-06-01', DATE '2024-04-01'),   -- superseded by the raise below
    (5002, 1001, DATE '2024-04-01', NULL),
    (5003, 1002, DATE '2023-01-16', NULL),
    (5004, 1003, DATE '2024-07-01', NULL),
    (5005, 1004, DATE '2021-03-15', NULL),
    (5006, 1005, DATE '2023-09-01', NULL),
    (5007, 1006, DATE '2025-02-03', NULL),
    (5008, 1007, DATE '2022-11-21', NULL),
    (5009, 1008, DATE '2024-04-08', NULL),
    (5010, 1009, DATE '2025-06-16', NULL),
    (5011, 1012, DATE '2020-05-04', NULL)                 -- the leaver's last package
) AS seed(id, employee_id, effective_from, superseded_on)
CROSS JOIN (SELECT id FROM users WHERE email = 'hr@acme.test') author
ON CONFLICT DO NOTHING;

------------------------------------------------------------------------------------------
-- Structure lines. Earnings are flat monthly amounts; PF is a percentage of basic, so the
-- deduction follows the basic — including down, when a payroll run prorates it (FR-5.4).
--
-- Monthly gross per grade: G1 50,000 · G2 90,000 · G3 150,000 · G4 250,000.
------------------------------------------------------------------------------------------
INSERT INTO salary_structure_components (structure_id, component_id, value)
SELECT seed.structure_id, component.id, seed.value
FROM (VALUES
    -- 1001, first package: G2-level, 90,000 gross
    (5001, 'BASIC', 45000.00), (5001, 'HRA', 18000.00), (5001, 'CONVEYANCE', 2000.00),
    (5001, 'SPECIAL', 25000.00), (5001, 'PF', 12.00), (5001, 'PROF_TAX', 200.00),
    -- 1001, after the raise: G3-level, 150,000 gross
    (5002, 'BASIC', 75000.00), (5002, 'HRA', 30000.00), (5002, 'CONVEYANCE', 2000.00),
    (5002, 'SPECIAL', 43000.00), (5002, 'PF', 12.00), (5002, 'PROF_TAX', 200.00),
    -- 1002, G2
    (5003, 'BASIC', 45000.00), (5003, 'HRA', 18000.00), (5003, 'CONVEYANCE', 2000.00),
    (5003, 'SPECIAL', 25000.00), (5003, 'PF', 12.00), (5003, 'PROF_TAX', 200.00),
    -- 1003, G2
    (5004, 'BASIC', 45000.00), (5004, 'HRA', 18000.00), (5004, 'CONVEYANCE', 2000.00),
    (5004, 'SPECIAL', 25000.00), (5004, 'PF', 12.00), (5004, 'PROF_TAX', 200.00),
    -- 1004, G4
    (5005, 'BASIC', 125000.00), (5005, 'HRA', 50000.00), (5005, 'CONVEYANCE', 2000.00),
    (5005, 'SPECIAL', 73000.00), (5005, 'PF', 12.00), (5005, 'PROF_TAX', 200.00),
    -- 1005, G2
    (5006, 'BASIC', 45000.00), (5006, 'HRA', 18000.00), (5006, 'CONVEYANCE', 2000.00),
    (5006, 'SPECIAL', 25000.00), (5006, 'PF', 12.00), (5006, 'PROF_TAX', 200.00),
    -- 1006, G1
    (5007, 'BASIC', 25000.00), (5007, 'HRA', 10000.00), (5007, 'CONVEYANCE', 2000.00),
    (5007, 'SPECIAL', 13000.00), (5007, 'PF', 12.00), (5007, 'PROF_TAX', 200.00),
    -- 1007, G2
    (5008, 'BASIC', 45000.00), (5008, 'HRA', 18000.00), (5008, 'CONVEYANCE', 2000.00),
    (5008, 'SPECIAL', 25000.00), (5008, 'PF', 12.00), (5008, 'PROF_TAX', 200.00),
    -- 1008, G1
    (5009, 'BASIC', 25000.00), (5009, 'HRA', 10000.00), (5009, 'CONVEYANCE', 2000.00),
    (5009, 'SPECIAL', 13000.00), (5009, 'PF', 12.00), (5009, 'PROF_TAX', 200.00),
    -- 1009, G2
    (5010, 'BASIC', 45000.00), (5010, 'HRA', 18000.00), (5010, 'CONVEYANCE', 2000.00),
    (5010, 'SPECIAL', 25000.00), (5010, 'PF', 12.00), (5010, 'PROF_TAX', 200.00),
    -- 1012, the leaver, G2
    (5011, 'BASIC', 45000.00), (5011, 'HRA', 18000.00), (5011, 'CONVEYANCE', 2000.00),
    (5011, 'SPECIAL', 25000.00), (5011, 'PF', 12.00), (5011, 'PROF_TAX', 200.00)
) AS seed(structure_id, component_code, value)
JOIN salary_components component ON component.code = seed.component_code
ON CONFLICT DO NOTHING;

------------------------------------------------------------------------------------------
-- Advance the identity sequences past the explicit ids above. Without this the next
-- employee created through the API would be handed id 1, then 2, and eventually collide
-- with the seeded rows.
------------------------------------------------------------------------------------------
SELECT setval(
    pg_get_serial_sequence('employees', 'id'),
    (SELECT GREATEST(MAX(id), 1) FROM employees),
    true);

SELECT setval(
    pg_get_serial_sequence('salary_structures', 'id'),
    (SELECT GREATEST(MAX(id), 1) FROM salary_structures),
    true);

SELECT setval(
    pg_get_serial_sequence('salary_structure_components', 'id'),
    (SELECT GREATEST(MAX(id), 1) FROM salary_structure_components),
    true);

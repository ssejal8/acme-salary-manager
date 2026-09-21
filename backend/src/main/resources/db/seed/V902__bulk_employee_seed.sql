------------------------------------------------------------------------------------------
-- Bulk development seed: takes the dataset to 10,000 employees.
--
-- The organisation in the problem statement has 10,000 people, and almost every decision
-- in this system — paging in the database, the sort whitelist, aggregate reporting, the
-- cost of a payroll run — only becomes visible at that size. Twelve employees cannot show
-- whether the employee list pages properly; ten thousand can.
--
-- WHY THIS IS GENERATED RATHER THAN A DUMP
--
-- 10,000 literal INSERT rows would be several megabytes of unreadable SQL that nobody can
-- review and no reviewer can diff. This derives every value from the series index instead,
-- so the whole dataset is about a hundred readable lines. The cost is that you must read
-- the formulas to know what the data looks like, which is what the comments are for.
--
-- DETERMINISTIC, NOT RANDOM
--
-- There is no random() anywhere. Every name, date and amount is a pure function of the
-- row number, so employee E-5000 is the same person with the same salary on every machine
-- and after every reset. That is what lets the figures below be asserted rather than
-- described, and it is the same property V901 buys by using literals.
--
-- WHAT IT PRODUCES (exact, because the inputs are fixed)
--
--   9,988 generated employees, E-1013 to E-11000, on top of V901's curated twelve
--     102 leavers            — every 97th row, INACTIVE with an exit date
--      39 without a package  — every 250th row, the coverage gap analytics reports
--   9,949 salary structures  — one current revision each, no raise history
--  59,694 structure lines    — six per package
--
--   Grade mix, a pyramid rather than a flat split: 45% G1, 35% G2, 15% G3, 5% G4.
--   Department mix: 50% Engineering, 20% Finance, 10% HR, 20% Sales, with designations
--   that fit the department and seniority that fits the grade — a G4 engineer is a
--   manager, not a junior.
--
--   Monthly gross runs from 37,000 to 321,000 over 243 distinct values, so the median and
--   the average are genuinely different numbers and the distribution statistics on the
--   compensation dashboard have something real to report.
--
-- EVERY PACKAGE WOULD HAVE BEEN ACCEPTED BY THE API
--
-- Salaries are derived from the employee's own grade band, spread across the middle 80%
-- of it, so annual CTC (gross x 12) always sits inside [min_ctc, max_ctc] and FR-4.3
-- would never have demanded an override. Basic is positive (FR-4.2) and deductions never
-- approach gross. A demo dataset the application itself would reject is a trap for
-- whoever reads it next, so the block at the bottom of this file proves these properties
-- and fails the migration loudly if a future edit breaks one.
--
-- ONE CONSEQUENCE TO KNOW ABOUT
--
-- A payroll run over this dataset computes close to 9,900 payslips in a single transaction
-- (ADR-011). NFR-1.3 budgets 60 seconds for 1,000 employees, so this seed is exactly what
-- makes that budget worth revisiting — and the run review screen renders one row per
-- payslip, which wants paging before anyone runs a real month here. Both are known and
-- recorded; the seed is not pretending otherwise.
--
-- Loaded only under the dev profile, like every file in this location, so none of it can
-- reach a deployed schema (ADR-007).
------------------------------------------------------------------------------------------

------------------------------------------------------------------------------------------
-- Employees.
--
-- Reference data is resolved by natural key, never by an assumed id, so this stays
-- correct however V900 was applied. Ids are explicit (1012 + row) because the structures
-- below have to reference them without a round trip; the sequences are advanced at the
-- end so an employee created through the API later cannot collide with one of these.
------------------------------------------------------------------------------------------
WITH shaped AS (
    SELECT
        i,
        1012 + i AS employee_id,
        -- Joining dates spread across eleven and a half years, every one of the 4,200
        -- days used exactly once: the multiplier is coprime with the modulus, so the
        -- dates cycle through the whole window instead of clustering. The earliest is
        -- 1 January 2015 and the latest 1 July 2026 — before the periods a demo runs
        -- payroll for, so nobody is excluded for having joined too late.
        DATE '2015-01-01' + ((i * 37) % 4200) AS date_of_joining,
        CASE
            WHEN i % 20 < 9  THEN 'G1'
            WHEN i % 20 < 16 THEN 'G2'
            WHEN i % 20 < 19 THEN 'G3'
            ELSE 'G4'
        END AS grade_name,
        CASE
            WHEN i % 10 < 5 THEN 'ENG'
            WHEN i % 10 < 7 THEN 'FIN'
            WHEN i % 10 = 7 THEN 'HR'
            ELSE 'SAL'
        END AS department_code
    FROM generate_series(1, 9988) AS i
),
staffed AS (
    SELECT
        shaped.*,
        -- Seniority follows the grade, so the titles are not nonsense next to the money.
        CASE shaped.department_code
            WHEN 'ENG' THEN CASE shaped.grade_name
                WHEN 'G4' THEN 'Engineering Manager'
                WHEN 'G3' THEN 'Senior Software Engineer'
                ELSE 'Software Engineer'
            END
            WHEN 'FIN' THEN 'Finance Analyst'
            WHEN 'HR'  THEN 'HR Executive'
            ELSE 'Sales Executive'
        END AS designation_title,
        (ARRAY[
            'Aarav', 'Aditi', 'Ananya', 'Arjun', 'Bhavna', 'Chetan', 'Deepa', 'Dhruv',
            'Farhan', 'Gauri', 'Harsh', 'Ishita', 'Jatin', 'Kavya', 'Lakshmi', 'Manish',
            'Nikhil', 'Pooja', 'Rohit', 'Sanjana', 'Tarun', 'Uma', 'Varun', 'Zoya'
        ])[1 + (shaped.i * 13) % 24] AS first_name,
        (ARRAY[
            'Agarwal', 'Bhatt', 'Chopra', 'Deshpande', 'Gupta', 'Iyer', 'Joshi', 'Kulkarni',
            'Malhotra', 'Nair', 'Patel', 'Rao', 'Reddy', 'Sharma', 'Shetty', 'Singh',
            'Thakur', 'Varma', 'Verma', 'Yadav'
        ])[1 + (shaped.i * 7) % 20] AS last_name,
        -- Every 97th person has left. Their package stays on record (ADR-014): payroll
        -- includes them for the month they left and excludes them afterwards (FR-2.6).
        i % 97 = 0 AS has_left
    FROM shaped
)
INSERT INTO employees (
    id, employee_code, first_name, last_name, work_email,
    date_of_joining, exit_date, status,
    department_id, designation_id, grade_id, created_at, updated_at)
SELECT
    staffed.employee_id,
    'E-' || staffed.employee_id,
    staffed.first_name,
    staffed.last_name,
    -- The employee id is part of the address because two of the generated people can
    -- share a name, and work_email is unique.
    lower(staffed.first_name || '.' || staffed.last_name || '.' || staffed.employee_id
          || '@acme.test'),
    staffed.date_of_joining,
    -- Capped at the end of August 2026 and never before the joining date, which is what
    -- ck_employees_exit_after_joining requires of a late joiner.
    CASE WHEN staffed.has_left THEN
        GREATEST(staffed.date_of_joining, LEAST(staffed.date_of_joining + 900, DATE '2026-08-31'))
    END,
    CASE WHEN staffed.has_left THEN 'INACTIVE' ELSE 'ACTIVE' END,
    department.id,
    designation.id,
    grade.id,
    TIMESTAMPTZ '2026-09-01 00:00:00+00',
    TIMESTAMPTZ '2026-09-01 00:00:00+00'
FROM staffed
JOIN departments department ON department.code = staffed.department_code
JOIN designations designation ON designation.title = staffed.designation_title
JOIN grades grade ON grade.name = staffed.grade_name
ON CONFLICT DO NOTHING;

------------------------------------------------------------------------------------------
-- Salary structures: one current revision per employee, effective from their joining
-- date. No raise history here — demonstrating a superseded revision is V901's job, where
-- it can be read at a glance rather than inferred from a formula.
--
-- Every 250th employee is skipped, which is the coverage gap compensation analytics
-- reports as employeesWithoutPackage and a payroll run passes over (FR-5.2).
------------------------------------------------------------------------------------------
INSERT INTO salary_structures (
    id, employee_id, effective_from, superseded_on, override_reason, created_by, created_at)
SELECT
    20000 + (employee.id - 1012),
    employee.id,
    employee.date_of_joining,
    NULL,
    -- No override: the salaries below are inside their grade bands by construction.
    NULL,
    author.id,
    TIMESTAMPTZ '2026-09-01 00:00:00+00'
FROM employees employee
CROSS JOIN (SELECT id FROM users WHERE email = 'hr@acme.test') author
WHERE employee.id BETWEEN 1013 AND 11000
  AND (employee.id - 1012) % 250 <> 0
ON CONFLICT DO NOTHING;

------------------------------------------------------------------------------------------
-- Structure lines.
--
-- Monthly gross is placed inside the employee's own grade band: the bottom 10% and top
-- 10% of the band are left clear, and the position within the remaining 80% comes from a
-- coprime multiple of the row number, so salaries spread across the band instead of every
-- G2 earning the same. Rounded to the nearest 1,000, which is both how salaries are
-- actually set and small enough that the rounding cannot leave the band.
--
-- The package is then split conventionally: basic is half of gross, HRA is 40% of basic,
-- conveyance is the usual flat 2,000, and the special allowance takes the remainder — so
-- the lines sum to gross exactly, however the halves rounded. PF is a percentage of basic
-- and professional tax a flat statutory 200 (ADR-016).
------------------------------------------------------------------------------------------
WITH priced AS (
    SELECT
        structure.id AS structure_id,
        gross.amount AS monthly_gross,
        round(gross.amount * 0.50, -2) AS basic
    FROM salary_structures structure
    JOIN employees employee ON employee.id = structure.employee_id
    JOIN grades grade ON grade.id = employee.grade_id
    CROSS JOIN LATERAL (
        SELECT round(
            grade.min_ctc / 12
                + (grade.max_ctc - grade.min_ctc) / 12 * 0.10
                + (grade.max_ctc - grade.min_ctc) / 12 * 0.80
                    * ((((employee.id - 1012) * 7919) % 1009)::numeric / 1009),
            -3) AS amount
    ) gross
    WHERE structure.id BETWEEN 20001 AND 29988
),
package AS (
    SELECT
        structure_id,
        monthly_gross,
        basic,
        round(basic * 0.40, -2) AS hra
    FROM priced
)
INSERT INTO salary_structure_components (structure_id, component_id, value)
SELECT package.structure_id, component.id, line.value
FROM package
CROSS JOIN LATERAL (VALUES
    ('BASIC',      package.basic),
    ('HRA',        package.hra),
    ('CONVEYANCE', 2000.00),
    -- The balancer: whatever is left of gross once the fixed parts are taken.
    ('SPECIAL',    package.monthly_gross - package.basic - package.hra - 2000.00),
    ('PF',         12.00),
    ('PROF_TAX',   200.00)
) AS line(code, value)
JOIN salary_components component ON component.code = line.code
ON CONFLICT DO NOTHING;

------------------------------------------------------------------------------------------
-- Hand the sequences back.
--
-- The rows above carry explicit ids, and an identity sequence does not advance for those.
-- Without this, the first employee created through the API would be handed id 1 and, as
-- the sequence climbed, would eventually collide with a seeded row. Idempotent, and
-- correct whatever the highest id happens to be.
------------------------------------------------------------------------------------------
SELECT setval(pg_get_serial_sequence('employees', 'id'),
              (SELECT max(id) FROM employees));
SELECT setval(pg_get_serial_sequence('salary_structures', 'id'),
              (SELECT max(id) FROM salary_structures));
SELECT setval(pg_get_serial_sequence('salary_structure_components', 'id'),
              (SELECT max(id) FROM salary_structure_components));

------------------------------------------------------------------------------------------
-- Prove it.
--
-- The claims in the header are only worth making if something enforces them. This runs
-- inside the same migration, so a seed that produced the wrong number of employees, or a
-- package the API would have rejected, fails startup with a message naming the problem —
-- rather than leaving a subtly wrong dataset for someone to discover from a demo.
------------------------------------------------------------------------------------------
DO $$
DECLARE
    total_employees      bigint;
    generated_employees  bigint;
    leavers              bigint;
    without_package      bigint;
    out_of_band          bigint;
    missing_basic        bigint;
    non_positive_basic   bigint;
    deductions_too_high  bigint;
BEGIN
    ------------------------------------------------------------------------------------
    -- Cost every package in the database once, the way SalaryStructureCalculator does:
    -- a flat line is its own value, a percentage line is that percentage of the basic,
    -- and each is rounded before being summed (ADR-006, NFR-3.3). Materialised into a
    -- temporary table because three separate checks read it, and because costing it
    -- inline would mean nesting an aggregate inside an aggregate.
    ------------------------------------------------------------------------------------
    CREATE TEMP TABLE seed_costed AS
    WITH basics AS (
        SELECT line.structure_id, line.value AS basic
          FROM salary_structure_components line
          JOIN salary_components component ON component.id = line.component_id
         WHERE component.code = 'BASIC'
    )
    SELECT
        structure.id AS structure_id,
        grade.min_ctc,
        grade.max_ctc,
        basics.basic,
        COALESCE(sum(
            CASE WHEN component.calculation_type = 'PERCENT_OF_BASIC'
                 THEN round(line.value / 100 * basics.basic, 2)
                 ELSE line.value
            END) FILTER (WHERE component.type = 'EARNING'), 0) AS gross,
        COALESCE(sum(
            CASE WHEN component.calculation_type = 'PERCENT_OF_BASIC'
                 THEN round(line.value / 100 * basics.basic, 2)
                 ELSE line.value
            END) FILTER (WHERE component.type = 'DEDUCTION'), 0) AS deductions
      FROM salary_structures structure
      JOIN employees employee ON employee.id = structure.employee_id
      JOIN grades grade ON grade.id = employee.grade_id
      JOIN salary_structure_components line ON line.structure_id = structure.id
      JOIN salary_components component ON component.id = line.component_id
      JOIN basics ON basics.structure_id = structure.id
     GROUP BY structure.id, grade.min_ctc, grade.max_ctc, basics.basic;

    SELECT count(*) INTO total_employees FROM employees;
    SELECT count(*) INTO generated_employees
      FROM employees WHERE id BETWEEN 1013 AND 11000;
    SELECT count(*) INTO leavers
      FROM employees WHERE id BETWEEN 1013 AND 11000 AND status = 'INACTIVE';
    SELECT count(*) INTO without_package
      FROM employees employee
     WHERE NOT EXISTS (
        SELECT 1 FROM salary_structures s WHERE s.employee_id = employee.id);

    IF total_employees <> 10000 THEN
        RAISE EXCEPTION 'bulk seed: expected 10000 employees, found %', total_employees;
    END IF;
    IF generated_employees <> 9988 THEN
        RAISE EXCEPTION 'bulk seed: expected 9988 generated employees, found %',
            generated_employees;
    END IF;
    IF leavers <> 102 THEN
        RAISE EXCEPTION 'bulk seed: expected 102 generated leavers, found %', leavers;
    END IF;
    -- 39 generated gaps plus E-1010 and E-1011 from the curated seed.
    IF without_package <> 41 THEN
        RAISE EXCEPTION 'bulk seed: expected 41 employees without a package, found %',
            without_package;
    END IF;

    -- FR-4.2: every package needs a basic, and it must be positive. The absence is
    -- checked against the structures themselves rather than against the costing above,
    -- which joins on the basic line and would therefore skip a package that had none.
    SELECT count(*) INTO missing_basic
      FROM salary_structures structure
     WHERE NOT EXISTS (
        SELECT 1
          FROM salary_structure_components line
          JOIN salary_components component ON component.id = line.component_id
         WHERE line.structure_id = structure.id AND component.code = 'BASIC');
    IF missing_basic > 0 THEN
        RAISE EXCEPTION 'bulk seed: % packages have no basic line at all', missing_basic;
    END IF;

    SELECT count(*) INTO non_positive_basic FROM seed_costed WHERE basic <= 0;
    IF non_positive_basic > 0 THEN
        RAISE EXCEPTION 'bulk seed: % packages have a non-positive basic', non_positive_basic;
    END IF;

    -- FR-4.3: annual CTC is twelve times gross and must sit inside the grade band, or the
    -- API would have demanded an override reason. An absent bound means unbounded on that
    -- side, not zero.
    SELECT count(*) INTO out_of_band
      FROM seed_costed
     WHERE (min_ctc IS NOT NULL AND 12 * gross < min_ctc)
        OR (max_ctc IS NOT NULL AND 12 * gross > max_ctc);
    IF out_of_band > 0 THEN
        RAISE EXCEPTION 'bulk seed: % packages fall outside their grade CTC band', out_of_band;
    END IF;

    -- FR-4.4: deductions may not exceed gross.
    SELECT count(*) INTO deductions_too_high FROM seed_costed WHERE deductions > gross;
    IF deductions_too_high > 0 THEN
        RAISE EXCEPTION 'bulk seed: % packages deduct more than they pay',
            deductions_too_high;
    END IF;

    DROP TABLE seed_costed;

    RAISE INFO 'bulk seed verified: % employees, % leavers, % without a package',
        total_employees, leavers, without_package;
END $$;

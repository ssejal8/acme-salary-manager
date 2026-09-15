-- The work email doubles as the login username (FR-2.7), and `users.email` already
-- carries a lowercase constraint. Without the same rule here, `A@acme.test` and
-- `a@acme.test` would pass the unique index as two different employees and then collide
-- when their logins are provisioned.
--
-- The Employee entity lowercases on the way in; this makes the database agree, so a bulk
-- import or a direct SQL fix cannot reintroduce the case variant. Added as V2 rather than
-- folded into V1 because applied migrations are never edited (ADR-007).

UPDATE employees SET work_email = lower(work_email) WHERE work_email <> lower(work_email);

ALTER TABLE employees
    ADD CONSTRAINT ck_employees_work_email_lowercase CHECK (work_email = lower(work_email));

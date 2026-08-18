-- P4-10 (D41): recurrence exceptions (AF-11). A future, not-yet-generated
-- run-date of a recurring rule may be skipped: the 6 am generator skips
-- creating that instance but still advances next_run_at (the rule stays alive).

CREATE TABLE recurring_task_rule_exceptions (
    id             UUID        PRIMARY KEY,
    rule_id        UUID        NOT NULL REFERENCES recurring_task_rules (id) ON DELETE CASCADE,
    exception_date DATE        NOT NULL,
    created_by     UUID        REFERENCES app_users (id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_rule_exception_date UNIQUE (rule_id, exception_date)
);

CREATE INDEX idx_rule_exceptions_rule ON recurring_task_rule_exceptions (rule_id);

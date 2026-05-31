-- LVA Ticket System — SQLite schema
-- Project key prefix: LVA  (operator directive, §6.L 68th invocation, 2026-05-31)
-- Satisfies HelixConstitution gates:
--   §11.4.15 CM-ITEM-STATUS-TRACKING   (status closed-set vocab)
--   §11.4.16 CM-ITEM-TYPE-TRACKING     (type closed-set vocab)
--   §11.4.19 CM-ITEM-SUMMARY-TABLES    (Issues_Summary / Fixed_Summary views)
--   §11.4.21 CM-ITEM-ID-KEYED          (LVA-N stable key)
--   §11.4.33 CM-CLOSURE-STATUS-VOCAB   (type-aware closure status)
--   §11.4.34 CM-REOPENED-SOURCE-ATTR   (reopened WHY/WHO/WHEN/WHICH-INCIDENT)
--
-- No sudo, no external service. Built with the system `sqlite3` binary only.
-- Apply:  sqlite3 docs/tickets/tickets.db < docs/tickets/schema.sql
-- The .db binary is a GENERATED artifact (main agent decides gitignore vs. commit).

PRAGMA foreign_keys = ON;

-- ---------------------------------------------------------------------------
-- Closed-set vocabularies (§11.4.15 / §11.4.16 / §11.4.33)
-- Stored as lookup tables so CHECK/FK constraints enforce the closed sets and
-- the vocab is auditable + extensible without code edits.
-- ---------------------------------------------------------------------------

-- §11.4.16 Item Type closed set
CREATE TABLE IF NOT EXISTS item_type (
    name        TEXT PRIMARY KEY,
    description TEXT NOT NULL
);
INSERT OR IGNORE INTO item_type (name, description) VALUES
    ('Bug',         'Defect: product behaves incorrectly for a user'),
    ('Feature',     'New user-visible capability'),
    ('Task',        'Engineering work item with no direct user-visible surface'),
    ('Chore',       'Maintenance / housekeeping (deps, docs, refactor)'),
    ('Incident',    'Sixth/Seventh-Law forensic anchor or §6.O Crashlytics issue'),
    ('Debt',        'Tracked constitutional / technical debt item');

-- §11.4.15 Open/active Status closed set (lifecycle states before closure)
CREATE TABLE IF NOT EXISTS item_status (
    name        TEXT PRIMARY KEY,
    is_terminal INTEGER NOT NULL DEFAULT 0,  -- 1 = a closure state
    description TEXT NOT NULL
);
INSERT OR IGNORE INTO item_status (name, is_terminal, description) VALUES
    ('Open',            0, 'Reported / triaged, not yet started'),
    ('InProgress',      0, 'Actively being worked'),
    ('Blocked',         0, 'Cannot proceed (see operator_blocked_details)'),
    ('OperatorBlocked', 0, 'Awaiting an operator decision/input/credential'),
    ('InReview',        0, 'Fix/work landed, under review or verification'),
    ('Closed',          1, 'Terminal — see closure_status for the type-aware verb'),
    ('Reopened',        0, 'Was Closed, re-opened (see reopened_* attribution)');

-- §11.4.33 Type-aware closure-status closed set.
-- A closure_status is only valid for the item's type; the mapping table below
-- (closure_status_for_type) enforces the §11.4.33 pairing.
CREATE TABLE IF NOT EXISTS closure_status (
    name        TEXT PRIMARY KEY,
    description TEXT NOT NULL
);
INSERT OR IGNORE INTO closure_status (name, description) VALUES
    ('Fixed',        'Bug closure: root cause addressed + regression test exists'),
    ('Implemented',  'Feature closure: capability shipped + Challenge test exists'),
    ('Completed',    'Task/Chore closure: work done'),
    ('Resolved',     'Incident closure: forensic anchor + coverage in place'),
    ('Closed',       'Debt closure: debt discharged / equivalence-mapped'),
    ('WontFix',      'Closed without action (rationale mandatory)'),
    ('Duplicate',    'Closed as duplicate of another LVA-N (cite duplicate_of)'),
    ('NotReproducible','Closed: could not reproduce (Sixth-Law clause 4 caveat applies)');

-- §11.4.33 valid (type, closure_status) pairs. The trigger below rejects
-- a closure whose verb does not match the item's type.
CREATE TABLE IF NOT EXISTS closure_status_for_type (
    type           TEXT NOT NULL REFERENCES item_type(name),
    closure_status TEXT NOT NULL REFERENCES closure_status(name),
    PRIMARY KEY (type, closure_status)
);
INSERT OR IGNORE INTO closure_status_for_type (type, closure_status) VALUES
    ('Bug','Fixed'),     ('Bug','WontFix'),     ('Bug','Duplicate'),     ('Bug','NotReproducible'),
    ('Feature','Implemented'), ('Feature','WontFix'), ('Feature','Duplicate'),
    ('Task','Completed'), ('Task','WontFix'),    ('Task','Duplicate'),
    ('Chore','Completed'),('Chore','WontFix'),   ('Chore','Duplicate'),
    ('Incident','Resolved'), ('Incident','Duplicate'), ('Incident','NotReproducible'),
    ('Debt','Closed'),   ('Debt','WontFix'),     ('Debt','Duplicate');

-- ---------------------------------------------------------------------------
-- Core tickets table (§11.4.21 LVA-N keyed)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tickets (
    -- §11.4.21 stable project-prefixed key. seq is the monotonic integer;
    -- id is the rendered key "LVA-<seq>". A trigger keeps id == 'LVA-' || seq.
    seq             INTEGER PRIMARY KEY AUTOINCREMENT,
    id              TEXT UNIQUE,                       -- 'LVA-1', 'LVA-2', ...

    title           TEXT NOT NULL,
    body            TEXT NOT NULL DEFAULT '',          -- markdown description

    type            TEXT NOT NULL REFERENCES item_type(name),     -- §11.4.16
    status          TEXT NOT NULL DEFAULT 'Open'
                        REFERENCES item_status(name),             -- §11.4.15
    closure_status  TEXT REFERENCES closure_status(name),         -- §11.4.33; NULL unless terminal

    priority        TEXT NOT NULL DEFAULT 'P2'
                        CHECK (priority IN ('P0','P1','P2','P3')),

    -- Provenance (§11.4.34 reopened-source is a subset of general source tracking)
    source          TEXT NOT NULL DEFAULT 'self-discovered'
                        CHECK (source IN ('operator-report','crashlytics','self-discovered',
                                          'bluff-hunt','code-review','user-report','automated-gate')),
    source_ref      TEXT,                              -- e.g. Crashlytics issue id, commit SHA, incident JSON path

    -- §11.4.34 reopened-source attribution. Required (NOT NULL) only when
    -- status='Reopened'; enforced by the trg_reopen_attribution trigger.
    reopened_why        TEXT,                          -- WHY it was re-opened
    reopened_who        TEXT,                          -- WHO re-opened it
    reopened_when       TEXT,                          -- WHEN (ISO-8601 UTC)
    reopened_incident   TEXT,                          -- WHICH-INCIDENT (LVA-N or incident JSON path)

    -- §6.O / §6.T.4 closure-evidence links (the Lava equivalence layer)
    fix_commit_sha      TEXT,                          -- commit that closed it
    validation_test     TEXT,                          -- path to unit/integration regression test
    challenge_test      TEXT,                          -- path to Compose UI / e2e Challenge test
    closure_log         TEXT,                          -- .lava-ci-evidence/crashlytics-resolved/<...> path

    -- Operator-blocked detail (HelixConstitution operator-blocked-details gate)
    operator_blocked_details TEXT,                     -- required when status='OperatorBlocked'

    duplicate_of    TEXT,                              -- LVA-N when closure_status='Duplicate'

    created_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
    updated_at      TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%SZ','now')),
    closed_at       TEXT                               -- set when status becomes terminal
);

-- Render the LVA-N id after insert (§11.4.21).
CREATE TRIGGER IF NOT EXISTS trg_ticket_id_after_insert
AFTER INSERT ON tickets WHEN NEW.id IS NULL
BEGIN
    UPDATE tickets SET id = 'LVA-' || NEW.seq WHERE seq = NEW.seq;
END;

-- Keep updated_at fresh on any change.
CREATE TRIGGER IF NOT EXISTS trg_ticket_touch
AFTER UPDATE ON tickets
BEGIN
    UPDATE tickets SET updated_at = strftime('%Y-%m-%dT%H:%M:%SZ','now')
    WHERE seq = NEW.seq AND OLD.updated_at = NEW.updated_at;
END;

-- §11.4.33: when an item is Closed, closure_status MUST be set AND MUST be
-- valid for the item's type. Rejects otherwise.
CREATE TRIGGER IF NOT EXISTS trg_closure_status_typeaware
BEFORE UPDATE OF status, closure_status ON tickets
WHEN NEW.status = 'Closed'
BEGIN
    SELECT RAISE(ABORT, '§11.4.33: Closed ticket needs a type-valid closure_status')
    WHERE NEW.closure_status IS NULL
       OR NOT EXISTS (
            SELECT 1 FROM closure_status_for_type
            WHERE type = NEW.type AND closure_status = NEW.closure_status);
END;

-- §11.4.34: a Reopened ticket MUST carry full reopened_* attribution.
CREATE TRIGGER IF NOT EXISTS trg_reopen_attribution
BEFORE UPDATE OF status ON tickets
WHEN NEW.status = 'Reopened'
BEGIN
    SELECT RAISE(ABORT, '§11.4.34: Reopen needs reopened_why/who/when/incident')
    WHERE NEW.reopened_why IS NULL OR NEW.reopened_why = ''
       OR NEW.reopened_who IS NULL OR NEW.reopened_who = ''
       OR NEW.reopened_when IS NULL OR NEW.reopened_when = ''
       OR NEW.reopened_incident IS NULL OR NEW.reopened_incident = '';
END;

-- Operator-blocked items MUST explain the block.
CREATE TRIGGER IF NOT EXISTS trg_operator_blocked_details
BEFORE UPDATE OF status ON tickets
WHEN NEW.status = 'OperatorBlocked'
BEGIN
    SELECT RAISE(ABORT, 'OperatorBlocked needs operator_blocked_details')
    WHERE NEW.operator_blocked_details IS NULL OR NEW.operator_blocked_details = '';
END;

-- Stamp closed_at when entering a terminal status.
CREATE TRIGGER IF NOT EXISTS trg_closed_at
AFTER UPDATE OF status ON tickets
WHEN NEW.status = 'Closed' AND NEW.closed_at IS NULL
BEGIN
    UPDATE tickets SET closed_at = strftime('%Y-%m-%dT%H:%M:%SZ','now') WHERE seq = NEW.seq;
END;

CREATE INDEX IF NOT EXISTS idx_tickets_status ON tickets(status);
CREATE INDEX IF NOT EXISTS idx_tickets_type   ON tickets(type);

-- ---------------------------------------------------------------------------
-- §11.4.19 summary aggregate VIEWS — used by the doc generators.
-- ---------------------------------------------------------------------------

-- "Issues" = every non-closed ticket of any type.
CREATE VIEW IF NOT EXISTS v_issues AS
    SELECT id, type, status, priority, title, source, source_ref, created_at, updated_at
    FROM tickets
    WHERE status NOT IN ('Closed')
    ORDER BY priority, seq;

-- "Fixed" = every closed ticket (closure_status carries the type-aware verb).
CREATE VIEW IF NOT EXISTS v_fixed AS
    SELECT id, type, closure_status, priority, title,
           fix_commit_sha, validation_test, challenge_test, closure_log,
           created_at, closed_at
    FROM tickets
    WHERE status = 'Closed'
    ORDER BY closed_at DESC, seq;

-- Issues_Summary: open counts by type x status.
CREATE VIEW IF NOT EXISTS v_issues_summary AS
    SELECT type, status, COUNT(*) AS n
    FROM tickets WHERE status NOT IN ('Closed')
    GROUP BY type, status ORDER BY type, status;

-- Fixed_Summary: closed counts by type x closure_status.
CREATE VIEW IF NOT EXISTS v_fixed_summary AS
    SELECT type, closure_status, COUNT(*) AS n
    FROM tickets WHERE status = 'Closed'
    GROUP BY type, closure_status ORDER BY type, closure_status;

-- UEMS database schema (PostgreSQL 15+). Executed by uems.tools.DbSetup.
-- All timestamps are TIMESTAMPTZ and stored in UTC (BR-13).

CREATE TABLE schema_version (version INT NOT NULL);
INSERT INTO schema_version VALUES (1);

CREATE TABLE app_setting (
    setting_key   VARCHAR(60) PRIMARY KEY,
    setting_value VARCHAR(200) NOT NULL
);

CREATE TABLE app_user (
    user_id              BIGSERIAL PRIMARY KEY,
    university_id        VARCHAR(30)  NOT NULL UNIQUE,
    full_name_en         VARCHAR(120) NOT NULL,
    full_name_ar         VARCHAR(120),
    email                VARCHAR(160) NOT NULL,
    account_type         VARCHAR(10)  NOT NULL CHECK (account_type IN ('STUDENT','STAFF','ADMIN')),
    faculty              VARCHAR(120),
    language             VARCHAR(2)   NOT NULL DEFAULT 'en',
    password_hash        VARCHAR(300) NOT NULL,
    must_change_password BOOLEAN      NOT NULL DEFAULT FALSE,
    status               VARCHAR(12)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED','ANONYMIZED')),
    failed_attempts      INT          NOT NULL DEFAULT 0,
    locked_until         TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE category (
    category_id BIGSERIAL PRIMARY KEY,
    name_en     VARCHAR(80) NOT NULL UNIQUE,
    name_ar     VARCHAR(80)
);
CREATE TABLE tag (
    tag_id  BIGSERIAL PRIMARY KEY,
    name_en VARCHAR(60) NOT NULL UNIQUE,
    name_ar VARCHAR(60)
);
CREATE TABLE user_interest (
    user_id BIGINT NOT NULL REFERENCES app_user(user_id),
    tag_id  BIGINT NOT NULL REFERENCES tag(tag_id),
    PRIMARY KEY (user_id, tag_id)
);

CREATE TABLE venue (
    venue_id    BIGSERIAL PRIMARY KEY,
    name_en     VARCHAR(120) NOT NULL,
    name_ar     VARCHAR(120),
    building    VARCHAR(80),
    room        VARCHAR(40),
    capacity    INT NOT NULL CHECK (capacity > 0),
    facilities  VARCHAR(400),
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    version     INT NOT NULL DEFAULT 0
);

CREATE TABLE club (
    club_id        BIGSERIAL PRIMARY KEY,
    name_en        VARCHAR(120) NOT NULL,
    name_ar        VARCHAR(120),
    description_en VARCHAR(2000),
    description_ar VARCHAR(2000),
    category_id    BIGINT REFERENCES category(category_id),
    active         BOOLEAN NOT NULL DEFAULT TRUE,
    version        INT NOT NULL DEFAULT 0
);
CREATE TABLE club_membership (
    membership_id BIGSERIAL PRIMARY KEY,
    club_id       BIGINT NOT NULL REFERENCES club(club_id),
    user_id       BIGINT NOT NULL REFERENCES app_user(user_id),
    role          VARCHAR(10) NOT NULL CHECK (role IN ('MEMBER','OFFICER')),
    status        VARCHAR(10) NOT NULL CHECK (status IN ('PENDING','ACTIVE','REJECTED','ENDED')),
    requested_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at    TIMESTAMPTZ,
    joined_at     TIMESTAMPTZ,
    reason        VARCHAR(400)
);
CREATE UNIQUE INDEX ux_membership_open ON club_membership (club_id, user_id) WHERE status IN ('PENDING','ACTIVE');
CREATE INDEX ix_membership_user ON club_membership (user_id);

CREATE TABLE event_series (
    series_id  BIGSERIAL PRIMARY KEY,
    frequency  VARCHAR(10) NOT NULL,
    step       INT NOT NULL,
    weekdays   VARCHAR(30),
    until_date DATE,
    max_count  INT
);

CREATE TABLE event (
    event_id           BIGSERIAL PRIMARY KEY,
    event_type         VARCHAR(12) NOT NULL CHECK (event_type IN ('GENERAL','WORKSHOP','COMPETITION')),
    title_en           VARCHAR(200),
    title_ar           VARCHAR(200),
    description_en     VARCHAR(4000),
    description_ar     VARCHAR(4000),
    category_id        BIGINT REFERENCES category(category_id),
    venue_id           BIGINT REFERENCES venue(venue_id),
    host_club_id       BIGINT REFERENCES club(club_id),
    capacity           INT NOT NULL DEFAULT 0,
    start_at           TIMESTAMPTZ,
    end_at             TIMESTAMPTZ,
    reg_opens_at       TIMESTAMPTZ,
    reg_deadline_at    TIMESTAMPTZ,
    cancel_deadline_at TIMESTAMPTZ,
    state              VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    series_id          BIGINT REFERENCES event_series(series_id),
    series_index       INT,
    created_by         BIGINT NOT NULL REFERENCES app_user(user_id),
    proposal           BOOLEAN NOT NULL DEFAULT FALSE,
    rejection_reason   VARCHAR(400),
    cancel_reason      VARCHAR(400),
    results_published  BOOLEAN NOT NULL DEFAULT FALSE,
    search_text        TEXT NOT NULL DEFAULT '',
    version            INT NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_event_state_start ON event (state, start_at);
CREATE INDEX ix_event_venue ON event (venue_id, start_at, end_at);
CREATE INDEX ix_event_series ON event (series_id);
CREATE INDEX ix_event_club ON event (host_club_id);

CREATE TABLE event_poster (
    event_id     BIGINT PRIMARY KEY REFERENCES event(event_id) ON DELETE CASCADE,
    content_type VARCHAR(20) NOT NULL,
    data         BYTEA NOT NULL
);
CREATE TABLE event_tag (
    event_id BIGINT NOT NULL REFERENCES event(event_id) ON DELETE CASCADE,
    tag_id   BIGINT NOT NULL REFERENCES tag(tag_id),
    PRIMARY KEY (event_id, tag_id)
);
CREATE TABLE workshop_session (
    session_id BIGSERIAL PRIMARY KEY,
    event_id   BIGINT NOT NULL REFERENCES event(event_id) ON DELETE CASCADE,
    seq        INT NOT NULL,
    topic_en   VARCHAR(200),
    topic_ar   VARCHAR(200),
    start_at   TIMESTAMPTZ NOT NULL,
    end_at     TIMESTAMPTZ NOT NULL,
    venue_id   BIGINT REFERENCES venue(venue_id)
);
CREATE INDEX ix_session_event ON workshop_session (event_id);
CREATE INDEX ix_session_venue ON workshop_session (venue_id, start_at, end_at);

CREATE TABLE event_state_history (
    history_id BIGSERIAL PRIMARY KEY,
    event_id   BIGINT NOT NULL REFERENCES event(event_id) ON DELETE CASCADE,
    from_state VARCHAR(20),
    to_state   VARCHAR(20) NOT NULL,
    changed_by BIGINT REFERENCES app_user(user_id),
    note       VARCHAR(400),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE registration (
    registration_id BIGSERIAL PRIMARY KEY,
    event_id        BIGINT NOT NULL REFERENCES event(event_id),
    user_id         BIGINT NOT NULL REFERENCES app_user(user_id),
    status          VARCHAR(12) NOT NULL CHECK (status IN ('CONFIRMED','WAITLISTED','CANCELLED','ATTENDED')),
    registered_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    cancelled_at    TIMESTAMPTZ,
    cancelled_by    BIGINT REFERENCES app_user(user_id),
    cancel_reason   VARCHAR(400),
    attended_at     TIMESTAMPTZ,
    checked_in_by   BIGINT REFERENCES app_user(user_id)
);
-- FR-REG-03: at most one active registration per student and event (also enforced in code)
CREATE UNIQUE INDEX ux_registration_active ON registration (event_id, user_id)
    WHERE status IN ('CONFIRMED','WAITLISTED','ATTENDED');
CREATE INDEX ix_registration_event ON registration (event_id, status, registered_at);
CREATE INDEX ix_registration_user ON registration (user_id, status);

CREATE TABLE session_attendance (
    registration_id BIGINT NOT NULL REFERENCES registration(registration_id) ON DELETE CASCADE,
    session_id      BIGINT NOT NULL REFERENCES workshop_session(session_id) ON DELETE CASCADE,
    present         BOOLEAN NOT NULL,
    marked_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    marked_by       BIGINT REFERENCES app_user(user_id),
    PRIMARY KEY (registration_id, session_id)
);

CREATE TABLE competition_result (
    result_id BIGSERIAL PRIMARY KEY,
    event_id  BIGINT NOT NULL REFERENCES event(event_id) ON DELETE CASCADE,
    user_id   BIGINT NOT NULL REFERENCES app_user(user_id),
    placement INT NOT NULL CHECK (placement BETWEEN 1 AND 3),
    score     NUMERIC(10,2),
    UNIQUE (event_id, user_id)
);

CREATE TABLE feedback (
    feedback_id BIGSERIAL PRIMARY KEY,
    event_id    BIGINT NOT NULL REFERENCES event(event_id),
    user_id     BIGINT NOT NULL REFERENCES app_user(user_id),
    rating      INT NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment     VARCHAR(1000),
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    UNIQUE (event_id, user_id)
);

CREATE TABLE discussion_comment (
    comment_id     BIGSERIAL PRIMARY KEY,
    event_id       BIGINT NOT NULL REFERENCES event(event_id),
    user_id        BIGINT NOT NULL REFERENCES app_user(user_id),
    parent_id      BIGINT REFERENCES discussion_comment(comment_id),
    body           VARCHAR(1000) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    edited         BOOLEAN NOT NULL DEFAULT FALSE,
    removed        BOOLEAN NOT NULL DEFAULT FALSE,
    removed_reason VARCHAR(400),
    removed_by     BIGINT REFERENCES app_user(user_id)
);
CREATE INDEX ix_comment_event ON discussion_comment (event_id, created_at);

CREATE TABLE notification (
    notification_id BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES app_user(user_id),
    ntype           VARCHAR(30) NOT NULL,
    template_key    VARCHAR(80) NOT NULL,
    params          TEXT NOT NULL DEFAULT '',
    link            VARCHAR(60),
    created_at      TIMESTAMPTZ NOT NULL,
    is_read         BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX ix_notification_user ON notification (user_id, created_at DESC);

CREATE TABLE audit_log (
    audit_id   BIGSERIAL PRIMARY KEY,
    actor_id   BIGINT,
    action     VARCHAR(40) NOT NULL,
    target     VARCHAR(80),
    details    VARCHAR(400),
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_audit_time ON audit_log (created_at DESC);

-- one row per sent 24-hour reminder; the primary key makes reminders idempotent (R-04)
CREATE TABLE reminder_log (
    event_id   BIGINT NOT NULL,
    session_id BIGINT NOT NULL DEFAULT 0,
    sent_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, session_id)
);

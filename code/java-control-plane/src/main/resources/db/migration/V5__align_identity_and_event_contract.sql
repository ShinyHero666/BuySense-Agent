create table settings (
    setting_key varchar(128) primary key,
    setting_value varchar(2000) not null,
    updated_at timestamp with time zone not null
);

create table identities (
    identity_id varchar(64) primary key,
    session_id varchar(64) not null unique,
    issued_at timestamp with time zone not null,
    expires_at timestamp with time zone not null
);

alter table agent_runs add column identity_id varchar(64) default 'legacy_identity' not null;
create index idx_agent_runs_identity_created on agent_runs(identity_id, created_at);

alter table run_events add column task_id varchar(128);
alter table run_events add column parent_task_id varchar(128);
alter table run_events add column schema_version varchar(16) default '2.0' not null;

create table interaction_events (
    interaction_id varchar(64) primary key,
    identity_id varchar(64) not null references identities(identity_id) on delete cascade,
    session_id varchar(64) not null,
    event_type varchar(32) not null,
    product_id varchar(256),
    payload_json text not null,
    occurred_at timestamp with time zone not null
);
create index idx_interaction_identity_time on interaction_events(identity_id, occurred_at);

create table identity_preferences (
    identity_id varchar(64) primary key references identities(identity_id) on delete cascade,
    personalization_enabled boolean not null default true,
    updated_at timestamp with time zone not null
);

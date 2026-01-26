create table agent_runs (
    run_id varchar(36) primary key,
    session_id varchar(128) not null,
    message varchar(4000) not null,
    status varchar(32) not null,
    result_json text,
    error_message varchar(2000),
    cancellation_requested boolean not null default false,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null
);

create table run_events (
    event_id varchar(36) primary key,
    run_id varchar(36) not null references agent_runs(run_id) on delete cascade,
    sequence_no bigint not null,
    event_type varchar(64) not null,
    event_time timestamp with time zone not null,
    payload_json text not null,
    constraint uq_run_event_sequence unique (run_id, sequence_no)
);

create index idx_run_events_run_sequence on run_events(run_id, sequence_no);
create index idx_agent_runs_status on agent_runs(status);

create table idempotency_keys (
    session_id varchar(128) not null,
    idempotency_key varchar(256) not null,
    run_id varchar(36) not null references agent_runs(run_id) on delete cascade,
    created_at timestamp with time zone not null,
    primary key (session_id, idempotency_key)
);

create table user_preferences (
    session_id varchar(128) primary key,
    personalization_enabled boolean not null,
    preferred_brand varchar(128),
    updated_at timestamp with time zone not null
);

alter table agent_runs
    add column confirmation_requested boolean not null default false;

alter table agent_runs
    add column result_phase varchar(32);

alter table agent_runs
    add column cart_draft_json text;

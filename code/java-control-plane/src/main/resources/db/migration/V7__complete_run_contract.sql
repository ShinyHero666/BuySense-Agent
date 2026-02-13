alter table agent_runs
    add column idempotency_key varchar(128);

alter table agent_runs
    add column error_code varchar(64);

alter table idempotency_keys
    add column request_fingerprint text;

update agent_runs
set error_code = 'agent_execution_failed'
where status = 'failed' and error_message is not null;

update agent_runs
set error_code = 'run_cancelled'
where status = 'cancelled';

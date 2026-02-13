create table identity_idempotency_keys (
    identity_id varchar(64) not null,
    idempotency_key varchar(256) not null,
    run_id varchar(36) not null references agent_runs(run_id) on delete cascade,
    created_at timestamp with time zone not null,
    primary key (identity_id, idempotency_key)
);

insert into identity_idempotency_keys(identity_id, idempotency_key, run_id, created_at)
select runs.identity_id, keys.idempotency_key, keys.run_id, keys.created_at
from idempotency_keys keys
join agent_runs runs on runs.run_id = keys.run_id;

drop table idempotency_keys;
alter table identity_idempotency_keys rename to idempotency_keys;

create unique index uq_identity_proposal_confirmation
    on agent_runs(identity_id, proposal_run_id);

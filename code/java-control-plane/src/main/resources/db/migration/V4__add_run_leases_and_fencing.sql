alter table agent_runs
    add column lease_owner varchar(64);

alter table agent_runs
    add column lease_token bigint not null default 0;

alter table agent_runs
    add column lease_expires_at timestamp with time zone;

create index idx_agent_runs_lease on agent_runs(status, lease_expires_at);

create table run_admission_lock (
    lock_id varchar(32) primary key
);

insert into run_admission_lock (lock_id) values ('global');

create table proposal_confirmation_claims (
    proposal_run_id varchar(36) primary key
        references agent_runs(run_id) on delete cascade,
    confirmation_run_id varchar(36) not null unique
        references agent_runs(run_id) on delete cascade,
    created_at timestamp with time zone not null
);
insert into proposal_confirmation_claims (
    proposal_run_id, confirmation_run_id, created_at
)
select proposal_run_id, run_id, created_at
from (
    select proposal_run_id, run_id, created_at,
           row_number() over (
               partition by proposal_run_id
               order by created_at, run_id
           ) as claim_rank
    from agent_runs
    where confirmation_requested = true
      and proposal_run_id is not null
      and status in ('queued', 'running', 'completed')
) existing_confirmations
where claim_rank = 1;
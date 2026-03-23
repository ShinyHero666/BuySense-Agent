alter table agent_runs
    add column domain_pack_id varchar(64) not null default 'normal-3c-v1';

alter table agent_runs
    add column workflow_id varchar(64) not null default 'commerce-decision-v1';

alter table agent_runs
    add column proposal_run_id varchar(36);

create index idx_agent_runs_session on agent_runs(session_id);
create index idx_agent_runs_proposal on agent_runs(proposal_run_id);

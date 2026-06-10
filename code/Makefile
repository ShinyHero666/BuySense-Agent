SHELL := /bin/bash

VENV ?= .venv
SHOPREC := $(VENV)/bin/shoprec
SHOPREC_SERVER := $(VENV)/bin/shoprec-server
LAB ?=

.PHONY: help start start-local-qwen preflight bootstrap verify verify-vnext static test scenarios quick-search business-case server agent agent-eval modelport-check modelport-smoke \
	sar-agent-install sar-agent-check sar-agent-test sar-agent-demo sar-agent-cart-demo sar-agent-eval sar-agent-server frontend-install frontend-build vnext-server v2-lab-check lab-start lab-check lab-reset

help:
	@printf '%s\n' \
	  'make start                  Start the beginner offline V2 console' \
	  'make start-local-qwen       Start V2 with ModelPort and local Qwen' \
	  'make preflight              Check offline V2 prerequisites only' \
	  'make bootstrap              Create .venv, install, and verify' \
	  'make verify                 Compile, test, and replay all scenarios' \
	  'make verify-vnext           Run all V2 contracts, tests, eval and UI gates' \
	  'make static                 Check Bash, JSON, docs, and Linux paths' \
	  'make quick-search           Run the guided search tour' \
	  'make business-case          Run the second-hand business tour' \
	  'make server                 Start HTTP API on 127.0.0.1:18080' \
	  'make agent                  Start the replay-mode buyer Agent CLI' \
	  'make agent-eval             Run the 100-case deterministic Agent gates' \
	  'make modelport-check        Check the external Linux ModelPort gateway' \
	  'make modelport-smoke        Explicitly run one real local-model turn' \
	  'make sar-agent-install       Install the TypeScript/Pi control-plane dependencies' \
	  'make sar-agent-test          Test the search-ads-recs multi-Agent control plane' \
	  'make sar-agent-demo          Run the offline normal-3C multi-Agent demo' \
	  'make sar-agent-cart-demo     Run proposal-to-cart-draft confirmation demo' \
	  'make sar-agent-eval          Run V2 search-ads-recs Agent evaluation gates' \
	  'make sar-agent-server        Start the TypeScript Agent HTTP service' \
	  'make frontend-build          Build the React/ECharts decision console' \
	  'make vnext-server            Alias for the beginner offline V2 console' \
	  'make v2-lab-check LAB=1     Run one current V2 learning checkpoint' \
	  'make lab-start LAB=1        Back up source before a lab' \
	  'make lab-check LAB=1        Run one lab checkpoint' \
	  'make lab-reset              Restore the backed-up baseline'

start:
	bash scripts/run-sar-agent.sh --offline

start-local-qwen:
	bash scripts/run-sar-agent.sh --local-qwen

preflight:
	bash scripts/run-sar-agent.sh --offline --check

bootstrap:
	SHOPREC_VENV="$(abspath $(VENV))" bash scripts/bootstrap.sh

verify:
	SHOPREC_VENV="$(abspath $(VENV))" bash scripts/verify.sh

verify-vnext:
	bash scripts/verify-vnext.sh

static:
	bash -n scripts/*.sh
	python3 scripts/check_course.py

test:
	PYTHONPATH="$(CURDIR)/src:$(CURDIR)" "$(VENV)/bin/python" \
	  -m unittest discover -s tests -v

scenarios:
	@set -euo pipefail; \
	for scenario in scenarios/*.json; do \
	  "$(SHOPREC)" --config config/experiments.json scenario "$$scenario"; \
	done

quick-search:
	SHOPREC_VENV="$(abspath $(VENV))" bash scripts/quick-search.sh

business-case:
	SHOPREC_VENV="$(abspath $(VENV))" bash scripts/quick-moyuan-case.sh

server:
	"$(SHOPREC_SERVER)" --host 127.0.0.1 --port 18080

agent:
	PYTHONPATH="$(CURDIR)/src:$(CURDIR)" "$(VENV)/bin/python" -m shoprec.cli agent

agent-eval:
	PYTHONPATH="$(CURDIR)/src:$(CURDIR)" "$(VENV)/bin/python" scripts/run_agent_eval.py \
	  --json-out agent_eval/reports/offline-v1.json \
	  --markdown-out agent_eval/reports/offline-v1.md

modelport-check:
	bash scripts/modelport-check.sh

modelport-smoke:
	bash scripts/modelport-smoke.sh

sar-agent-install:
	npm --prefix agent-control-plane ci --ignore-scripts --no-audit --no-fund
	npm --prefix apps/commerce-console ci --ignore-scripts --no-audit --no-fund

sar-agent-check:
	npm --prefix agent-control-plane run check

sar-agent-test:
	npm --prefix agent-control-plane test

sar-agent-demo:
	npm --prefix agent-control-plane run demo

sar-agent-cart-demo:
	npm --prefix agent-control-plane run demo:cart

sar-agent-eval:
	npm --prefix agent-control-plane run eval

sar-agent-server:
	npm --prefix agent-control-plane run server

frontend-install:
	npm --prefix apps/commerce-console ci --ignore-scripts --no-audit --no-fund

frontend-build:
	npm --prefix apps/commerce-console run build

vnext-server: start

v2-lab-check:
	@test -n "$(LAB)" || (echo 'LAB is required, for example: make v2-lab-check LAB=1' >&2; exit 2)
	bash scripts/check-v2-lab.sh "$(LAB)"

lab-start:
	@test -n "$(LAB)" || (echo 'LAB is required, for example: make lab-start LAB=1' >&2; exit 2)
	bash scripts/start-lab.sh "$(LAB)"

lab-check:
	@test -n "$(LAB)" || (echo 'LAB is required, for example: make lab-check LAB=1' >&2; exit 2)
	SHOPREC_VENV="$(abspath $(VENV))" bash scripts/check-lab.sh "$(LAB)"

lab-reset:
	bash scripts/reset-lab.sh

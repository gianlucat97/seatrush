# Developer entry points for SeatRush (README section 23).
# Targets that depend on modules a later milestone introduces fail with the
# milestone that brings them, instead of a missing-rule error.

MVNW ?= ./mvnw
MAVEN_FLAGS ?= -B -ntp

.DEFAULT_GOAL := help
.PHONY: help format build test-it clean up down seed ui load-small check

help: ## Show this help
	@grep -hE '^[a-z][a-z-]*:.*## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*## "}; {printf "  %-12s %s\n", $$1, $$2}'

format: ## Rewrite every file in the project format
	$(MVNW) $(MAVEN_FLAGS) spotless:apply

build: ## Format check, compile, unit tests
	$(MVNW) $(MAVEN_FLAGS) -DskipITs verify

test-it: ## Full build including the integration tests (Testcontainers, needs Docker)
	$(MVNW) $(MAVEN_FLAGS) verify

clean: ## Remove every build output
	$(MVNW) $(MAVEN_FLAGS) clean

up: ## Start infrastructure, observability and all services (Docker Compose)
	@echo "make up arrives with the Docker Compose infrastructure (M1)."; exit 1

down: ## Stop everything and remove the volumes
	@echo "make down arrives with the Docker Compose infrastructure (M1)."; exit 1

seed: ## Create a demo event with 2,000 seats, on-sale in 2 minutes
	@echo "make seed arrives with the inventory service (M1)."; exit 1

ui: ## Start the seat map frontend
	@echo "make ui arrives with the seat map UI (M4)."; exit 1

load-small: ## 5,000-user load test followed by the invariant checker
	@echo "make load-small arrives with the benchmark suite (M7)."; exit 1

check: ## Run the invariant checker alone
	@echo "make check arrives with the benchmark suite (M7)."; exit 1

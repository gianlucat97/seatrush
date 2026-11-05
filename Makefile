# Developer entry points for SeatRush (README section 23).
# Targets that depend on modules a later milestone introduces fail with the
# milestone that brings them, instead of a missing-rule error.

MVNW ?= ./mvnw
MAVEN_FLAGS ?= -B -ntp

# The local stack lives in one Compose file; --env-file is explicit because the file sits
# in deploy/docker-compose and Compose would otherwise look for .env next to it.
COMPOSE_FILE ?= deploy/docker-compose/docker-compose.yml
COMPOSE ?= docker compose --file $(COMPOSE_FILE) --env-file .env

.DEFAULT_GOAL := help
.PHONY: help format build test-it clean up down images seed ui ui-build load-small check

# The frontend is a Node project rather than a Maven module, so it has its own
# install and its own build.
UI_DIR ?= frontend/seat-map-ui
NPM ?= npm

# Where make seed talks to. The gateway's published port, since the seed runs on the host.
SEED_API_URL ?= http://localhost:8080

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

up: .env ## Start the whole local stack: infrastructure, observability, the services, the client
	# --build so a source change is in the image that starts. The build stage is shared by
	# the six service images and cached, so an unchanged tree rebuilds nothing.
	$(COMPOSE) up --detach --wait --build

images: .env ## Build the container images without starting anything
	$(COMPOSE) build

down: .env ## Stop everything and remove the volumes
	$(COMPOSE) down --volumes --remove-orphans

# Copied once and then yours to edit: .env is git-ignored, .env.example is the template.
.env:
	@cp .env.example .env
	@echo "created .env from .env.example"

seed: ## Create a demo event with 2,000 seats, on-sale in 2 minutes
	@SEATRUSH_API_URL=$(SEED_API_URL) ./scripts/seed.sh

ui: $(UI_DIR)/node_modules ## Start the seat map frontend on http://localhost:5173
	$(NPM) --prefix $(UI_DIR) run dev

ui-build: $(UI_DIR)/node_modules ## Format check, type check, unit tests and bundle of the frontend
	$(NPM) --prefix $(UI_DIR) run format:check
	$(NPM) --prefix $(UI_DIR) test
	$(NPM) --prefix $(UI_DIR) run build

# Installed from the lock file, so a checkout builds what CI builds. The directory
# is the target, so a second make ui does not reinstall.
$(UI_DIR)/node_modules: $(UI_DIR)/package-lock.json
	$(NPM) --prefix $(UI_DIR) ci
	@touch $@

load-small: ## 5,000-user load test followed by the invariant checker
	@echo "make load-small arrives with the benchmark suite (M7)."; exit 1

check: ## Run the invariant checker alone
	@echo "make check arrives with the benchmark suite (M7)."; exit 1

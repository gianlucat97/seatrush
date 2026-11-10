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
.PHONY: help format build test-it clean up down images seed ui ui-build
.PHONY: k8s-up k8s-down k8s-seed k8s-status k8s-template load-small load-full
.PHONY: chaos check message-docs api-docs publish-run demo-gif
.PHONY: k8s-observability k8s-hpa k8s-grafana

# The frontend is a Node project rather than a Maven module, so it has its own
# install and its own build.
UI_DIR ?= frontend/seat-map-ui
NPM ?= npm

# Where make seed talks to. The gateway's published port, since the seed runs on the host.
SEED_API_URL ?= http://localhost:8080

help: ## Show this help
	# A digit in the class as well as a letter, because the k8s targets below have one in
	# their name and a target missing from this listing is a target nobody finds.
	@grep -hE '^[a-z][a-z0-9-]*:.*## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*## "}; {printf "  %-18s %s\n", $$1, $$2}'

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

# The local Kubernetes cluster of README section 24. Its entry point is a script rather
# than a recipe, because handing seven locally built images to the nodes that run them is a
# sequence worth reading.
K8S_CLUSTER ?= seatrush
K8S_NAMESPACE ?= seatrush
K8S_RELEASE ?= seatrush
HELM_CHART ?= deploy/helm/seatrush
KUBECTL ?= kubectl --context kind-$(K8S_CLUSTER) --namespace $(K8S_NAMESPACE)
# The add-ons of README section 24.4 live in a namespace of their own, because they are
# facilities of the cluster and outlive any one release of SeatRush.
KUBECTL_OBSERVABILITY ?= kubectl --context kind-$(K8S_CLUSTER) --namespace seatrush-observability

# Where the cluster answers: the ingress controller, through the port deploy/k8s/kind-cluster.yaml
# maps from the host. One address for the client and the API, which is the same origin the
# Compose stack serves them from (README section 6.7).
K8S_URL ?= http://localhost:8088

k8s-up: ## Create the kind cluster and deploy the whole system to it
	./scripts/k8s-up.sh

k8s-down: ## Delete the kind cluster and everything in it
	kind delete cluster --name $(K8S_CLUSTER)

k8s-seed: ## Create a demo event in the cluster, on-sale in 2 minutes
	@SEATRUSH_API_URL=$(K8S_URL) SEATRUSH_UI_URL=$(K8S_URL) ./scripts/seed.sh

k8s-status: ## What is running in the cluster
	# The budgets and the policies of README sections 24.5 and 24.7 are listed here too,
	# because they are the two objects nothing else in this file would show and the two whose
	# absence looks exactly like their presence.
	@$(KUBECTL) get pods,svc,ingress,pdb,networkpolicy --selector app.kubernetes.io/part-of=seatrush

k8s-template: ## Render the chart without a cluster, which is what CI validates
	helm template $(K8S_RELEASE) $(HELM_CHART)

k8s-observability: ## Install the metrics and autoscaling add-ons alone (README section 24.4)
	./scripts/k8s-observability.sh

k8s-hpa: ## What every autoscaler of the release currently reads
	# Both kinds together, because the release has both and reading one of them is reading
	# two thirds of the answer. A ScaledObject's own HPA is in the first list as well, named
	# keda-hpa-<service>, which is where its current reading of the lag is.
	@$(KUBECTL) get hpa,scaledobject

# A port-forward and not an Ingress. Section 24.1 has one way into the cluster and it is the
# client and the API; an operations console on the same host would be a second one. 3001 on
# the host, because 3000 is the Grafana of the Compose stack and both can be up at once.
k8s-grafana: ## Open the dashboards of the cluster on http://localhost:3001
	@echo "the three dashboards of README section 14.4: http://localhost:3001"
	@$(KUBECTL_OBSERVABILITY) port-forward service/grafana 3001:3000

# Flags for a run, passed to Maven, which is how one knob of README section 18.1 is changed
# without a third preset:
#   make load-small LOAD_FLAGS="-Dseatrush.load.spectators=0"
# load-tests/gatling/src/test/java/com/seatrush/load/LoadProfile.java lists them all.
LOAD_FLAGS ?=

load-small: ## 5,000-user load test followed by the invariant checker
	@./scripts/load.sh small $(LOAD_FLAGS)

load-full: ## The benchmark of README section 18.1: 200,000 buyers, needs a cluster and an injector
	@./scripts/load.sh full $(LOAD_FLAGS)

# Which profile the published run is of, since the environment decides that and not this file:
#   make publish-run RUN_PROFILE=full
RUN_PROFILE ?= small

publish-run: ## A run of the benchmark published into docs/benchmarks/ (README section 18.2)
	@./scripts/publish-run.sh $(RUN_PROFILE) $(LOAD_FLAGS)

# Where the recording goes. The venue and the length are DEMO_FLAGS, DEMO_FRAMES and
# DEMO_INTERVAL, which scripts/demo-gif.sh documents.
DEMO_GIF ?= docs/demo.gif

demo-gif: ## Record the seat map filling under load into docs/demo.gif (README section 6.7)
	@./scripts/demo-gif.sh $(DEMO_GIF)

# One experiment instead of all of them:
#   make chaos EXPERIMENTS="kill-order stop-kafka"
# ./scripts/chaos.sh list names them.
EXPERIMENTS ?=

chaos: ## Break the things README section 16 makes a promise about, then check the invariants
	@./scripts/chaos.sh $(EXPERIMENTS)

# The generator is the test that checks them, so one message type has one description and not two.
# Without the write flag the same test compares instead of writing, which is what makes a schema
# that has drifted a failed build (README section 17).
MESSAGE_DOCS_FLAGS = -Dtest=MessageSchemaTest -Dsurefire.failIfNoSpecifiedTests=false -Dseatrush.messages.write=true

message-docs: ## Regenerate the JSON Schemas of docs/messages/ from the message records
	$(MVNW) $(MAVEN_FLAGS) -pl libs/messaging -am -DskipITs $(MESSAGE_DOCS_FLAGS) test

# The other half of README section 17.4, and the one that needs Docker: each document is asked of
# its own service while that service is running, so what is published is what a pod serves rather
# than what a reflection pass believes. Unit tests are off and one integration test per service is
# on, which is why both failIfNoSpecifiedTests flags are here: every module of the reactor is
# visited and only six of them have an OpenApiIT.
API_DOCS_FLAGS = -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
	-Dit.test=OpenApiIT -Dfailsafe.failIfNoSpecifiedTests=false -Dseatrush.api.write=true

api-docs: ## Regenerate the OpenAPI documents of docs/api/ from the six services
	$(MVNW) $(MAVEN_FLAGS) $(API_DOCS_FLAGS) verify

# Flags for the run, since every one of them has a default that fits the local stack:
#   make check CHECK_FLAGS="--settle-timeout 0"      look at a sale that is still running
#   make check CHECK_FLAGS="--event <uuid>"          one event of a catalog with several
# ./scripts/check.sh --help lists them all.
CHECK_FLAGS ?=

check: ## Verify the invariants of README section 4 against the running stack
	@./scripts/check.sh $(CHECK_FLAGS)

# Porto Bus API — common tasks.
#
#   make setup    install deps + create .env
#   make dev      run with auto-reload
#   make smoke    hit every endpoint once, print status codes
#
# Run `make` on its own for the full list.

SHELL := /bin/bash
.DEFAULT_GOAL := help

PORT ?= 8000
BASE ?= http://127.0.0.1:$(PORT)

# Defaults used by the smoke test / curl helpers. Override on the command line:
#   make realtime STOP=BOLH
STOP ?= CMO
LINE ?= 300
DIRECTION ?= 0

# Departure board. LAT/LON are optional — without them the board uses
# HOME_LAT/HOME_LON from .env (see `make geocode`).
WALK ?= 10
ROWS ?= 10

.PHONY: help
help: ## Show this help
	@echo "Porto Bus API"
	@echo
	@grep -hE '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}'
	@echo
	@echo "Vars: PORT=$(PORT) STOP=$(STOP) LINE=$(LINE) DIRECTION=$(DIRECTION) WALK=$(WALK) ROWS=$(ROWS)"
	@echo "      board: set HOME_LAT/HOME_LON in .env, or pass LAT= LON="

# ---- setup -----------------------------------------------------------------

.PHONY: setup
setup: node_modules .env ## Install dependencies and create .env
	@echo "Ready. Run 'make dev'."

node_modules: package.json
	npm install
	@touch node_modules

.env:
	@cp .env.example .env
	@echo "Created .env from .env.example"

.PHONY: reinstall
reinstall: ## Wipe node_modules and reinstall from scratch
	rm -rf node_modules package-lock.json
	npm install

# ---- running ---------------------------------------------------------------

.PHONY: dev
dev: setup ## Run the API with auto-reload (Ctrl-C to stop)
	npm run dev

.PHONY: start
start: setup ## Run the API without auto-reload
	npm start

.PHONY: stop
stop: ## Kill whatever is listening on PORT
	@lsof -ti:$(PORT) | xargs -r kill 2>/dev/null && echo "Stopped." || echo "Nothing on port $(PORT)."

# ---- testing ---------------------------------------------------------------

.PHONY: test
test: node_modules ## Run the unit tests
	npm test

.PHONY: postman
postman: ## Run the Postman collection headlessly (needs a running server)
	@if ! curl -sf --max-time 3 "$(BASE)/health" >/dev/null; then \
		echo "No server on $(BASE) — start one with 'make dev' first."; exit 1; \
	fi
	npx --yes newman run postman/porto-bus-api.postman_collection.json \
		--env-var baseUrl=$(BASE) \
		--env-var stopCode=$(STOP) \
		--env-var line=$(LINE) \
		--env-var directionId=$(DIRECTION) \
		--reporters cli --reporter-cli-no-banner

.PHONY: smoke
smoke: ## Hit every endpoint against a running server and print status codes
	@echo "Smoke-testing $(BASE) (stop=$(STOP) line=$(LINE))"
	@echo
	@if ! curl -sf --max-time 3 "$(BASE)/health" >/dev/null; then \
		echo "  No server on $(BASE) — start one with 'make dev' first."; exit 1; \
	fi
	@svc=$$(curl -s --max-time 30 "$(BASE)/lines/$(LINE)/services" \
		| sed -n 's/.*"active_service_id":"\([^"]*\)".*/\1/p'); \
	echo "  active service_id: $$svc"; echo; \
	svc_enc=$$(printf '%s' "$$svc" | sed 's/ /%20/g; s/|/%7C/g'); \
	fail=0; \
	check() { \
		code=$$(curl -s -o /dev/null -w '%{http_code}' --max-time 60 "$(BASE)$$1"); \
		if [ "$$code" = "200" ]; then mark="ok  "; else mark="FAIL"; fail=1; fi; \
		printf "  %s %-3s %s\n" "$$mark" "$$code" "$$1"; \
	}; \
	check "/health"; \
	check "/stops?q=carmo&limit=5"; \
	check "/stops/$(STOP)"; \
	check "/stops/$(STOP)/realtime"; \
	check "/stops/$(STOP)/routes"; \
	check "/stops/$(STOP)/services"; \
	check "/stops/$(STOP)/departures?line=$(LINE)"; \
	check "/stops/$(STOP)/schedule?route_id=$(LINE)&service_id=$$svc_enc&direction_id=$(DIRECTION)"; \
	check "/lines"; \
	check "/lines/$(LINE)/stops?direction_id=$(DIRECTION)"; \
	check "/lines/$(LINE)/shape?direction_id=$(DIRECTION)"; \
	check "/lines/$(LINE)/services"; \
	check "/lines/$(LINE)/schedule?service_id=$$svc_enc&direction_id=$(DIRECTION)"; \
	echo; \
	if [ $$fail -eq 0 ]; then echo "  All endpoints returned 200."; \
	else echo "  Some endpoints failed."; exit 1; fi

# ---- handy one-off calls ---------------------------------------------------

.PHONY: board
board: ## LED-style departure board for HOME_LAT/HOME_LON (or LAT=/LON=)
	@curl -s "$(BASE)/board.txt?$(if $(LAT),lat=$(LAT)&lon=$(LON)&,)walk_minutes=$(WALK)&limit=$(ROWS)&color=1"

.PHONY: watch-board
watch-board: ## Refresh the board every 30s, like the real display would
	@while true; do \
		clear; \
		curl -s "$(BASE)/board.txt?$(if $(LAT),lat=$(LAT)&lon=$(LON)&,)walk_minutes=$(WALK)&limit=$(ROWS)&color=1"; \
		sleep 30; \
	done

.PHONY: geocode
geocode: ## Turn an address into coordinates: make geocode ADDRESS="Rua ..., Porto"
	@test -n "$(ADDRESS)" || (echo 'Usage: make geocode ADDRESS="Rua de ..., Porto"'; exit 1)
	@node scripts/geocode.js "$(ADDRESS)"

.PHONY: realtime
realtime: ## Live board for STOP
	@curl -s "$(BASE)/stops/$(STOP)/realtime" | $(PRETTY)

.PHONY: departures
departures: ## Combined live + scheduled for LINE at STOP
	@curl -s "$(BASE)/stops/$(STOP)/departures?line=$(LINE)" | $(PRETTY)

.PHONY: service-id
service-id: ## Print today's active service_id for LINE (needed by schedule calls)
	@curl -s "$(BASE)/lines/$(LINE)/services" | $(PRETTY)

# Pretty-print if python3 is around, otherwise pass through untouched.
PRETTY := $$(command -v python3 >/dev/null && echo "python3 -m json.tool" || echo cat)

SHELL=/bin/bash -O globstar -O extglob

VERSION:=$(shell git rev-parse --short=10 HEAD)
DOCKER_LOCATION:=docker
KEYSTORE_LOC:=/home/heykieran/kjo-security/jetty-keystore

CLJS_SRCS=$(shell find cljs-src -type f)
FE_CLJS_SRCS=$(shell find fe-src -type f)
COMMON_SRCS=$(shell find common-src -type f)
CLJ_SRCS=$(shell find src -type f)
CURR_DATE=$(shell date +%Y%m%d)
NUM_MINUTES=$(shell date -d "1970-01-01 `date +%T`" +%s | xargs -Is expr s / 60)

.PHONY: test build docker clean-build clean-deploy clean-all run-local

target/public/cljs-out/prod-main.js: deps.edn $(CLJS_SRCS) $(FE_CLJS_SRCS) $(COMMON_SRCS)
	clojure -A:prod

target/classes/main/core.class: deps.edn $(CLJ_SRCS) $(COMMON_SRCS)
	clojure -A:build -m package

build: target/public/cljs-out/prod-main.js target/classes/main/core.class

docker: $(DOCKER_LOCATION)/deploy
	docker build --build-arg ALLOC_BUILD_ID=v1.0.${CURR_DATE}m${NUM_MINUTES} -t clojure-app-v1 docker

clean-build:
	rm -fr target/app
	rm -fr target/classes
	rm -fr target/log
	rm -fr target/public/cljs-out/prod
	rm -f target/public/cljs-out/prod-main.js

clean-deploy:
	rm -rf $(DOCKER_LOCATION)/deploy

clean-all: clean-build clean-deploy

$(DOCKER_LOCATION)/deploy: target/classes/main/core.class target/public/cljs-out/prod-main.js
	mkdir -p $(DOCKER_LOCATION)/deploy
	mkdir -p $(DOCKER_LOCATION)/deploy/local
	mkdir -p $(DOCKER_LOCATION)/deploy/log
	mkdir -p $(DOCKER_LOCATION)/deploy/saved
	mkdir -p $(DOCKER_LOCATION)/deploy/app/public/cljs-out
	cp -r target/app $(DOCKER_LOCATION)/deploy
	cp -r target/classes $(DOCKER_LOCATION)/deploy
	cp target/public/cljs-out/prod-main.js $(DOCKER_LOCATION)/deploy/app/public/cljs-out
	if [ -d "saved" ]; then cp saved/* $(DOCKER_LOCATION)/deploy/saved/; fi;
	find $(DOCKER_LOCATION)/deploy/app -maxdepth 1 -mindepth 1 -type d \( ! \( -name 'lib' -o -name 'public' \) \) -exec rm -rf {} \;
	cp $(KEYSTORE_LOC) $(DOCKER_LOCATION)/deploy/local

run-local: export ALLOC_SSL_PORT=8081
run-local: export ALLOC_KEYSTORE_PASSWORD=password # this is NOT the correct password
run-local: export ALLOC_PORT=8080
run-local: export ALLOC_KEYSTORE_LOCATION=$(PWD)/$(DOCKER_LOCATION)/deploy/local/jetty-keystore
run-local: export ALLOC_SESSION_STORE_KEY=akh6y98dhyt54sch

# make run-local ALLOC_KEYSTORE_PASSWORD=the_real_password
run-local: build $(DOCKER_LOCATION)/deploy
	cd $(DOCKER_LOCATION)/deploy && java ${JAVA_OPTS} -cp ".:classes:app:app/lib/*" main.core

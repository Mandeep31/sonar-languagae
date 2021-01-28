.DEFAULT_GOAL := help

.PHONY: all preFlight helmLint build unitTest sonar package clean
 
TF_DOCKER_IMAGE ?= application/sre/k8s-validator:1.1.37
export UTIL_DOCKER_IMAGE ?= platform-dev/base/node:12.18.4-master.2 ## used for "make clean", unsure otherwise
export SONAR_SCANNER_IMAGE_NAME ?= goo-images/utility-scanner-sonarqube:0.1.21
export UTIL_GRADLE_IMAGE ?= platform-dev/build-tools/gradle:5.6.2-openjdk8

MODULE ?=

ifeq ($(PROPERTIES_FILE),)
	PROPERTIES_FILE := build.properties
endif

help:
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-30s\033[0m %s\n", $$1, $$2}'

helmLint: ## this will do a helm lint and k8s-validation
	docker pull ${TF_DOCKER_IMAGE}
	echo Make target: helmLint - ${MODULE}
	docker run --rm --entrypoint /bin/sh -v ${PWD}/:/it ${TF_DOCKER_IMAGE} -c "cd /it/${MODULE}/ && helm lint --strict chart/${MODULE}" &
	docker run --rm --entrypoint /bin/sh -v ${PWD}/:/it ${TF_DOCKER_IMAGE} -c "cd /it/${MODULE}/ && helm template chart/${MODULE}" > rendered_template_${MODULE}.yml
	docker run --rm -v ${PWD}/:/it ${TF_DOCKER_IMAGE} /it/rendered_template_${MODULE}.yml
	
build: ## this target will build
	echo Make target: build
	makefiles/scripts/build.sh
	
clean: # this will clean and remove workspace files
	echo Make target: clean
	makefiles/scripts/clean.sh

preFlight: ## used to understand and generate the environment
	echo Make target: preFlight
	makefiles/scripts/preFlight.sh

unitTest: ## this will run all unitTest
	echo Make target: unitTest
	makefiles/scripts/unitTest.sh

test: ## test preflight
	makefiles/scripts/test/test_all.sh

sonar: ## this will scan for quality metrics and report it to sonarqube
	echo Make target: sonar
	makefiles/scripts/sonar.sh

package: ## this will package application into docker image
	echo Make target: package
	makefiles/scripts/package.sh

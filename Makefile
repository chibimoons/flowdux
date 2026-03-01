.PHONY: setup lint format

setup:
	git config core.hooksPath .githooks

lint:
	./gradlew spotlessCheck detekt

format:
	./gradlew spotlessApply

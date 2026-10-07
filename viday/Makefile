.PHONY:	run device-wait device-reverse device-tests test-unit tests

SDK ?= $(HOME)/Android/Sdk
AVD ?= Medium_Phone
ADB ?= adb

# Запуск эмулятора (AVD по умолчанию Medium_Phone)
run:
	$(SDK)/emulator/emulator -avd $(AVD) -dns-server 8.8.8.8

# Ожидание полной загрузки устройства + проброс порта API стенда
device-wait:
	$(ADB) wait-for-device
	until [ "$$($(ADB) shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
	$(ADB) reverse tcp:8080 tcp:8080
	@echo "device ready: $$($(ADB) devices)"

# Проброс порта API стенда (если устройство уже загружено)
device-reverse:
	$(ADB) reverse tcp:8080 tcp:8080

# Unit-тесты (JVM)
test-unit:
	./gradlew test

# Инструментированные тесты НА УСТРОЙСТВЕ (Требование 7 ЛР2)
device-tests: device-wait
	./gradlew :app:connectedDebugAndroidTest

# Полный прогон мобильного контура: unit + устройство
tests: test-unit device-tests

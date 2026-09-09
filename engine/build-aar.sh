#!/bin/sh
# Сборка engine.aar.
#
# Скрипт существует ради ОДНОГО флага: -ldflags=-checklinkname=0.
#
# Без него сборка падает так:
#
#   link: github.com/wlynxg/anet: invalid reference to net.zoneCache
#
# Причина: pion/turn безусловно импортирует transport/v4/stdnet
# (turn/client.go:17), тот тянет github.com/wlynxg/anet, а anet через
# //go:linkname лезет во внутренний net.zoneCache
# (anet/interface_android.go:164). Go с версии 1.23 такие ссылки
# запрещает. Обойти выбором версии нельзя — stdnet импортируется
# безусловно и в turn v3, и в v4.
#
# Ошибка ни словом не упоминает TURN, так что забытый флаг стоит часа
# недоумения. Отсюда и скрипт.
#
# ПРОВЕРЕНО 26.08.2026, Go 1.27.0: флаг ВСЁ ЕЩЁ НУЖЕН.
#
# Проверял не рассуждением, а сборкой. `go build ./...` тут ничего не
# доказывает: библиотеку он не компонует, и ошибка появляется только на
# настоящей линковке в libgojni.so.
#
# Повторить проверку:
#
#   gomobile bind -target=android/arm64 -androidapi 26 -o /tmp/probe.aar .
#
# Собралось — флаг ниже можно убирать.
#
# Обходных путей на сегодня нет:
#
#   anet уже САМОЙ СВЕЖЕЙ версии v0.0.5, и запрещённая ссылка в ней;
#   pion/transport/v4 v4.1.0 (новее нашей) тянет anet ровно так же —
#     проверял, зависимость не отвалилась;
#   отказаться от pion/turn нельзя: это релейная ступень целиком.
#
# Что снимет флаг: выход anet новее v0.0.5 без //go:linkname. Проверять
# при каждом обновлении зависимостей — командой выше, она стоит минуту.
#
# Цена флага, чтобы не забывалась: он снимает проверку компоновщика
# ГЛОБАЛЬНО, на всю сборку, а не только для anet. Сузить нельзя, это
# ключ линкера.
#
# Перед запуском владелец выгружает античит:  fltmc unload FACEIT
#
# Пути ниже — под машину владельца. Меняются здесь, в одном месте.

set -e

export ANDROID_HOME="${ANDROID_HOME:-C:/Users/bioto/AppData/Local/Android/Sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/30.0.15729638}"
export JAVA_HOME="${JAVA_HOME:-C:/Program Files/Android/Android Studio/jbr}"
export PATH="$JAVA_HOME/bin:$PATH"

cd "$(dirname "$0")"

# АРХИТЕКТУРЫ ВЫБИРАЮТСЯ КЛЮЧОМ.
#
#   build-aar.sh          — только arm64-v8a. Умолчание для отладки:
#                           собирается втрое быстрее и APK втрое легче,
#                           а телефон владельца всё равно arm64;
#   build-aar.sh --all    — все три. Обязательно для релиза и Play.
#
# Имена у gomobile свои и с именами Android не совпадают:
#   android/arm64 -> arm64-v8a
#   android/arm   -> armeabi-v7a
#   android/amd64 -> x86_64
#
# 32-битный x86 не собираем: устройств на нём практически не осталось.
# Список обязан совпадать с abiFilters в app/build.gradle.kts — иначе в
# бандл попадёт архитектура без движка.
#
# Флаг -checklinkname=0 нужен всем одинаково: он про линковку, а не про
# архитектуру, и запрещённая ссылка anet на net.zoneCache существует в
# любой сборке под Android.
#
# ЗАБЫТЬ --all ПЕРЕД РЕЛИЗОМ НЕЛЬЗЯ: сборка релиза проверяет AAR и
# падает с внятным текстом, если архитектур в нём меньше трёх. Проверка
# в app/build.gradle.kts.

TARGET="android/arm64"
WHAT="только arm64-v8a (отладка)"
if [ "$1" = "--all" ]; then
	TARGET="android/arm64,android/arm,android/amd64"
	WHAT="три архитектуры (релиз)"
fi

go vet ./...
gomobile bind \
	-target="$TARGET" \
	-androidapi 26 \
	-ldflags=-checklinkname=0 \
	-o ../app/libs/engine.aar \
	.

echo "engine.aar собран: $WHAT"

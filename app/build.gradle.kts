import java.util.Properties
import java.util.zip.ZipFile

// Номер отладочной сборки. Поднимается build-apk.sh, читается здесь.
val buildNumber: Int = file("version.properties").let { f ->
    if (!f.exists()) 1
    else Properties().apply { f.inputStream().use { load(it) } }
        .getProperty("code", "1").toInt()
}

/**
 * Пароли подписи. Файла в репозитории НЕТ и быть не должно — .gitignore
 * закрывает его. Образец лежит рядом: keystore.properties.example.
 *
 * Нет файла — релиз собирается НЕПОДПИСАННЫМ. Не падаем намеренно:
 * отладочные сборки должны собираться у любого, а про отсутствие
 * подписи сборка скажет отдельной строкой.
 */
val keystoreProps: Properties? = rootProject.file("keystore.properties").let { f ->
    if (!f.exists()) null
    else Properties().apply { f.inputStream().use { load(it) } }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.meridianvpn.app"

    /**
     * ВЕРСИЯ NDK — НУЖНА НЕ ДЛЯ СБОРКИ, А ДЛЯ ИНСТРУМЕНТОВ.
     *
     * Своего нативного кода мы не собираем: libgojni.so приезжает
     * готовой из engine.aar, а её собирает gomobile своим NDK.
     *
     * Но без этой строки Gradle не знает, где взять strip, и в сборке
     * стояло:
     *
     *   Unable to strip library ... due to missing strip tool for ABI
     *
     * Молча из этого следовали ДВЕ вещи, и обе плохие:
     *
     *   библиотека паковалась неурезанной — 16 МБ вместо нескольких;
     *   отладочные символы для Play не извлекались вовсе, хотя
     *   debugSymbolLevel был выставлен. Задача отрабатывала и клала в
     *   бандл пустоту, а Play при каждой загрузке просил символы.
     *
     * Версия должна совпадать с той, которой собран движок, — см.
     * ANDROID_NDK_HOME в engine/build-aar.sh.
     */
    ndkVersion = "30.0.15729638"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "org.meridianvpn.app"
        minSdk = 26
        targetSdk = 37
        // Номер сборки живёт в app/version.properties и поднимается
        // скриптом build-apk.sh при каждой сборке.
        //
        // Нужно затем, что отладочные сборки ставились с УДАЛЕНИЕМ
        // старой, а удаление стирает и пул хешей, и память пути — то
        // есть ровно то, что мы отлаживаем. С растущим versionCode APK
        // ставится поверх, и накопленное переживает установку.
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    /**
     * ДВА ВАРИАНТА РАЗДАЧИ, ОБА РЕЛИЗНЫЕ.
     *
     *   play   — сборка для Google Play;
     *   direct — прямая раздача APK.
     *
     * Различаются они одним: что предлагать человеку после пробного
     * периода. В сборке из Play вести на внешнюю оплату нельзя ни
     * ссылкой, ни словом — за это снимают приложение.
     *
     * ПОЧЕМУ ВАРИАНТЫ, А НЕ ФЛАГ В КОДЕ. Флаг оставил бы строки в
     * сборке: и ссылку на бота, и слово «Telegram» видно в dex обычным
     * grep, даже если ветка недостижима. Полагаться на то, что R8
     * выбросит недостижимое, здесь нельзя — проверять пришлось бы после
     * каждой правки.
     *
     * Поэтому предложение о покупке живёт в РАЗНЫХ наборах исходников:
     *   app/src/play/java/.../Purchase.kt
     *   app/src/direct/java/.../Purchase.kt
     * В сборку попадает только один, и в play-варианте строк про бота
     * нет физически, а не по недостижимости.
     *
     * ОСЬ РАЗДАЧИ НЕ ЗАМЕНЯЕТ ОСЬ ОТЛАДКИ. Тестовый ключ и засев ссылок
     * VK по-прежнему убираются по BuildConfig.DEBUG: они не про то, кому
     * раздаём, а про то, отлаживаем ли. Обе релизные сборки их не
     * содержат.
     */
    flavorDimensions += "distribution"

    productFlavors {
        create("play") {
            dimension = "distribution"
            // Видно на экране и в имени файла: перепутать варианты
            // при раздаче стоило бы дороже, чем эти семь букв.
            versionNameSuffix = "-play"
        }
        create("direct") {
            dimension = "distribution"
            versionNameSuffix = "-direct"
        }
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        /**
         * АРХИТЕКТУРЫ ЗАДАЮТСЯ ПО ТИПУ СБОРКИ, а не общим списком.
         *
         * Отладочная нужна одному человеку и на одном телефоне (arm64),
         * а весит втрое меньше и ставится втрое быстрее. Релизная
         * обязана быть универсальной: её ставят люди, и на чём — нам
         * неизвестно.
         *
         * Списки НЕ объединяются между defaultConfig и типом сборки —
         * они складываются. Поэтому в defaultConfig ничего нет: иначе
         * отладочная получила бы все три обратно.
         *
         * Релизный список обязан совпадать с целями в
         * engine/build-aar.sh --all. Расхождение ловит проверка в конце
         * этого файла: она не даст собрать релиз, если в engine.aar
         * архитектур меньше трёх.
         *
         * Почему список вообще нужен: без него в сборку попадает ещё и
         * x86 — его тянет Compose, у которого своя нативная библиотека
         * собрана под четыре архитектуры, а наш движок под три. Play
         * нарезал бы x86-вариант с библиотекой Compose и БЕЗ движка:
         * он установился бы и упал при первом подключении.
         */
        debug {
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
        }

        release {
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }

            // R8 ВКЛЮЧЁН. Правила — в proguard-rules.pro, там же
            // разобрано, почему без них приложение соберётся и упадёт
            // при первом обращении к движку: вызовы идут через JNI по
            // именам, а их R8 не видит.
            // ОТЛАДОЧНЫЕ СИМВОЛЫ НАТИВНОГО КОДА — ДЛЯ PLAY.
            //
            // Play просит их отдельным предупреждением на каждой
            // загрузке бандла с нативным кодом. Без них падение внутри
            // движка приходит стопкой голых адресов, по которой не
            // понять даже, в какой функции оно случилось.
            //
            // Символы едут ОТДЕЛЬНЫМ разделом бандла и человеку НЕ
            // достаются: Play разбирает их у себя и удаляет из того, что раздаёт.
            // То есть на размер установки это не влияет.
            //
            // FULL, а не SYMBOL_TABLE: у нашей библиотеки есть и
            // .debug_line, значит Play сможет назвать не только функцию,
            // но и строку. Проверено llvm-readelf: секции .symtab,
            // .debug_info и .debug_line на месте.
            //
            // Из этого же следует, что в сборку движка НЕЛЬЗЯ добавлять
            // -ldflags="-s -w" ради размера: это выбросит ровно те
            // секции, ради которых здесь стоит FULL.
            ndk {
                debugSymbolLevel = "FULL"
            }

            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )

            // Подпись: боевая, если есть keystore.properties, иначе
            // ОТЛАДОЧНАЯ.
            //
            // Не «никакая» намеренно. Неподписанный APK невозможно
            // установить, а релизную сборку надо проверять на
            // устройстве — R8 ломает вызовы через JNI не при сборке, а
            // при работе (задача 56.6). С отладочной подписью
            // получается APK, который ставится и который можно
            // испытать, не трогая боевой ключ.
            //
            // Ошибиться и выложить такой в Play нельзя: отладочные
            // сертификаты Play отвергает на загрузке. Плюс сборка
            // предупреждает об этом отдельной строкой.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")


            // Отладочного в релизе быть не должно: тестовый пароль,
            // засев ссылок VK, ссылка на бота. Читается как
            // BuildConfig.DEBUG в коде.
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // Нужен для BuildConfig.DEBUG: по нему из релиза убирается
        // тестовый пароль, засев ссылок VK и ссылка на бота (56.4).
        buildConfig = true
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)

    // PLAY BILLING — ТОЛЬКО В ВАРИАНТЕ PLAY.
    //
    // playImplementation, а не implementation: в сборку для прямой
    // раздачи библиотека не попадает вовсе. Это то же правило, что и с
    // Purchase.kt — не флаг в коде, а отсутствие кода.
    //
    // Заодно снимает вопрос ревью: в direct-сборке нет ни классов
    // биллинга, ни разрешения com.android.vending.BILLING.
    "playImplementation"(libs.play.billing)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
/**
 * Предупреждение об отсутствии подписи.
 *
 * Пишется в конце сборки, а не при настройке: при настройке его никто
 * не видит за потоком строк Gradle, а неподписанный .aab Play не
 * примет — и узнать об этом на загрузке дороже, чем здесь.
 */
gradle.taskGraph.whenReady {
    val releasing = allTasks.any { it.name.contains("Release") }
    if (releasing && keystoreProps == null) {
        logger.warn(
            "ВНИМАНИЕ: keystore.properties не найден — релиз подписан ОТЛАДОЧНЫМ ключом и в Play не пойдёт. " +
                "Образец: keystore.properties.example"
        )
    }
}

/**
 * ЗАЩИТА ОТ РЕЛИЗА С ОДНОЙ АРХИТЕКТУРОЙ.
 *
 * Отладочный AAR собирается только под arm64 — так втрое быстрее. Релиз
 * обязан идти со всеми тремя, иначе Play нарежет варианты, в которых
 * движка нет вовсе: приложение установится и упадёт при подключении.
 *
 * Разница между этими двумя AAR по имени файла не видна никак — он один
 * и тот же, app/libs/engine.aar. Значит полагаться на память нельзя, и
 * проверка смотрит внутрь.
 *
 * Лечится одной командой:  bash engine/build-aar.sh --all
 */
gradle.taskGraph.whenReady {
    val releasing = allTasks.any { it.name.contains("Release") }
    if (!releasing) return@whenReady

    val aar = rootProject.file("app/libs/engine.aar")
    if (!aar.exists()) {
        throw GradleException("нет app/libs/engine.aar — собери: bash engine/build-aar.sh --all")
    }
    val need = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
    val have = ZipFile(aar).use { zip ->
        zip.entries().toList()
            .map { it.name }
            .filter { it.endsWith("/libgojni.so") }
            .map { it.removePrefix("jni/").removeSuffix("/libgojni.so") }
    }
    val missing = need - have.toSet()
    if (missing.isNotEmpty()) {
        throw GradleException(
            "engine.aar собран не под все архитектуры: есть ${have.joinToString()}, " +
                "нет ${missing.joinToString()}. " +
                "Пересобери: bash engine/build-aar.sh --all"
        )
    }
    logger.lifecycle("движок в релизе: ${have.sorted().joinToString()}")
}

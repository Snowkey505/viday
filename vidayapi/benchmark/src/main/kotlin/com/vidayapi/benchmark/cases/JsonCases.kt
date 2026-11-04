package com.vidayapi.benchmark.cases

import com.vidayapi.benchmark.core.BenchmarkCase
import com.vidayapi.benchmark.core.ConcurrentBenchmarkCase
import com.vidayapi.benchmark.core.NamedBenchmarkCase
import com.vidayapi.benchmark.core.Scenario
import kotlinx.serialization.json.Json
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * Набор микробенчмарк-кейсов ЛР3: сравнение ДВУХ библиотек JSON-сериализации
 * на ТРЁХ моделях полезной нагрузки (x1 / x4 / x8).
 *
 *   Jackson 3 (tools.jackson + jackson-module-kotlin)      — «production»-библиотека
 *                                                            проекта vidayapi (runtime-рефлексия);
 *   kotlinx.serialization (compile-time сериализация)      — официальный Kotlin-подход.
 *
 * Для каждой библиотеки и каждой модели по три сценария (Требование 5):
 *   read  — чтение: JSON-строка → объект (deserialize);
 *   write — запись: объект → JSON-строка (serialize);
 *   mixed — одновременные чтение и запись (2 потока, 1 ядро).
 *
 * Итого 3 модели × 2 библиотеки × 3 сценария = 18 кейсов.
 * Имя кейса: <сценарий>.<библиотека>.<модель>, например read.jackson.x4.
 */

private val kotlinxJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

// ---------------------------------------------------------------------------
// Jackson 3
// ---------------------------------------------------------------------------

class JsonReadJacksonCase(private val spec: ModelSpec) : BenchmarkCase {
    override val name = "read.jackson.${spec.name}"
    override val description = "Jackson 3: JSON → объект (deserialize), модель ${spec.name} (${spec.fieldCount} полей)"
    override val scenario = Scenario.READ
    override val direction = "read"
    override val library = "jackson"
    override val model = spec.name
    override val fieldCount = spec.fieldCount

    private lateinit var mapper: ObjectMapper
    private lateinit var json: String

    override fun setUp() {
        mapper = jacksonObjectMapper()
        json = spec.fixtureJson()
    }

    override fun operation(): Any? = mapper.readValue(json, spec.javaType)
}

class JsonWriteJacksonCase(private val spec: ModelSpec) : BenchmarkCase {
    override val name = "write.jackson.${spec.name}"
    override val description = "Jackson 3: объект → JSON (serialize), модель ${spec.name} (${spec.fieldCount} полей)"
    override val scenario = Scenario.WRITE
    override val direction = "write"
    override val library = "jackson"
    override val model = spec.name
    override val fieldCount = spec.fieldCount

    private lateinit var mapper: ObjectMapper

    override fun setUp() {
        mapper = jacksonObjectMapper()
    }

    override fun operation(): Any? = mapper.writeValueAsString(spec.sample())
}

class JsonMixedJacksonCase(private val spec: ModelSpec) : ConcurrentBenchmarkCase {
    override val name = "mixed.jackson.${spec.name}"
    override val description = "Jackson 3: чтение (1 поток) + запись (2-й поток), модель ${spec.name} (${spec.fieldCount} полей)"
    override val scenario = Scenario.MIXED
    override val direction = "mixed"
    override val library = "jackson"
    override val model = spec.name
    override val fieldCount = spec.fieldCount

    private lateinit var mapper: ObjectMapper
    private lateinit var json: String

    override fun setUp() {
        mapper = jacksonObjectMapper()
        json = spec.fixtureJson()
    }

    override fun readerOperation(): Any? = mapper.readValue(json, spec.javaType)
    override fun writerOperation(): Any? = mapper.writeValueAsString(spec.sample())
}

// ---------------------------------------------------------------------------
// kotlinx.serialization
// ---------------------------------------------------------------------------

class JsonReadKotlinxCase(private val spec: ModelSpec) : BenchmarkCase {
    override val name = "read.kotlinx.${spec.name}"
    override val description = "kotlinx.serialization: JSON → объект (decodeFromString), модель ${spec.name} (${spec.fieldCount} полей)"
    override val scenario = Scenario.READ
    override val direction = "read"
    override val library = "kotlinx"
    override val model = spec.name
    override val fieldCount = spec.fieldCount

    private lateinit var json: String

    override fun setUp() {
        json = spec.fixtureJson()
    }

    override fun operation(): Any? = kotlinxJson.decodeFromString(spec.serializer, json)
}

class JsonWriteKotlinxCase(private val spec: ModelSpec) : BenchmarkCase {
    override val name = "write.kotlinx.${spec.name}"
    override val description = "kotlinx.serialization: объект → JSON (encodeToString), модель ${spec.name} (${spec.fieldCount} полей)"
    override val scenario = Scenario.WRITE
    override val direction = "write"
    override val library = "kotlinx"
    override val model = spec.name
    override val fieldCount = spec.fieldCount

    override fun operation(): Any? = kotlinxJson.encodeToString(spec.serializer, spec.sample())
}

class JsonMixedKotlinxCase(private val spec: ModelSpec) : ConcurrentBenchmarkCase {
    override val name = "mixed.kotlinx.${spec.name}"
    override val description = "kotlinx.serialization: чтение (1 поток) + запись (2-й поток), модель ${spec.name} (${spec.fieldCount} полей)"
    override val scenario = Scenario.MIXED
    override val direction = "mixed"
    override val library = "kotlinx"
    override val model = spec.name
    override val fieldCount = spec.fieldCount

    private lateinit var json: String

    override fun setUp() {
        json = spec.fixtureJson()
    }

    override fun readerOperation(): Any? = kotlinxJson.decodeFromString(spec.serializer, json)
    override fun writerOperation(): Any? = kotlinxJson.encodeToString(spec.serializer, spec.sample())
}

// ---------------------------------------------------------------------------
// Реестр кейсов: 3 модели × 2 библиотеки × 3 сценария = 18 кейсов
// ---------------------------------------------------------------------------

object JsonCases {

    fun all(): List<NamedBenchmarkCase> = BenchmarkModels.all.flatMap { spec ->
        listOf(
            JsonReadJacksonCase(spec),
            JsonReadKotlinxCase(spec),
            JsonWriteJacksonCase(spec),
            JsonWriteKotlinxCase(spec),
            JsonMixedJacksonCase(spec),
            JsonMixedKotlinxCase(spec),
        )
    }
}
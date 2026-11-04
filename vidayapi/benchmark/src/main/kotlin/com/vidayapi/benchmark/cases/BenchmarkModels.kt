package com.vidayapi.benchmark.cases

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Три сравниваемые JSON-модели микробенчмарка (новое требование):
 *
 *   x1 — исходная модель (10 полей, как в ЛР3);
 *   x4 — модель в 4 раза больше по числу полей (40);
 *   x8 — модель в 8 раз больше по числу полей (80).
 *
 * Поля намеренно РАЗНЫЕ по типам — покрыты все возможные случаи JSON:
 * примитивы (Int/Long/Short/Byte/Double/Float/Boolean/Char/String), enum,
 * списки (List<T>), вложенные списки (List<List<T>>, List<List<List<T>>>),
 * массивы (IntArray/LongArray/DoubleArray/FloatArray/BooleanArray/CharArray/
 * ByteArray/ShortArray/Array<String>), map (Map<String, T>), map-of-map,
 * map со списками, вложенные объекты, списки объектов, списки списков
 * объектов, map объектов и т.д.
 *
 * Обе библиотеки (Jackson 3 + jackson-module-kotlin и kotlinx.serialization)
 * работают с ОДНИМ И ТЕМ ЖЕ объектом/JSON-текстом каждой модели — сравнение
 * честное (см. [ModelSpec] и [BenchmarkModels]).
 */

@Serializable
enum class PayloadKind { VIDEO, AUDIO, PLAYLIST, CHANNEL, USER }

// ---------------------------------------------------------------------------
// Общие вложенные DTO (используются моделями x1/x4/x8)
// ---------------------------------------------------------------------------

@Serializable
data class MediaVariantDto(
    val width: Int,
    val height: Int,
    val bitrateKbps: Int,
    val codec: String,
    val isSource: Boolean = false,
)

@Serializable
data class OwnerDto(
    val id: Int,
    val name: String,
    val email: String,
    val isVerified: Boolean,
    val followers: Int,
    val joinedAt: String,
)

@Serializable
data class ChapterDto(
    val title: String,
    val startMs: Int,
    val endMs: Int,
    val isSpoiler: Boolean,
)

@Serializable
data class PlaylistDto(
    val id: Int,
    val name: String,
    val isPublic: Boolean,
    val itemCount: Int,
    val createdAt: String,
)

@Serializable
data class ChannelDto(
    val id: Int,
    val handle: String,
    val name: String,
    val subscriberCount: Long,
    val isVerified: Boolean,
)

@Serializable
data class RelatedDto(
    val id: Int,
    val title: String,
    val kind: PayloadKind,
    val score: Double,
)

// ---------------------------------------------------------------------------
// x1 — исходная модель (10 полей)
// ---------------------------------------------------------------------------

@Serializable
data class BenchmarkPayloadX1(
    val id: Int,
    val title: String,
    val description: String,
    val ownerId: Int,
    val accessType: String,
    val durationSeconds: Int,
    val createdAt: String,
    val tags: List<String>,
    val stats: Map<String, Int>,
    val variants: List<MediaVariantDto> = emptyList(),
)

// ---------------------------------------------------------------------------
// x4 — модель в 4 раза больше (40 полей)
// ---------------------------------------------------------------------------

@Serializable
data class BenchmarkPayloadX4(
    // примитивы (12)
    val id: Int,
    val idLong: Long,
    val idShort: Short,
    val idByte: Byte,
    val rating: Double,
    val ratingFloat: Float,
    val isPremium: Boolean,
    val kind: PayloadKind,
    val title: String,
    val code: Char,
    val createdAt: String,
    val accessType: String,
    // списки (10)
    val tags: List<String>,
    val ids: List<Int>,
    val scores: List<Double>,
    val flags: List<Boolean>,
    val durations: List<Long>,
    val names: List<String>,
    val heights: List<Float>,
    val codes: List<Char>,
    val kinds: List<PayloadKind>,
    val nestedInts: List<List<Int>>,
    // массивы (8)
    val intArray: IntArray,
    val longArray: LongArray,
    val doubleArray: DoubleArray,
    val floatArray: FloatArray,
    val booleanArray: BooleanArray,
    val charArray: CharArray,
    val byteArray: ByteArray,
    val stringArray: Array<String>,
    // map (6)
    val stats: Map<String, Int>,
    val labels: Map<String, String>,
    val weights: Map<String, Double>,
    val counters: Map<String, Long>,
    val nestedMap: Map<String, List<Int>>,
    val flagsMap: Map<String, Boolean>,
    // вложенные объекты (4)
    val owner: OwnerDto,
    val variants: List<MediaVariantDto>,
    val chapters: List<ChapterDto>,
    val playlist: PlaylistDto,
)

// ---------------------------------------------------------------------------
// x8 — модель в 8 раз больше (80 полей)
// ---------------------------------------------------------------------------

@Serializable
data class BenchmarkPayloadX8(
    // примитивы (20)
    val id: Int,
    val idLong: Long,
    val idShort: Short,
    val idByte: Byte,
    val idBig: Long,
    val rating: Double,
    val ratingFloat: Float,
    val isPremium: Boolean,
    val isActive: Boolean,
    val kind: PayloadKind,
    val title: String,
    val subtitle: String,
    val code: Char,
    val locale: Char,
    val createdAt: String,
    val updatedAt: String,
    val accessType: String,
    val status: String,
    val version: Int,
    val priority: Int,
    // списки (20)
    val tags: List<String>,
    val ids: List<Int>,
    val scores: List<Double>,
    val flags: List<Boolean>,
    val durations: List<Long>,
    val names: List<String>,
    val heights: List<Float>,
    val codes: List<Char>,
    val kinds: List<PayloadKind>,
    val nestedInts: List<List<Int>>,
    val nestedStrings: List<List<String>>,
    val deepNested: List<List<List<Int>>>,
    val matrix: List<List<Double>>,
    val pairs: List<List<Boolean>>,
    val tripleStrings: List<List<List<String>>>,
    val shortIds: List<Short>,
    val byteIds: List<Byte>,
    val ratings: List<Float>,
    val charMatrix: List<List<Char>>,
    val kindMatrix: List<List<PayloadKind>>,
    // массивы (12)
    val intArray: IntArray,
    val longArray: LongArray,
    val doubleArray: DoubleArray,
    val floatArray: FloatArray,
    val booleanArray: BooleanArray,
    val charArray: CharArray,
    val byteArray: ByteArray,
    val shortArray: ShortArray,
    val stringArray: Array<String>,
    val intArray2: IntArray,
    val doubleArray2: DoubleArray,
    val stringArray2: Array<String>,
    // map (12)
    val stats: Map<String, Int>,
    val labels: Map<String, String>,
    val weights: Map<String, Double>,
    val counters: Map<String, Long>,
    val nestedMap: Map<String, List<Int>>,
    val flagsMap: Map<String, Boolean>,
    val deepMap: Map<String, Map<String, Int>>,
    val listMap: Map<String, List<String>>,
    val doubleMap: Map<String, Map<String, Double>>,
    val boolMap: Map<String, Boolean>,
    val longMap: Map<String, Long>,
    val charMap: Map<String, Char>,
    // вложенные объекты (16)
    val owner: OwnerDto,
    val variants: List<MediaVariantDto>,
    val chapters: List<ChapterDto>,
    val playlist: PlaylistDto,
    val channel: ChannelDto,
    val related: List<RelatedDto>,
    val nestedVariants: List<List<MediaVariantDto>>,
    val ownerList: List<OwnerDto>,
    val chapterMap: Map<String, ChapterDto>,
    val variantMap: Map<String, MediaVariantDto>,
    val deepOwner: OwnerDto,
    val deepPlaylist: PlaylistDto,
    val variantMatrix: List<List<ChapterDto>>,
    val ownerMapList: List<Map<String, OwnerDto>>,
    val playlistList: List<PlaylistDto>,
    val channelList: List<ChannelDto>,
)

// ---------------------------------------------------------------------------
// Спецификация модели для харнесса
// ---------------------------------------------------------------------------

/**
 * «Паспорт» модели: имя (x1/x4/x8), число полей, фабрика образца,
 * генератор JSON-фикстуры (одинаковые байты для ОБЕИХ библиотек),
 * kotlinx-сериализатор и Java-тип для Jackson.
 */
class ModelSpec(
    val name: String,
    val fieldCount: Int,
    val sample: () -> Any,
    val fixtureJson: () -> String,
    val serializer: KSerializer<Any>,
    val javaType: Class<Any>,
)

/**
 * Реестр трёх сравниваемых моделей. JSON-фикстура для сценария «чтение»
 * генерируется kotlinx-сериализатором из образца — обе библиотеки разбирают
 * ровно одни и те же байты (как и в исходной ЛР3, но без рукописного JSON).
 */
object BenchmarkModels {

    private val kotlinxJson: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ------------------------------------------------------------------ x1

    fun x1Sample(): BenchmarkPayloadX1 = BenchmarkPayloadX1(
        id = 10042,
        title = "Viday benchmark video",
        description = "Описание ролика для микробенчмарка сериализации (ЛР3).",
        ownerId = 7,
        accessType = "PUBLIC",
        durationSeconds = 186,
        createdAt = "2026-10-21T18:00:00Z",
        tags = listOf("kotlin", "benchmark", "viday", "serialization"),
        stats = mapOf("views" to 12345, "likes" to 678, "playlistAdds" to 91),
        variants = listOf(
            MediaVariantDto(1920, 1080, 4500, "H264", isSource = true),
            MediaVariantDto(1280, 720, 2500, "H264"),
            MediaVariantDto(640, 360, 1000, "VP9"),
        ),
    )

    // ------------------------------------------------------------------ x4

    fun x4Sample(): BenchmarkPayloadX4 = BenchmarkPayloadX4(
        id = 10042,
        idLong = 100_000_000_042L,
        idShort = 42,
        idByte = 7,
        rating = 4.75,
        ratingFloat = 4.5f,
        isPremium = true,
        kind = PayloadKind.VIDEO,
        title = "Viday benchmark video (x4)",
        code = 'V',
        createdAt = "2026-10-21T18:00:00Z",
        accessType = "PUBLIC",
        tags = listOf("kotlin", "benchmark", "viday", "serialization", "json", "x4"),
        ids = listOf(1, 2, 3, 4, 5, 6, 7, 8),
        scores = listOf(1.5, 2.5, 3.5, 4.5, 5.5),
        flags = listOf(true, false, true, true, false),
        durations = listOf(60L, 120L, 180L, 240L),
        names = listOf("alpha", "beta", "gamma", "delta"),
        heights = listOf(1080.0f, 720.0f, 480.0f, 360.0f),
        codes = listOf('A', 'B', 'C', 'D'),
        kinds = listOf(PayloadKind.VIDEO, PayloadKind.AUDIO, PayloadKind.PLAYLIST),
        nestedInts = listOf(listOf(1, 2, 3), listOf(4, 5), listOf(6, 7, 8, 9)),
        intArray = intArrayOf(10, 20, 30, 40, 50),
        longArray = longArrayOf(100L, 200L, 300L),
        doubleArray = doubleArrayOf(1.1, 2.2, 3.3, 4.4),
        floatArray = floatArrayOf(1.5f, 2.5f, 3.5f),
        booleanArray = booleanArrayOf(true, false, true),
        charArray = charArrayOf('x', 'y', 'z'),
        byteArray = byteArrayOf(1, 2, 3, 4),
        stringArray = arrayOf("one", "two", "three"),
        stats = mapOf("views" to 12345, "likes" to 678, "playlistAdds" to 91),
        labels = mapOf("title" to "Viday", "codec" to "H264", "status" to "ready"),
        weights = mapOf("audio" to 0.3, "video" to 0.7),
        counters = mapOf("plays" to 1000L, "shares" to 42L),
        nestedMap = mapOf("even" to listOf(2, 4, 6), "odd" to listOf(1, 3, 5)),
        flagsMap = mapOf("premium" to true, "featured" to false),
        owner = OwnerDto(7, "Viday Studio", "studio@viday.example", true, 12_345, "2024-01-15T00:00:00Z"),
        variants = listOf(
            MediaVariantDto(1920, 1080, 4500, "H264", isSource = true),
            MediaVariantDto(1280, 720, 2500, "H264"),
            MediaVariantDto(640, 360, 1000, "VP9"),
        ),
        chapters = listOf(
            ChapterDto("Intro", 0, 15_000, false),
            ChapterDto("Main", 15_000, 120_000, false),
            ChapterDto("Spoiler", 120_000, 150_000, true),
        ),
        playlist = PlaylistDto(9, "Benchmark playlist", true, 42, "2026-10-01T00:00:00Z"),
    )

    // ------------------------------------------------------------------ x8

    fun x8Sample(): BenchmarkPayloadX8 = BenchmarkPayloadX8(
        id = 10042,
        idLong = 100_000_000_042L,
        idShort = 42,
        idByte = 7,
        idBig = 9_000_000_000_000_000_000L,
        rating = 4.75,
        ratingFloat = 4.5f,
        isPremium = true,
        isActive = true,
        kind = PayloadKind.VIDEO,
        title = "Viday benchmark video (x8)",
        subtitle = "Расширенная модель для микробенчмарка",
        code = 'V',
        locale = 'R',
        createdAt = "2026-10-21T18:00:00Z",
        updatedAt = "2026-10-22T09:30:00Z",
        accessType = "PUBLIC",
        status = "READY",
        version = 3,
        priority = 1,
        tags = listOf("kotlin", "benchmark", "viday", "serialization", "json", "x8", "nested", "deep"),
        ids = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12),
        scores = listOf(1.5, 2.5, 3.5, 4.5, 5.5, 6.5, 7.5),
        flags = listOf(true, false, true, true, false, true, false, false),
        durations = listOf(60L, 120L, 180L, 240L, 300L, 360L),
        names = listOf("alpha", "beta", "gamma", "delta", "epsilon", "zeta"),
        heights = listOf(2160.0f, 1080.0f, 720.0f, 480.0f, 360.0f),
        codes = listOf('A', 'B', 'C', 'D', 'E', 'F'),
        kinds = listOf(PayloadKind.VIDEO, PayloadKind.AUDIO, PayloadKind.PLAYLIST, PayloadKind.CHANNEL),
        nestedInts = listOf(listOf(1, 2, 3), listOf(4, 5), listOf(6, 7, 8, 9), listOf(10)),
        nestedStrings = listOf(listOf("a", "b"), listOf("c", "d", "e")),
        deepNested = listOf(listOf(listOf(1, 2), listOf(3)), listOf(listOf(4, 5, 6))),
        matrix = listOf(listOf(1.0, 2.0), listOf(3.0, 4.0, 5.0)),
        pairs = listOf(listOf(true, false), listOf(false, true, true)),
        tripleStrings = listOf(listOf(listOf("x", "y"), listOf("z")), listOf(listOf("w"))),
        shortIds = listOf(1, 2, 3, 4, 5),
        byteIds = listOf(1, 2, 3),
        ratings = listOf(1.0f, 2.0f, 3.0f, 4.0f, 5.0f),
        charMatrix = listOf(listOf('a', 'b'), listOf('c', 'd', 'e')),
        kindMatrix = listOf(listOf(PayloadKind.VIDEO, PayloadKind.AUDIO), listOf(PayloadKind.PLAYLIST)),
        intArray = intArrayOf(10, 20, 30, 40, 50, 60),
        longArray = longArrayOf(100L, 200L, 300L, 400L),
        doubleArray = doubleArrayOf(1.1, 2.2, 3.3, 4.4, 5.5),
        floatArray = floatArrayOf(1.5f, 2.5f, 3.5f, 4.5f),
        booleanArray = booleanArrayOf(true, false, true, false, true),
        charArray = charArrayOf('x', 'y', 'z', 'w'),
        byteArray = byteArrayOf(1, 2, 3, 4, 5, 6, 7),
        shortArray = shortArrayOf(10, 20, 30),
        stringArray = arrayOf("one", "two", "three", "four"),
        intArray2 = intArrayOf(7, 8, 9),
        doubleArray2 = doubleArrayOf(0.5, 1.5, 2.5),
        stringArray2 = arrayOf("red", "green", "blue"),
        stats = mapOf("views" to 12345, "likes" to 678, "playlistAdds" to 91, "shares" to 42),
        labels = mapOf("title" to "Viday", "codec" to "H264", "status" to "ready", "lang" to "ru"),
        weights = mapOf("audio" to 0.3, "video" to 0.7, "subs" to 0.1),
        counters = mapOf("plays" to 1000L, "shares" to 42L, "favorites" to 7L),
        nestedMap = mapOf("even" to listOf(2, 4, 6), "odd" to listOf(1, 3, 5), "prime" to listOf(2, 3, 5, 7)),
        flagsMap = mapOf("premium" to true, "featured" to false, "archived" to false),
        deepMap = mapOf("video" to mapOf("h264" to 1, "vp9" to 2), "audio" to mapOf("aac" to 3)),
        listMap = mapOf("tags" to listOf("a", "b"), "genres" to listOf("c", "d", "e")),
        doubleMap = mapOf("q1" to mapOf("views" to 100.5, "likes" to 10.25), "q2" to mapOf("views" to 200.75)),
        boolMap = mapOf("premium" to true, "featured" to false, "beta" to true),
        longMap = mapOf("total" to 1_000_000L, "delta" to 42L),
        charMap = mapOf("first" to 'A', "last" to 'Z'),
        owner = OwnerDto(7, "Viday Studio", "studio@viday.example", true, 12_345, "2024-01-15T00:00:00Z"),
        variants = listOf(
            MediaVariantDto(1920, 1080, 4500, "H264", isSource = true),
            MediaVariantDto(1280, 720, 2500, "H264"),
            MediaVariantDto(640, 360, 1000, "VP9"),
            MediaVariantDto(3840, 2160, 12000, "HEVC"),
        ),
        chapters = listOf(
            ChapterDto("Intro", 0, 15_000, false),
            ChapterDto("Main", 15_000, 120_000, false),
            ChapterDto("Spoiler", 120_000, 150_000, true),
            ChapterDto("Outro", 150_000, 186_000, false),
        ),
        playlist = PlaylistDto(9, "Benchmark playlist", true, 42, "2026-10-01T00:00:00Z"),
        channel = ChannelDto(3, "@viday", "Viday Channel", 1_000_000L, true),
        related = listOf(
            RelatedDto(11, "Related video 1", PayloadKind.VIDEO, 0.9),
            RelatedDto(12, "Related audio", PayloadKind.AUDIO, 0.7),
        ),
        nestedVariants = listOf(
            listOf(MediaVariantDto(1920, 1080, 4500, "H264"), MediaVariantDto(1280, 720, 2500, "H264")),
            listOf(MediaVariantDto(640, 360, 1000, "VP9")),
        ),
        ownerList = listOf(
            OwnerDto(1, "Alice", "alice@viday.example", true, 100, "2023-01-01T00:00:00Z"),
            OwnerDto(2, "Bob", "bob@viday.example", false, 50, "2023-06-01T00:00:00Z"),
        ),
        chapterMap = mapOf(
            "intro" to ChapterDto("Intro", 0, 15_000, false),
            "main" to ChapterDto("Main", 15_000, 120_000, false),
        ),
        variantMap = mapOf(
            "source" to MediaVariantDto(1920, 1080, 4500, "H264", isSource = true),
            "mobile" to MediaVariantDto(640, 360, 1000, "VP9"),
        ),
        deepOwner = OwnerDto(7, "Viday Studio", "studio@viday.example", true, 12_345, "2024-01-15T00:00:00Z"),
        deepPlaylist = PlaylistDto(9, "Benchmark playlist", true, 42, "2026-10-01T00:00:00Z"),
        variantMatrix = listOf(
            listOf(ChapterDto("A1", 0, 10_000, false), ChapterDto("A2", 10_000, 20_000, false)),
            listOf(ChapterDto("B1", 0, 5_000, true)),
        ),
        ownerMapList = listOf(
            mapOf("owner" to OwnerDto(1, "Alice", "alice@viday.example", true, 100, "2023-01-01T00:00:00Z")),
            mapOf("owner" to OwnerDto(2, "Bob", "bob@viday.example", false, 50, "2023-06-01T00:00:00Z")),
        ),
        playlistList = listOf(
            PlaylistDto(1, "Favorites", true, 10, "2026-01-01T00:00:00Z"),
            PlaylistDto(2, "Watch later", false, 3, "2026-02-01T00:00:00Z"),
        ),
        channelList = listOf(
            ChannelDto(3, "@viday", "Viday Channel", 1_000_000L, true),
            ChannelDto(4, "@other", "Other Channel", 500L, false),
        ),
    )

    // ------------------------------------------------------------------ реестр

    val x1: ModelSpec = ModelSpec(
        name = "x1",
        fieldCount = 10,
        sample = { x1Sample() },
        fixtureJson = { kotlinxJson.encodeToString(BenchmarkPayloadX1.serializer(), x1Sample()) },
        serializer = BenchmarkPayloadX1.serializer() as KSerializer<Any>,
        javaType = BenchmarkPayloadX1::class.java as Class<Any>,
    )

    val x4: ModelSpec = ModelSpec(
        name = "x4",
        fieldCount = 40,
        sample = { x4Sample() },
        fixtureJson = { kotlinxJson.encodeToString(BenchmarkPayloadX4.serializer(), x4Sample()) },
        serializer = BenchmarkPayloadX4.serializer() as KSerializer<Any>,
        javaType = BenchmarkPayloadX4::class.java as Class<Any>,
    )

    val x8: ModelSpec = ModelSpec(
        name = "x8",
        fieldCount = 80,
        sample = { x8Sample() },
        fixtureJson = { kotlinxJson.encodeToString(BenchmarkPayloadX8.serializer(), x8Sample()) },
        serializer = BenchmarkPayloadX8.serializer() as KSerializer<Any>,
        javaType = BenchmarkPayloadX8::class.java as Class<Any>,
    )

    /** Все три модели в порядке сравнения: x1 → x4 → x8. */
    val all: List<ModelSpec> = listOf(x1, x4, x8)
}
package de.hyprnode.slimy.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.listSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.modules.SerializersModule
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

// JSON exactly as the iPhone app's Codable model writes it, so saves,
// `.slimysave` exports and Care Together snapshots move between platforms.
//
// - Dates are `JsonDate` (an `Instant`): seconds since 1970 as a number in the
//   save and in exported files (`GameStorage.encoder` on iOS), ISO 8601 without
//   fractions in everything that goes through the server (`HyprnodeAPI`).
//   Pick the matching `Json` from `SlimyJson`; both read either form.
// - Swift writes a `[SomeEnum: V]` dictionary as a flat array
//   `[key1, value1, key2, value2, …]` (`FlatMapSerializer`).
// - nil optionals are left out; every other field is always written.
// - Decoding is tolerant like Swift's `decodeIfPresent` + defaults: missing or
//   damaged fields fall back to their default (`TolerantSerializer`).

/** A date in the model. Serialized by the `Json` instance's date strategy. */
typealias JsonDate = @Contextual Instant

/** Reads a date written as seconds since 1970 or as ISO 8601. */
private fun decodeDate(element: JsonElement): Instant {
    val primitive = element as? JsonPrimitive ?: throw SerializationException("Date expected")
    if (primitive.isString) {
        val text = primitive.content
        return runCatching { Instant.parse(text) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant() }.getOrNull()
            ?: throw SerializationException("Unreadable date: $text")
    }
    // Read the number exactly (not through a Double), so a date written here
    // comes back to the nanosecond and Swift's fractional seconds keep theirs.
    val exact = runCatching { BigDecimal(primitive.content) }.getOrNull()
        ?: throw SerializationException("Date expected")
    return runCatching {
        val scaled = exact.setScale(9, RoundingMode.HALF_UP)
        val whole = scaled.setScale(0, RoundingMode.FLOOR)
        val nanos = scaled.subtract(whole).movePointRight(9).toLong()
        Instant.ofEpochSecond(whole.longValueExact(), nanos)
    }.getOrElse { throw SerializationException("Date out of range") }
}

/** `dateEncodingStrategy = .secondsSince1970`: a plain number, never in exponent form. */
@OptIn(ExperimentalSerializationApi::class)
object SecondsSince1970Serializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("SlimyDate.seconds", PrimitiveKind.DOUBLE)

    override fun serialize(encoder: Encoder, value: Instant) {
        val seconds = BigDecimal.valueOf(value.epochSecond).add(BigDecimal.valueOf(value.nano.toLong(), 9))
            .stripTrailingZeros()
        val text = if (seconds.scale() <= 0) seconds.toBigInteger().toString() else seconds.toPlainString()
        if (encoder is JsonEncoder) encoder.encodeJsonElement(JsonUnquotedLiteral(text))
        else encoder.encodeDouble(value.timeIntervalSince1970)
    }

    override fun deserialize(decoder: Decoder): Instant =
        if (decoder is JsonDecoder) decodeDate(decoder.decodeJsonElement()) else instantOf(decoder.decodeDouble())
}

/** `dateEncodingStrategy = .iso8601`: "2026-10-25T12:00:00Z", whole seconds, UTC. */
object Iso8601Serializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("SlimyDate.iso8601", PrimitiveKind.STRING)
    private val format = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun format(value: Instant): String = format.format(value.truncatedTo(ChronoUnit.SECONDS))

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(format(value))

    override fun deserialize(decoder: Decoder): Instant =
        if (decoder is JsonDecoder) decodeDate(decoder.decodeJsonElement()) else decodeDate(JsonPrimitive(decoder.decodeString()))
}

/** A Swift `UUID`: written as its upper-case `uuidString`, read in any case. */
object UuidSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("SlimyUUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.uuidString)
    override fun deserialize(decoder: Decoder): UUID =
        runCatching { UUID.fromString(decoder.decodeString()) }.getOrElse { throw SerializationException("Bad UUID") }
}

/**
 * A Swift dictionary whose key is an enum (not String/Int coded): written as
 * a flat array `[k1, v1, k2, v2, …]`. Reading also accepts an object.
 */
open class FlatMapSerializer<K, V>(
    private val keys: KSerializer<K>,
    private val values: KSerializer<V>,
) : KSerializer<Map<K, V>> {
    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = listSerialDescriptor(JsonElement.serializer().descriptor)

    override fun serialize(encoder: Encoder, value: Map<K, V>) {
        val json = (encoder as? JsonEncoder)?.json ?: throw SerializationException("JSON only")
        encoder.encodeJsonElement(buildJsonArray {
            for ((k, v) in value) {
                add(json.encodeToJsonElement(keys, k))
                add(json.encodeToJsonElement(values, v))
            }
        })
    }

    override fun deserialize(decoder: Decoder): Map<K, V> {
        val input = decoder as? JsonDecoder ?: throw SerializationException("JSON only")
        val out = LinkedHashMap<K, V>()
        when (val element = input.decodeJsonElement()) {
            is JsonArray -> {
                if (element.size % 2 != 0) throw SerializationException("Odd number of dictionary entries")
                var i = 0
                while (i < element.size) {
                    out[input.json.decodeFromJsonElement(keys, element[i])] =
                        input.json.decodeFromJsonElement(values, element[i + 1])
                    i += 2
                }
            }
            is JsonObject -> for ((k, v) in element) {
                out[input.json.decodeFromJsonElement(keys, JsonPrimitive(k))] = input.json.decodeFromJsonElement(values, v)
            }
            else -> throw SerializationException("Dictionary expected")
        }
        return out
    }
}

/** A list that skips elements it can't read, like Swift's `Lossy<T>` wrappers. */
open class LossyListSerializer<T>(private val element: KSerializer<T>) : KSerializer<List<T>> {
    override val descriptor: SerialDescriptor = ListSerializer(element).descriptor

    override fun serialize(encoder: Encoder, value: List<T>) = ListSerializer(element).serialize(encoder, value)

    override fun deserialize(decoder: Decoder): List<T> {
        val input = decoder as? JsonDecoder ?: return ListSerializer(element).deserialize(decoder)
        val array = input.decodeJsonElement() as? JsonArray ?: throw SerializationException("List expected")
        return array.mapNotNull { runCatching { input.json.decodeFromJsonElement(element, it) }.getOrNull() }
    }
}

/** Skins from their names, leaving out any this version doesn't know (`Skin.lossySet`). */
object LossySkinSetSerializer : KSerializer<Set<Skin>> {
    private val strings = ListSerializer(String.serializer())
    override val descriptor: SerialDescriptor = strings.descriptor
    override fun serialize(encoder: Encoder, value: Set<Skin>) = strings.serialize(encoder, value.map { it.name })
    override fun deserialize(decoder: Decoder): Set<Skin> = Skin.lossySet(strings.deserialize(decoder))
}

/**
 * The fields of one JSON object, read the way the Swift `init(from:)`
 * extensions do: `value(key, fallback)` gives the fallback when the key is
 * missing, null or can't be read as that type.
 */
class JsonFields(val json: Json, val obj: JsonObject) {
    fun has(key: String): Boolean = obj[key] != null && obj[key] !is JsonNull

    fun <T> value(key: String, serializer: KSerializer<T>, fallback: T): T {
        val element = obj[key] ?: return fallback
        if (element is JsonNull) return fallback
        return try {
            json.decodeFromJsonElement(serializer, element)
        } catch (e: Exception) {
            fallback
        }
    }

    /** Like Swift's `try c.decode(...)`: throws when missing or damaged. */
    fun <T> require(key: String, serializer: KSerializer<T>): T {
        val element = obj[key]
        if (element == null || element is JsonNull) throw SerializationException("Missing $key")
        return json.decodeFromJsonElement(serializer, element)
    }

    fun string(key: String, fallback: String): String = value(key, String.serializer(), fallback)
    fun stringOrNull(key: String): String? = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    fun int(key: String, fallback: Int): Int = value(key, Int.serializer(), fallback)
    fun double(key: String, fallback: Double): Double = value(key, Double.serializer(), fallback)
    fun bool(key: String, fallback: Boolean): Boolean = value(key, Boolean.serializer(), fallback)
    fun date(key: String, fallback: Instant): Instant = value(key, dateSerializer, fallback)
    fun dateOrNull(key: String): Instant? = value(key, dateSerializer.nullable, null)
    fun strings(key: String): List<String> = value(key, ListSerializer(String.serializer()), emptyList())

    /** The `Json`'s own date strategy (seconds or ISO 8601); reads either form. */
    @OptIn(ExperimentalSerializationApi::class)
    val dateSerializer: KSerializer<Instant>
        get() = json.serializersModule.getContextual(Instant::class) ?: SecondsSince1970Serializer
}

/**
 * A serializer that writes with the plugin-generated serializer and reads
 * field by field with fallbacks, like the Swift `init(from:)` extensions.
 */
abstract class TolerantSerializer<T>(private val generated: KSerializer<T>) : KSerializer<T> {
    override val descriptor: SerialDescriptor get() = generated.descriptor

    override fun serialize(encoder: Encoder, value: T) = generated.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): T {
        val input = decoder as? JsonDecoder ?: return generated.deserialize(decoder)
        val obj = input.decodeJsonElement() as? JsonObject ?: throw SerializationException("Object expected")
        return decode(JsonFields(input.json, obj))
    }

    abstract fun decode(fields: JsonFields): T
}

/** The `Json` configurations of the iPhone app's encoders. */
object SlimyJson {
    private fun make(dates: KSerializer<Instant>, pretty: Boolean = false) = Json {
        serializersModule = SerializersModule { contextual(Instant::class, dates) }
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
        if (pretty) {
            prettyPrint = true
            @OptIn(ExperimentalSerializationApi::class)
            prettyPrintIndent = "  "
        }
    }

    /** The save on this phone (`GameStorage.encoder`): dates as seconds since 1970. */
    val storage: Json = make(SecondsSince1970Serializer)

    /** `.slimysave` files: like the save, pretty-printed with sorted keys. */
    val saveFile: Json = make(SecondsSince1970Serializer, pretty = true)

    /** Everything that goes through the server (`HyprnodeAPI.encoder`): ISO 8601 dates. */
    val api: Json = make(Iso8601Serializer)

    /** Recursively sorts object keys, like `outputFormatting = .sortedKeys`. */
    fun sortedKeys(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.toSortedMap().mapValues { sortedKeys(it.value) })
        is JsonArray -> JsonArray(element.map { sortedKeys(it) })
        else -> element
    }
}

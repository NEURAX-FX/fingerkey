package com.phonefprint.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bouncycastle.util.encoders.Base64
import java.util.Locale

class StorageRecoveryException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal data class DeviceSnapshot(
    val identity: Identity,
    val servers: List<ServerConfig>,
    val soundEnabled: Boolean,
)

internal object SnapshotCodec {
    fun encode(snapshot: DeviceSnapshot): ByteArray = buildJsonObject {
        put("version", 1)
        put("seed", Base64.toBase64String(snapshot.identity.seed()))
        put("publicKey", snapshot.identity.publicKeyBase64)
        put("soundEnabled", snapshot.soundEnabled)
        put("servers", JsonArray(snapshot.servers.map { server ->
            buildJsonObject {
                put("id", server.id)
                put("name", server.name)
                put("host", server.host)
                put("port", server.port)
                put("fingerprint", server.fingerprint)
            }
        }))
    }.toString().toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): DeviceSnapshot {
        val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as JsonObject
        require(root["version"] == JsonPrimitive(1)) { "Unsupported snapshot version" }
        val identity = Identity(decodeBase64(root.string("seed")))
        require(identity.publicKeyBase64 == root.string("publicKey")) { "Identity public key does not match seed" }
        val sound = when (root["soundEnabled"]) {
            JsonPrimitive(true) -> true
            JsonPrimitive(false) -> false
            else -> error("Invalid sound preference")
        }
        return DeviceSnapshot(identity, nativeServers(root.getValue("servers") as JsonArray), sound)
    }

    fun nativeServers(raw: String): List<ServerConfig> =
        nativeServers(Json.parseToJsonElement(raw) as JsonArray)

    private fun nativeServers(array: JsonArray): List<ServerConfig> = deduplicate(array.map { element ->
        val item = element as JsonObject
        val fingerprint = canonicalFingerprint(item.string("fingerprint"))
        require(canonicalFingerprint(item.string("id")) == fingerprint) { "Server identity does not match fingerprint" }
        val port = item["port"] as JsonPrimitive
        require(!port.isString) { "Invalid server port" }
        canonicalServer(ServerConfig(fingerprint, item.string("name"), item.string("host"), port.content.toInt(), fingerprint))
    })

    fun legacyServers(raw: String?): List<ServerConfig> {
        if (raw == null) return emptyList()
        return deduplicate((Json.parseToJsonElement(raw) as JsonArray).map { element ->
            val item = element as JsonObject
            val address = item.string("lastAddr")
            val colon = address.lastIndexOf(':')
            require(colon > 0 && colon < address.lastIndex) { "Invalid legacy server address" }
            val host = address.substring(0, colon).removeSurrounding("[", "]")
            val fingerprint = canonicalFingerprint(item.string("fingerprint"))
            canonicalServer(ServerConfig(fingerprint, item.string("name"), host, address.substring(colon + 1).toInt(), fingerprint))
        })
    }

    fun canonicalServer(server: ServerConfig): ServerConfig = server.normalized()

    fun canonicalFingerprint(value: String): String {
        require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "Invalid server certificate fingerprint"
        }
        return value.lowercase(Locale.ROOT)
    }

    private fun deduplicate(servers: List<ServerConfig>): List<ServerConfig> =
        LinkedHashMap<String, ServerConfig>().apply {
            servers.forEach { put(it.fingerprint, it) }
        }.values.toList()

    fun decodeBase64(value: String): ByteArray = Base64.decode(value)

    private fun JsonObject.string(key: String): String {
        val value = getValue(key) as JsonPrimitive
        require(value.isString) { "Invalid $key" }
        return value.content
    }
}

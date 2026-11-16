package com.shutterstar.agenthub.storage

import com.intellij.openapi.util.JDOMUtil
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.TimeUnit

/** XML state with cross-process read/modify/write locking and atomic replacement. */
class SharedXmlStore<S : Any>(
    private val home: AgentHubHome,
    val relativePath: String,
    private val stateClass: Class<S>,
    private val factory: () -> S,
    private val schemaVersion: Int = currentSchemaVersion(factory()),
    private val statIntervalMillis: Long = 500,
    private val lockTimeoutMillis: Long = 3000,
) : StateStore<S> {
    override val persistent: Boolean get() = true
    val path: Path? = home.root?.resolve(relativePath)?.normalize()
    private val lockName = relativePath.replace('/', '-').replace('\\', '-')
    private var cached = factory()
    private var loadedStamp: String? = null
    private var checkedAt = Long.MIN_VALUE
    private var generation = 0L
    /** Last on-disk stamp this store has seen or produced itself; a different one means someone else wrote. */
    private var seenDiskStamp: String? = null
    private var externalGeneration = 0L
    private var newerVersion = false
    private var readFailed = false

    init {
        require(!Path.of(relativePath).isAbsolute && !relativePath.split('/', '\\').contains(".."))
        require(path == null || path.startsWith(home.root!!.normalize()))
    }

    @Synchronized override fun snapshot(): S {
        refresh()
        return copy(cached)
    }

    /** Reloads [cached] when the file changed on disk; cheap enough for stamp polling (no state copy). */
    private fun refresh() {
        if (path == null || home.memoryOnly) return
        val now = System.nanoTime()
        if (loadedStamp == null || checkedAt == Long.MIN_VALUE || now - checkedAt >= TimeUnit.MILLISECONDS.toNanos(statIntervalMillis)) {
            checkedAt = now
            val currentStamp = diskStamp()
            noteDiskStamp(currentStamp)
            if (loadedStamp != currentStamp) {
                try {
                    cached = read(recoverCorrupt = false)
                    loadedStamp = currentStamp
                    readFailed = false
                } catch (_: CorruptState) {
                    try {
                        if (home.prepare()) {
                            cached = home.withLock(lockName, lockTimeoutMillis) { read(recoverCorrupt = true) }
                            loadedStamp = diskStamp()
                            readFailed = false
                        }
                    } catch (_: Exception) {
                        readFailed = true
                        home.warning("AgentHub could not read its data; the previous in-memory snapshot is being shown.")
                    }
                } catch (_: Exception) {
                    readFailed = true
                    home.warning("AgentHub could not read its data; the previous in-memory snapshot is being shown.")
                }
            }
        }
    }

    @Synchronized override fun checkWritable() {
        if (path == null || !home.prepare()) return
        home.withLock(lockName, lockTimeoutMillis) {
            if (!home.withLock("format", lockTimeoutMillis) { home.canWriteFormat() }) {
                throw SharedStorageException("AgentHub data uses an unsupported format. Update AgentHub before changing it.")
            }
            read(recoverCorrupt = true)
            if (newerVersion) throw SharedStorageException("AgentHub data uses a newer schema. Update AgentHub before changing it.")
        }
    }

    @Synchronized override fun update(transform: (S) -> S): S {
        if (path == null || !home.prepare()) {
            cached = transform(copy(cached))
            generation++
            return copy(cached)
        }
        return try {
            home.withLock(lockName, lockTimeoutMillis) {
                if (!home.withLock("format", lockTimeoutMillis) { home.canWriteFormat() }) {
                    throw SharedStorageException("AgentHub data was created by a newer or unsupported plugin. Update AgentHub before changing it.")
                }
                // A write by someone else since we last looked must still count as external.
                noteDiskStamp(diskStamp())
                val latest = read(recoverCorrupt = true)
                if (newerVersion) throw SharedStorageException("AgentHub data uses a newer schema. Update AgentHub before changing it.")
                val updated = transform(latest)
                val xml = XmlStateCodecs.serialize(updated).apply { setAttribute("version", schemaVersion.toString()) }
                home.atomicWrite(path, JDOMUtil.writeElement(xml).toByteArray(Charsets.UTF_8))
                cached = copy(updated)
                loadedStamp = diskStamp()
                seenDiskStamp = loadedStamp
                checkedAt = System.nanoTime()
                readFailed = false
                generation++
                updated
            }
        } catch (_: IOException) {
            // A failed durable transaction must not look successful to a mutation caller.
            home.warning("AgentHub could not save shared data. The operation failed; retry after checking the data location.")
            throw SharedStorageException("AgentHub could not save its shared data. Check the data location and retry.")
        }
    }

    @Synchronized override fun stamp(): String {
        refresh()
        return if (path == null || home.memoryOnly) "memory:$generation" else "${loadedStamp.orEmpty()}:$readFailed"
    }

    @Synchronized override fun externalStamp(): String {
        refresh()
        return if (path == null || home.memoryOnly) "memory" else "$externalGeneration:$readFailed"
    }

    private fun noteDiskStamp(current: String) {
        if (seenDiskStamp != null && seenDiskStamp != current) externalGeneration++
        seenDiskStamp = current
    }

    /** Must be called under the store lock whenever quarantine is permitted. */
    private fun read(recoverCorrupt: Boolean): S {
        newerVersion = false
        home.requireSafePath(path!!)
        if (!Files.exists(path, NOFOLLOW_LINKS)) return factory()
        val bytes = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(MAX_XML_BYTES + 1) }
        val parsed = try {
            if (bytes.size > MAX_XML_BYTES) throw CorruptState()
            // Do not permit external entities/DTDs or non-UTF-8 encodings from shared or migrated files.
            if (containsForbiddenXmlDeclaration(bytes)) throw CorruptState()
            JDOMUtil.load(bytes.inputStream())
        } catch (_: Exception) {
            if (!recoverCorrupt) throw CorruptState()
            quarantine()
            return factory()
        }
        return try {
            val version = parsed.getAttributeValue("version")?.toIntOrNull() ?: schemaVersion
            val stateVersion = parsed.getChildren("option").firstOrNull { it.getAttributeValue("name") == "schemaVersion" }
                ?.getAttributeValue("value")?.toIntOrNull() ?: schemaVersion
            newerVersion = version > schemaVersion || stateVersion > schemaVersion
            if (newerVersion) home.warning("AgentHub data uses a newer schema; this plugin will not overwrite it.")
            require(parsed.name == stateClass.simpleName)
            XmlStateCodecs.deserialize(parsed, stateClass)
        } catch (_: Exception) {
            if (!recoverCorrupt) throw CorruptState()
            quarantine()
            factory()
        }
    }

    private fun quarantine() {
        // Preserve unknown-format data even if this older serializer cannot interpret it.
        if (newerVersion || !home.withLock("format", lockTimeoutMillis) { home.canWriteFormat() }) throw SharedStorageException("AgentHub data cannot be changed by this plugin version.")
        val target = path!!.resolveSibling("${path.fileName}.corrupt-${System.currentTimeMillis()}-${UUID.randomUUID()}")
        Files.move(path, target, ATOMIC_MOVE)
        home.warning("AgentHub found damaged state, preserved the original in a .corrupt file, and started an empty index.")
    }

    private fun diskStamp(): String = try {
        val attributes = home.safeAttributes(path!!)
        "${attributes.lastModifiedTime()}:${attributes.size()}:${attributes.fileKey()}"
    } catch (_: NoSuchFileException) { "missing" }
    catch (_: Exception) { "unavailable" }

    @Suppress("UNCHECKED_CAST")
    private fun copy(state: S): S =
        if (state is CopyableState<*>) (state as CopyableState<S>).deepCopy()
        else XmlStateCodecs.deserialize(XmlStateCodecs.serialize(state), stateClass)

    private class CorruptState : RuntimeException()

    companion object {
        private const val MAX_XML_BYTES = 32 * 1024 * 1024
        private val DOCTYPE_BYTES = "DOCTYPE".toByteArray(Charsets.US_ASCII)
        private val ENTITY_BYTES = "ENTITY".toByteArray(Charsets.US_ASCII)

        internal fun containsForbiddenXmlDeclaration(bytes: ByteArray): Boolean {
            if (bytes.size >= 2) {
                val b0 = bytes[0].toInt() and 0xFF
                val b1 = bytes[1].toInt() and 0xFF
                if ((b0 == 0xFE && b1 == 0xFF) || (b0 == 0xFF && b1 == 0xFE)) return true
            }
            var i = 0
            val limit = bytes.size
            while (i < limit) {
                val b = bytes[i]
                if (b == 0.toByte()) return true
                if (b == '<'.code.toByte() && i + 1 < limit && bytes[i + 1] == '!'.code.toByte()) {
                    if (matchesAsciiIgnoreCase(bytes, i + 2, DOCTYPE_BYTES) ||
                        matchesAsciiIgnoreCase(bytes, i + 2, ENTITY_BYTES)
                    ) {
                        return true
                    }
                }
                i++
            }
            return false
        }

        private fun matchesAsciiIgnoreCase(bytes: ByteArray, offset: Int, targetUpper: ByteArray): Boolean {
            if (offset + targetUpper.size > bytes.size) return false
            for (j in targetUpper.indices) {
                val c = bytes[offset + j].toInt() and 0xFF
                val upper = if (c in 'a'.code..'z'.code) c - 32 else c
                if (upper != targetUpper[j].toInt()) return false
            }
            return true
        }

        private fun currentSchemaVersion(state: Any): Int =
            (state.javaClass.methods.firstOrNull { it.name == "getSchemaVersion" && it.parameterCount == 0 }
                ?.invoke(state) as? Int) ?: 1
    }
}

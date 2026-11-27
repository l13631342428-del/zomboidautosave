package com.example

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/**
 * Config file format (JSON):
 * {
 *   "monitorPath": "C:/path/to/watch",
 *   "logPath":     "C:/path/to/changes.log"
 * }
 */
@Serializable
data class WatchConfig(val monitorPath: String, val logPath: String)

/** Inbound request for POST /config. Either a configPath, or both monitorPath + logPath. */
@Serializable
data class ConfigRequest(
    val configPath: String? = null,
    val monitorPath: String? = null,
    val logPath: String? = null,
)

fun main(args: Array<String>) {
    // Optional: pass a config file path as the first program argument to start watching on boot.
    if (args.isNotEmpty()) {
        runCatching { WatchManager.loadConfigFile(args[0]) }
            .onSuccess { WatchManager.applyConfig(it) }
            .onFailure { println("Failed to load startup config '${args[0]}': ${it.message}") }
    }
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json()
    }

    routing {
        post("/hello") {
            val request = call.receive<HelloRequest>()
            if (request.name.isBlank()) {
                call.respondText("Name must not be blank", status = HttpStatusCode.BadRequest)
                return@post
            }
            call.respondText("Hello World ${request.name}")
        }

        // Accept a config (file path, or inline monitorPath + logPath) and start watching.
        post("/config") {
            val req = call.receive<ConfigRequest>()
            val cfg = when {
                !req.configPath.isNullOrBlank() -> WatchManager.loadConfigFile(req.configPath)
                !req.monitorPath.isNullOrBlank() && !req.logPath.isNullOrBlank() ->
                    WatchConfig(req.monitorPath, req.logPath)
                else -> {
                    call.respondText(
                        "Provide \"configPath\", or both \"monitorPath\" and \"logPath\"",
                        status = HttpStatusCode.BadRequest,
                    )
                    return@post
                }
            }
            runCatching { WatchManager.applyConfig(cfg) }
                .onSuccess {
                    call.respondText(
                        "Watching: ${cfg.monitorPath}\nLogging to: ${cfg.logPath}",
                    )
                }
                .onFailure {
                    call.respondText("Failed to start watcher: ${it.message}", status = HttpStatusCode.InternalServerError)
                }
        }

        get("/config") {
            val c = WatchManager.currentConfig()
            if (c == null) {
                call.respondText("No active config", status = HttpStatusCode.NotFound)
            } else {
                call.respondText("monitorPath=${c.monitorPath}\nlogPath=${c.logPath}")
            }
        }
    }
}

@Serializable
data class HelloRequest(val name: String)

/** Holds the active watcher so config can be replaced at runtime. */
object WatchManager {
    @Volatile private var current: Watcher? = null
    @Volatile private var config: WatchConfig? = null

    @Synchronized
    fun applyConfig(cfg: WatchConfig) {
        current?.close()
        current = Watcher(cfg).also { it.start() }
        config = cfg
    }

    fun currentConfig(): WatchConfig? = config

    fun loadConfigFile(path: String): WatchConfig {
        val p = Paths.get(path).toAbsolutePath().normalize()
        var text = Files.readString(p)
        // Tolerate a UTF-8 BOM that some editors (e.g. PowerShell 5, Notepad) prepend.
        if (text.isNotEmpty() && text[0] == '\uFEFF') text = text.substring(1)
        return Json.decodeFromString<WatchConfig>(text)
    }
}

/**
 * Recursively watches a directory and appends every CREATE / DELETE / MODIFY event
 * (with a timestamp) to the configured log file.
 */
class Watcher(config: WatchConfig) {
    private val monitor: Path = Paths.get(config.monitorPath).toAbsolutePath().normalize()
    private val logFile: Path = Paths.get(config.logPath).toAbsolutePath().normalize()
    private val watchService = FileSystems.getDefault().newWatchService()
    private val keys = HashMap<WatchKey, Path>()
    private val logLock = Any()
    @Volatile private var closed = false

    init {
        Files.createDirectories(monitor)
        logFile.parent?.let { Files.createDirectories(it) }
        registerAll(monitor)
        appendLog("WATCH_START monitor=$monitor log=$logFile")
    }

    private fun registerAll(start: Path) {
        Files.walk(start).use { stream ->
            stream.filter { Files.isDirectory(it) }.forEach { dir ->
                val key = dir.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                )
                keys[key] = dir
            }
        }
    }

    fun start() {
        thread(isDaemon = true, name = "dir-watcher") {
            while (!closed) {
                val key = watchService.take()
                val dir = keys[key]
                if (dir == null) {
                    key.reset()
                    continue
                }
                for (event in key.pollEvents()) {
                    handle(event, dir)
                }
                if (!key.reset()) {
                    keys.remove(key)
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handle(event: WatchEvent<*>, dir: Path) {
        val kind = event.kind()
        if (kind == StandardWatchEventKinds.OVERFLOW) return

        val name = (event as WatchEvent<Path>).context()
        val child = dir.resolve(name).toAbsolutePath().normalize()

        // Avoid an infinite loop if the log file lives inside the monitored directory.
        if (child == logFile) return

        val type = when (kind) {
            StandardWatchEventKinds.ENTRY_CREATE -> "CREATE"
            StandardWatchEventKinds.ENTRY_DELETE -> "DELETE"
            StandardWatchEventKinds.ENTRY_MODIFY -> "MODIFY"
            else -> kind.name()
        }

        // Register newly created subdirectories so their contents are watched too.
        if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(child)) {
            runCatching { registerAll(child) }
        }

        appendLog("$type $child")
    }

    private fun appendLog(line: String) {
        val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"))
        synchronized(logLock) {
            Files.writeString(
                logFile,
                "$ts  $line${System.lineSeparator()}",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }

    fun close() {
        closed = true
        runCatching { watchService.close() }
    }
}

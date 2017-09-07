import java.io.*
import java.net.*
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.net.ssl.*
import kotlin.coroutines.experimental.buildSequence
import kotlin.reflect.KClass

/**
 * Created by natsuki@live.cn on 06/09/2017.
 * TODO:
 *  1. polish cookie/session/ua and other utilities for Request and Response
 *  2. remove threadpool, using coroutine to replace listen/copy file operation
 */
class Client(private val socket: Socket, private val match: (Request) -> (Request, Response) -> Any) : Runnable {
    override fun run() {
        val input = BufferedReader(InputStreamReader(socket.inputStream))
        val output = socket.outputStream
        val req = read(input) {
            linkedMapOf<String, String>().apply {
                input.lineSequence().takeWhile(String::isNotBlank).forEach {
                    it.split(":").let {
                        put(it[0], it[1].trim())
                    }
                }
            }
        }

        val rsp = Response(stream = output)
        val out = match(req)(req, rsp)
        println(out)
        rsp.flush()
        socket.close()
    }

    private fun read(reader: BufferedReader, eval: () -> Map<String, String>) = reader.readLine().split(' ').let {
        Request(Method.valueOf(it[0]), it[1], it[2], eval(), reader)
    }
}

data class Request(val method: Method, private val raw: String, val version: String, private val headers: Map<String, String>, private val stream: BufferedReader) {
    val content by lazy {
        (1..headers.getOrDefault("Content-Length", "0").toInt()).fold("", { acc, _ ->
            acc + stream.read().toChar()
        })
    }
    val url = raw.replace("""[#?].*""".toRegex(), "")
    val query = raw.replace(""".*\?|#.*""".toRegex(), "").split("&").map {
        it.split("=").let {
            it[0] to it[1]
        }
    }.toMap()
}

data class Response(private var status: Status = Status.OK, private val stream: OutputStream) {
    private val writer by lazy {
        PrintWriter(stream)
    }

    private var dirty = false

    fun send(content: Sequence<Char>, status: Status = this.status, headers: Map<String, String> = emptyMap()) {
        if (!dirty) {
            writer.write("HTTP/1.1 ${status.code} ${status.value}\n")
            if (headers.isNotEmpty()) {
                headers.forEach { writer.write("${it.key}: ${it.value}\n") }
                writer.write("\n")
            }
            writer.write("\n")
            dirty = true
        }
        writer.write(content.toList().toCharArray())
    }

    fun send(content: String, status: Status = this.status, headers: Map<String, String> = emptyMap()) {
        send(buildSequence { yieldAll(content.toCharArray().asIterable()) }, status, headers)
    }

    fun flush() = writer.flush()
}

enum class Method {
    GET, POST, PUT, DELETE, OPTIONS, HEAD, TRACE, CONNECT, PATCH
}

enum class Status(val code: Int, val value: String) {
    // 200
    OK(200, "OK"),
    Created(201, "Created"),

    // 300
    MovedPermanently(301, "Moved Permanently"),
    Found(302, "Found"),

    // 400
    BadRequest(400, "Bad Request"),
    NotFound(404, "Not Found"),

    // 500
    InternalServerError(500, "Internal Server Error"),
}

typealias Handler = (Request, Response) -> Any

infix operator fun Handler.plus(next: Handler): Handler = { req, rsp ->
    this(req, rsp)
    next(req, rsp)
}

class Server(private val port: Int = (System.getProperty("server.port") ?: "1234").toInt()) {
    private val threads = Executors.newCachedThreadPool()
    private val bindings = Method.values().map {
        it to mutableMapOf<String, Handler>()
    }.toMap()

    private val before = mutableMapOf<String, Handler>()
    private val after = mutableMapOf<String, Handler>()
    private val exception = mutableMapOf<Class<out RuntimeException>, Handler>()

    fun start(port: Int = this.port, secure: Boolean = false, keystore: String = "", pass: String = "") {
        bindRoutes()
        threads.submit {
            println("Server start on port $port")
            try {
                val socket = if (secure) ssl(port, keystore, pass) else ServerSocket(port)
                while (true) {
                    threads.submit(Client(socket.accept(), { match(it) }))
                }
            } catch (e: IOException) {
                println(e.message)
                System.exit(1)
            }
        }
    }

    private fun bindRoutes() {
        bindings.forEach { _, values ->
            values.forEach { key, value ->
                val action = before.filterKeys {
                    key == it || key.matches(it.toRegex())
                }.values.toList().reduce { acc, ele ->
                    acc + ele
                } + value + after.filterKeys {
                    key == it || key.matches(it.toRegex())
                }.values.toList().reduce { acc, ele ->
                    acc + ele
                }

                values[key] = action

                if (exception.isNotEmpty()) {
                    values[key] = { req, res ->
                        try {
                            action(req, res)
                        } catch (e: RuntimeException) {
                            if (exception.contains(e.javaClass)) {
                                exception[e.javaClass]!!.invoke(req, res)
                            } else {
                                res.send(e.message ?: "Error", Status.InternalServerError)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun ssl(port: Int, store: String, pwd: String): ServerSocket {
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(FileInputStream(store), pwd.toCharArray())
        }

        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(keys)
        }

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keys, pwd.toCharArray())
        }

        return SSLContext.getInstance("SSL").apply {
            init(kmf.keyManagers, null, null)
        }.serverSocketFactory.createServerSocket(port)
    }

    private fun match(req: Request) = bindings[req.method]!!.let { routes ->
        routes.getOrElse(req.url, {
            routes.filter { it.key.toRegex().matches(req.url) }.values.firstOrNull() ?: { _, res ->
                res.send("Resource Not Found", Status.NotFound)
            }
        })
    }

    private fun bind(method: Method, path: String, call: Handler) = bindings[method]?.put(path, call)
    fun get(path: String, call: Handler) = bind(Method.GET, path, call)
    fun post(path: String, call: Handler) = bind(Method.POST, path, call)
    fun put(path: String, call: Handler) = bind(Method.PUT, path, call)
    fun delete(path: String, call: Handler) = bind(Method.DELETE, path, call)
    fun after(path: String, call: Handler) = after.put(path, call)
    fun after(call: Handler) = after.put(".*", call)
    fun before(path: String, call: Handler) = before.put(path, call)
    fun before(call: Handler) = before.put(".*", call)
    fun exception(klass: KClass<out RuntimeException>, call: Handler) = exception.put(klass.java, call)
    fun static(path: String) = "./$path".run {
        File(this).walkTopDown().forEach {
            if (!it.isDirectory) {
                val file = it.path.substring(this.length)
                val call: (Request, Response) -> Unit = { _, res ->
                    res.send(buildSequence {
                        yieldAll(it.readBytes().map { it.toChar() })
                    })
                }
                get("""/?$file""", call)
            }
        }
    }
}


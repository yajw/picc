import com.jayway.jsonpath.JsonPath
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

fun main(args: Array<String>) {
    val opts = args.toList().zipWithNext().toMap()
    compress(opts["-k"]?.split(","), opts["-i"], opts["-o"], opts["-c"]?.toInt() ?: 100)
}

fun compress(keys: List<String>?, inputPath: String?, outputPath: String?, threadPoolSize: Int) {
    if (keys.isNullOrEmpty()) {
        System.err.println("key can't be null")
        return
    }
    if (inputPath == null) {
        System.err.println("input path can't be null")
        return
    }
    if (outputPath == null) {
        System.err.println("output path can't be null")
        return
    }

    Files.createDirectories(Paths.get(outputPath))

    val executor = Executors.newFixedThreadPool(threadPoolSize)

    var originalSize = AtomicLong(0)
    var outputSize = AtomicLong(0)

    File(inputPath).walkTopDown().filter { it.isFile }.forEach {
        executor.submit {
            try {
                originalSize.addAndGet(Files.size(it.toPath()))
                val size = compressOne(keys.random(), it, outputPath)
                outputSize.addAndGet(size)
            } catch (e: Exception) {
                println(it.absolutePath)
            }
        }
    }
    executor.shutdown()
    while (!executor.isTerminated) {
    }
    println("done! ${originalSize.get() / 1024 / 1024} MB => ${outputSize.get() / 1024 / 1024} MB")
}


fun compressOne(key: String, inputFile: File, outputPath: String): Long {
    val targetFilePath = Paths.get(outputPath, "c_${inputFile.name}").toString()
    return shrink(key, inputFile.absolutePath) {
        copy(key, it, targetFilePath)
    }
}

fun shrink(key: String, path: String, cb: (location: String) -> Long): Long {
    val request = HttpRequest.newBuilder()
        .uri(URI.create("https://api.tinify.com/shrink"))
        .header("Authorization", buildAuth(key))
        .POST(HttpRequest.BodyPublishers.ofFile(Paths.get(path)))
        .build()
    var size = 0L
    httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        .thenAccept { size = cb(JsonPath.read(it.body(), "$.output.url")) }
        .join()
    return size
}

fun copy(key: String, location: String, targetPath: String): Long {
    val path = Paths.get(targetPath)
    val request = HttpRequest.newBuilder()
        .uri(URI.create(location))
        .headers("Authorization", buildAuth(key))
        .GET()
        .build()
    httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofFile(path)).join()
    return Files.size(path)
}

fun buildAuth(key: String): String {
    return "Basic ${Base64.getEncoder().encodeToString("api:$key".toByteArray())}"
}

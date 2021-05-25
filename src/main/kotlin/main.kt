import com.drew.imaging.jpeg.JpegMetadataReader
import com.drew.metadata.Directory
import com.drew.metadata.exif.ExifIFD0Descriptor
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.jayway.jsonpath.JsonPath
import java.io.File
import java.lang.Error
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.time.ZoneId
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong


val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

fun main(args: Array<String>) {
    val opts = args.toList().zipWithNext().toMap()
    compress(
        opts["-k"]?.split(","),
        opts["-i"],
        opts["-o"],
        opts["-c"]?.toInt() ?: 100,
        opts["-C"].toBoolean(),
    )
}

fun compress(
    keys: List<String>?,
    inputPath: String?,
    outputPath: String?,
    threadPoolSize: Int,
    classifyByDate: Boolean
) {
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
                val size = compressOne(keys.random(), it, outputPath, classifyByDate)
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


fun compressOne(key: String, inputFile: File, outputPath: String, classifyByDate: Boolean): Long {
    var targetFilePath = Paths.get(outputPath, "c_${inputFile.name}").toString()
    val attrs = Files.readAttributes(inputFile.toPath(), BasicFileAttributes::class.java)
    val captureDate = getCaptureDate(inputFile)
    if (classifyByDate && captureDate != null) {
        try {
            val localDate = captureDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
            val folder = Paths.get(outputPath, localDate.year.toString(), localDate.month.value.toString().padStart(2, '0'))
            Files.createDirectories(folder)
            targetFilePath = Paths.get(folder.toString(), "c_${inputFile.name}").toString()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    return shrink(key, inputFile.absolutePath) {
        copy(key, it, targetFilePath, attrs)
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

fun copy(key: String, location: String, targetPath: String, attrs: BasicFileAttributes): Long {
    val path = Paths.get(targetPath)
    val request = HttpRequest.newBuilder()
        .uri(URI.create(location))
        .header("Authorization", buildAuth(key))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("{\"preserve\":[\"copyright\",\"creation\",\"location\"]}"))
        .build()
    httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofFile(path)).join()
    val targetFileAttrs = Files.getFileAttributeView(path, BasicFileAttributeView::class.java)
    targetFileAttrs.setTimes(attrs.lastModifiedTime(), attrs.lastAccessTime(), attrs.creationTime())
    return Files.size(path)
}

fun buildAuth(key: String): String {
    return "Basic ${Base64.getEncoder().encodeToString("api:$key".toByteArray())}"
}

fun getCaptureDate(jpegFile: File): Date? {
    try {
        val metadata = JpegMetadataReader.readMetadata(jpegFile)
        val d = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
        return d.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)
    } catch (e: Exception) {
        return null
    }
}

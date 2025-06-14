import kotlinx.coroutines.*
import okhttp3.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ArknightsImageDownloader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "https://arknights.wiki.gg"
//    private val startUrl = "$baseUrl/wiki/Category:Background_images"
    private val startUrl = "$baseUrl/wiki/Category:Story_images"
    private val downloadDir = File("img")
    private val targetSize = "1024x576"

    init {
        // ダウンロードディレクトリを作成
        if (!downloadDir.exists()) {
            downloadDir.mkdirs()
        }
    }

    suspend fun downloadAllImages() {
        var currentUrl = startUrl
        var pageCount = 1

        while (currentUrl.isNotEmpty()) {
            println("Processing page $pageCount: $currentUrl")

            try {
                val doc = fetchDocument(currentUrl)
                val imageUrls = extractImageUrls(doc)

                if (imageUrls.isNotEmpty()) {
                    println("Found ${imageUrls.size} images on page $pageCount")
                    downloadImages(imageUrls)
                } else {
                    println("No images found on page $pageCount")
                }

                // 次のページのURLを取得
                currentUrl = getNextPageUrl(doc)
                if (currentUrl.isNotEmpty()) {
                    println("Next page found: $currentUrl")
                    delay(1000) // リクエスト間隔を空ける
                } else {
                    println("No more pages found. Download completed.")
                }

                pageCount++

            } catch (e: Exception) {
                println("Error processing page $currentUrl: ${e.message}")
                e.printStackTrace()
                break
            }
        }
    }

    private suspend fun fetchDocument(url: String): Document = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("Failed to fetch page: ${response.code}")
        }

        val html = response.body?.string() ?: throw IOException("Empty response body")
        Jsoup.parse(html, url)
    }

    private suspend fun extractImageUrls(doc: Document): List<String> {
        val imageUrls = mutableListOf<String>()

        // カテゴリページから画像を探す
        val galleryItems = doc.select(".gallerybox .thumb img, .gallery .gallerybox img")

        for (img in galleryItems) {
            val src = img.attr("src")
            if (src.isNotEmpty()) {
                // 高解像度版のURLを取得
                val fullSizeUrl = getFullSizeImageUrl(src)
                imageUrls.add(fullSizeUrl)
            }
        }

        // 通常のファイルリンクも確認
        val fileLinks = doc.select("a[href*='/wiki/File:']")
        for (link in fileLinks) {
            val href = link.attr("href")
            if (href.isNotEmpty()) {
                try {
                    val filePageUrl = if (href.startsWith("http")) href else baseUrl + href
                    val imageUrl = extractImageFromFilePage(filePageUrl)
                    if (imageUrl.isNotEmpty()) {
                        imageUrls.add(imageUrl)
                    }
                } catch (e: Exception) {
                    println("Error processing file link $href: ${e.message}")
                }
            }
        }

        return imageUrls.distinct()
    }

    private fun getFullSizeImageUrl(thumbnailUrl: String): String {
        // サムネイルURLから元画像URLを生成
        return thumbnailUrl
            .replace("/thumb/", "/")
            .replace(Regex("/\\d+px-[^/]*$"), "")
    }

    private suspend fun extractImageFromFilePage(filePageUrl: String): String = withContext(Dispatchers.IO) {
        try {
            val doc = fetchDocument(filePageUrl)
            val fullImageLink = doc.select(".fullImageLink a, .fullMedia a").first()
            fullImageLink?.attr("href") ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun getNextPageUrl(doc: Document): String {
        // "next page"リンクを探す
        val nextLinks = doc.select("a:contains(next), a:contains(Next), a:contains(次), .mw-nextlink")

        for (link in nextLinks) {
            val href = link.attr("href")
            if (href.isNotEmpty() && !link.hasClass("mw-prevlink")) {
                return if (href.startsWith("http")) href else baseUrl + href
            }
        }

        // ページネーション内の次のページリンクを探す
        val paginationLinks = doc.select(".mw-category-pagination a")
        for (link in paginationLinks) {
            val text = link.text().trim()
            if (text.contains("next", ignoreCase = true) ||
                text.contains("次", ignoreCase = true)) {
                val href = link.attr("href")
                return if (href.startsWith("http")) href else baseUrl + href
            }
        }

        return ""
    }

    private suspend fun downloadImages(imageUrls: List<String>) = coroutineScope {
        val jobs = imageUrls.map { imageUrl ->
            async(Dispatchers.IO) {
                downloadImage(imageUrl)
            }
        }

        jobs.awaitAll()
    }

    private suspend fun downloadImage(imageUrl: String) = withContext(Dispatchers.IO) {
        try {
            val fullUrl = if (imageUrl.startsWith("http")) imageUrl else baseUrl + imageUrl
            val fileName = extractFileName(fullUrl)
            val file = File(downloadDir, fileName)

            if (file.exists()) {
                println("Skipping $fileName (already exists)")
                return@withContext
            }

            println("Downloading: $fileName")

            val request = Request.Builder()
                .url(fullUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                println("Failed to download $fileName: ${response.code}")
                return@withContext
            }

            response.body?.byteStream()?.use { inputStream ->
                FileOutputStream(file).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            // ダウンロード後に実際のサイズを確認
            if (verifyImageSize(file)) {
                println("Successfully downloaded: $fileName")
            } else {
                println("Downloaded $fileName but size may not match target")
            }

        } catch (e: Exception) {
            println("Error downloading $imageUrl: ${e.message}")
        }
    }

    private fun extractFileName(url: String): String {
        val urlObj = URL(url)
        val path = urlObj.path
        val fileName = path.substringAfterLast("/")

        // ファイル名をサニタイズ
        return fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    private fun verifyImageSize(file: File): Boolean {
        // 簡単なサイズ確認（実際の画像解析ライブラリを使用することを推奨）
        return file.length() > 1024 // 最低限のファイルサイズチェック
    }

    fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}

fun renameAllFile() {
    val imgFolder = File("img")

    if (!imgFolder.exists() || !imgFolder.isDirectory) {
        println("エラー: imgフォルダが存在しません")
        return
    }

    val files = imgFolder.listFiles { file -> file.isFile }

    if (files == null || files.isEmpty()) {
        println("imgフォルダにファイルが見つかりません")
        return
    }

    files.sortBy { it.name.lowercase() }

    println("${files.size}個のファイルを連番にリネームします...")
    println("処理前のファイル一覧:")
    files.forEachIndexed { index, file ->
        println("${index + 1}: ${file.name}")
    }

    print("\n続行しますか？ (y/N): ")
    val input = readlnOrNull()?.trim()?.lowercase()
    if (input != "y" && input != "yes") {
        println("処理をキャンセルしました")
        return
    }

    val tempFiles = mutableListOf<Pair<File, String>>()

    try {
        files.forEachIndexed { index, file ->
            val extension = file.extension
            val newName = if (extension.isNotEmpty()) {
                String.format("%03d.%s", index + 1, extension)
            } else {
                String.format("%03d", index + 1)
            }

            val newFile = File(imgFolder, newName)

            if (newFile.exists() && newFile != file) {
                val tempName = "temp_${System.currentTimeMillis()}_${index}"
                val tempFile = File(imgFolder, tempName)
                Files.move(file.toPath(), tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                tempFiles.add(Pair(tempFile, newName))
            } else if (file.name != newName) {
                Files.move(file.toPath(), newFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                println("${file.name} → $newName")
            }
        }

        tempFiles.forEach { (tempFile, finalName) ->
            val finalFile = File(imgFolder, finalName)
            Files.move(tempFile.toPath(), finalFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            println("${tempFile.name} → $finalName")
        }

        println("\nリネーム完了！")

        val renamedFiles = imgFolder.listFiles { file -> file.isFile }
        if (renamedFiles != null) {
            renamedFiles.sortBy { it.name }
            println("\n処理後のファイル一覧:")
            renamedFiles.forEach { file ->
                println(file.name)
            }
        }

    } catch (e: Exception) {
        println("エラーが発生しました: ${e.message}")

        // エラー時のクリーンアップ
        tempFiles.forEach { (tempFile, _) ->
            if (tempFile.exists()) {
                println("一時ファイルを削除: ${tempFile.name}")
                tempFile.delete()
            }
        }
    }
}

val File.extension: String
    get() = if (name.contains('.')) name.substringAfterLast('.') else ""

// メイン関数
suspend fun main() {
//    val downloader = ArknightsImageDownloader()
//
//    try {
//        println("Starting Arknights background image download...")
//        println("Target size: 1024x576")
//        println("Download directory: img/")
//        println()
//
//        downloader.downloadAllImages()
//
//        println("\nDownload process completed!")
//
//    } catch (e: Exception) {
//        println("Error during download process: ${e.message}")
//        e.printStackTrace()
//    } finally {
//        downloader.close()
//    }

    renameAllFile();
}

// 実行用のエントリーポイント
fun runDownloader() {
    runBlocking {
        main()
    }
}
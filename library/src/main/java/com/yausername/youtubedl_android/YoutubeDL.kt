package com.yausername.youtubedl_android

import android.content.Context
import android.os.Build
import android.util.Log
import com.yausername.youtubedl_android.Constants.DirectoriesName.ARIA2C
import com.yausername.youtubedl_android.Constants.LIBRARY_NAME
import com.yausername.youtubedl_android.Constants.PACKAGES_ROOT_NAME
import com.yausername.youtubedl_android.data.local.streams.StreamGobbler
import com.yausername.youtubedl_android.data.local.streams.StreamProcessExtractor
import com.yausername.youtubedl_android.data.remote.YoutubeDLUpdater
import com.yausername.youtubedl_android.data.remote.dependencies.DependenciesDownloaderImpl
import com.yausername.youtubedl_android.domain.DependenciesDownloader
import com.yausername.youtubedl_android.domain.Dependency
import com.yausername.youtubedl_android.domain.model.DownloadedDependencies
import com.yausername.youtubedl_android.domain.model.YoutubeDLResponse
import com.yausername.youtubedl_android.domain.model.getMissingDependencies
import com.yausername.youtubedl_android.domain.model.videos.VideoInfo
import com.yausername.youtubedl_android.util.exceptions.MissingDependency
import com.yausername.youtubedl_android.util.exceptions.YoutubeDLException
import com.yausername.youtubedl_android.util.files.FilesUtil.createDirectoryIfNotExists
import com.yausername.youtubedl_common.SharedPrefsHelper
import com.yausername.youtubedl_common.SharedPrefsHelper.update
import com.yausername.youtubedl_common.utils.ZipUtils.unzip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.apache.commons.io.FileUtils
import java.io.File
import java.io.IOException
import java.util.Collections
import kotlin.collections.set

object YoutubeDL {
    private var initialized = false
    internal lateinit var binariesDirectory: File

    private var pythonPath: File? = null
    private var ffmpegPath: File? = null
    private lateinit var ytdlpPath: File

    /* ENVIRONMENT VARIABLES */
    private lateinit var ENV_LD_LIBRARY_PATH: String
    private lateinit var ENV_SSL_CERT_FILE: String
    private lateinit var ENV_PYTHONHOME: String

    private val dependenciesDownloader: DependenciesDownloader = DependenciesDownloaderImpl()

    //Map of process id associated with the process
    private val idProcessMap = Collections.synchronizedMap(HashMap<String, Process>())

    @Synchronized
    @Throws(YoutubeDLException::class, MissingDependency::class)
            /**
             * Initializes the library. This method should be called before any other method.
             * @param appContext the application context
             */
    fun init(appContext: Context) {
        if (initialized) return

        val baseDir = File(appContext.noBackupFilesDir, LIBRARY_NAME)

        createDirectoryIfNotExists(baseDir)

        val packagesDir = File(baseDir, PACKAGES_ROOT_NAME)

        binariesDirectory =
            File(appContext.applicationInfo.nativeLibraryDir) //Here are all the binaries provided by the jniLibs folder

        pythonPath = File(binariesDirectory, Constants.BinariesName.PYTHON)
        ffmpegPath = File(binariesDirectory, Constants.BinariesName.FFMPEG)

        // Create dependencies packages directory (where they are extracted)
        val pythonDir = File(
            packagesDir, Constants.DirectoriesName.PYTHON
        )
        val ffmpegDir = File(
            packagesDir, Constants.DirectoriesName.FFMPEG
        )
        val aria2cDir = File(packagesDir, Constants.DirectoriesName.ARIA2C)

        val ytdlpDir = File(
            baseDir, Constants.DirectoriesName.YTDLP
        )
        ytdlpPath = File(ytdlpDir, Constants.BinariesName.YTDLP)

        // Set environment variables
        ENV_LD_LIBRARY_PATH =
            pythonDir.absolutePath + "/usr/lib" + ":" + ffmpegDir.absolutePath + "/usr/lib" + ":" + aria2cDir.absolutePath + "/usr/lib"
        ENV_SSL_CERT_FILE = pythonDir.absolutePath + "/usr/etc/tls/cert.pem"
        ENV_PYTHONHOME = pythonDir.absolutePath + "/usr"

        initPython(appContext, pythonDir)
        initYtdlp(appContext, ytdlpDir)

        initialized = true
    }

    /**
     * Asserts that the dependencies are installed and downloads them if they are missing.
     * RECOMMENDATION: Call this method before init.
     * @param appContext the application context
     * @param callback a callback that will be called with the dependency and the progress of the download
     * @throws MissingDependency if the dependencies are missing after the installation
     */
    @Throws(MissingDependency::class)
    fun ensureDependencies(appContext: Context, callback: dependencyDownloadCallback? = null) {
        val installedDependencies = checkInstalledDependencies(appContext)
        installDependencies(appContext, installedDependencies) { dependency, progress ->
            callback?.invoke(dependency, progress)
        }
    }

    /**
     * Asserts that the dependencies are installed and downloads them if they are missing
     * @param appContext the application context
     * @param downloadedDependencies the downloaded dependencies
     * @param callback a callback that will be called with the dependency and the progress of the download
     */
    @Throws(MissingDependency::class)
    fun installDependencies(
        appContext: Context,
        downloadedDependencies: DownloadedDependencies,
        callback: dependencyDownloadCallback?
    ) {
        // We check what dependencies are missing and download them; the callback is called with the dependency and the progress of the download.
        // get the dependencies that are missing
        val missingDependencies = downloadedDependencies.getMissingDependencies()
        if (BuildConfig.DEBUG) Log.i("YoutubeDL", "Missing dependencies: $missingDependencies")
        if (missingDependencies.isNotEmpty()) {
            Log.i("YoutubeDL", "Some dependencies are missing: $missingDependencies")

            runBlocking(Dispatchers.IO) {
                // download the missing dependencies
                missingDependencies.forEach { dependency ->
                    Log.i("YoutubeDL", "Downloading $dependency")
                    var lastProgress = 0 // Initialize with an appropriate default value

                    when (dependency) {
                        Dependency.PYTHON -> {
                            dependenciesDownloader.downloadPython(appContext) { progress ->
                                if (progress != lastProgress) {
                                    callback?.invoke(dependency, progress)
                                    lastProgress = progress
                                }
                            }
                        }

                        Dependency.FFMPEG -> {
                            dependenciesDownloader.downloadFFmpeg(appContext) { progress ->
                                if (progress != lastProgress) {
                                    callback?.invoke(dependency, progress)
                                    lastProgress = progress
                                }
                            }
                        }

                        Dependency.ARIA2C -> {
                            dependenciesDownloader.downloadAria2c(appContext) { progress ->
                                if (progress != lastProgress) {
                                    callback?.invoke(dependency, progress)
                                    lastProgress = progress
                                }
                            }
                        }
                    }
                }
            }

            // check again if the dependencies are installed (this time they should be)
            val postInstallMissingDependencies =
                checkInstalledDependencies(appContext).getMissingDependencies()
            if (postInstallMissingDependencies.isNotEmpty()) {
                throw MissingDependency("Some of the dependencies are still missing after the installation: $postInstallMissingDependencies")
            }
        } else {
            Log.i("YoutubeDL", "All dependencies are installed")
        }
    }


    @Throws(YoutubeDLException::class)
    /**
     * Initializes yt-dlp.
     * @param appContext the application context
     * @param ytdlpDir the directory where yt-dlp is located
     */
    internal fun initYtdlp(appContext: Context, ytdlpDir: File) {
        if (!ytdlpDir.exists()) ytdlpDir.mkdirs()
        val ytdlpBinary = File(ytdlpDir, Constants.BinariesName.YTDLP)
        if (!ytdlpBinary.exists()) {
            try {
                val inputStream =
                    appContext.resources.openRawResource(R.raw.ytdlp) /* will be renamed to yt-dlp */
                FileUtils.copyInputStreamToFile(inputStream, ytdlpBinary)
            } catch (e: Exception) {
                FileUtils.deleteQuietly(ytdlpDir)
                throw YoutubeDLException("Failed to initialize yt-dlp", e)
            }
        }
    }

    @Throws(YoutubeDLException::class)
            /**
             * Initializes Python.
             * @param appContext the application context
             * @param pythonDir the directory where Python is located
             */
    fun initPython(appContext: Context, pythonDir: File) {
        val pythonLibrary = File(
            binariesDirectory, Constants.LibrariesName.PYTHON
        )
        // using size of lib as version
        val pythonSize = pythonLibrary.length().toString()
        if (!pythonDir.exists() || shouldUpdatePython(appContext, pythonSize)) {
            FileUtils.deleteQuietly(pythonDir)
            pythonDir.mkdirs()
            try {
                unzip(pythonLibrary, pythonDir)
            } catch (e: Exception) {
                FileUtils.deleteQuietly(pythonDir)
                throw YoutubeDLException("Failed to initialize Python", e)
            }
            updatePython(appContext, pythonSize)
        }
    }

    /**
     * Check if Python should be updated by using the zip file size (both new and old)
     * @param appContext the application context
     * @param version the current version of Python (the size of the zip file)
     */
    private fun shouldUpdatePython(appContext: Context, version: String): Boolean {
        return version != SharedPrefsHelper[appContext, PYTHON_LIB_VERSION]
    }

    /**
     * Updates the Python version
     * @param appContext the application context
     * @param version the new version of Python (the size of the zip file)
     */
    private fun updatePython(appContext: Context, version: String) {
        update(appContext, PYTHON_LIB_VERSION, version)
    }

    /**
     * Asserts that the library is initialized
     */
    private fun assertInit() {
        check(initialized) { "The library instance that you are trying to access is not initialized; please, check if you have initialized it by using the YoutubeDL.init() function" }
    }

    @Throws(YoutubeDLException::class, InterruptedException::class, CanceledException::class)
    fun getInfo(url: String): VideoInfo {
        val request = YoutubeDLRequest(url)
        return getInfo(request)
    }

    @Throws(YoutubeDLException::class, InterruptedException::class, CanceledException::class)
            /**
             * Gets video information
             * @param request the request object
             * @return the video information
             */
    fun getInfo(request: YoutubeDLRequest): VideoInfo {
        request.addOption("--dump-json")
        val response = execute(request, null, null)
        val videoInfo: VideoInfo = try {
            json.decodeFromString<VideoInfo>(response.out)
        } catch (e: IOException) {
            throw YoutubeDLException("Unable to parse video information", e)
        }
        return videoInfo
    }

    /**
     * Checks if the output errors should be ignored
     * @param request the request object
     * @param out the output
     * @return true if the errors should be ignored, false otherwise
     */
    private fun ignoreErrors(request: YoutubeDLRequest, out: String): Boolean {
        return request.hasOption("--dump-json") && out.isNotEmpty() && request.hasOption("--ignore-errors")
    }

    /**
     * Checks if a process with the given id is running and destroys it
     * @param id the process id
     * @return true if the process was destroyed successfully, false otherwise
     */
    fun destroyProcessById(id: String): Boolean {
        if (idProcessMap.containsKey(id)) {
            val p = idProcessMap[id]
            var alive = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                alive = p!!.isAlive
            }
            if (alive) {
                p!!.destroy()
                idProcessMap.remove(id)
                return true
            }
        }
        return false
    }

    class CanceledException : Exception()

    @JvmOverloads
    @Throws(YoutubeDLException::class, InterruptedException::class, CanceledException::class)
            /**
             * Executes a request to yt-dlp
             * @param request the request object
             * @param processId the process id
             * @param callback a callback that will be called with the progress percentage, the ETA and the output
             * @return the response object
             */
    fun execute(
        request: YoutubeDLRequest,
        processId: String? = null,
        callback: ((Float, Long, String) -> Unit)? = null
    ): YoutubeDLResponse {
        assertInit()
        if (processId != null && idProcessMap.containsKey(processId)) throw YoutubeDLException("Process ID already exists")
        // disable caching unless explicitly requested
        if (!request.hasOption("--cache-dir") || request.getOption("--cache-dir") == null) {
            request.addOption("--no-cache-dir")
        }

        /* Set ffmpeg location, See https://github.com/xibr/ytdlp-lazy/issues/1 */
        request.addOption("--ffmpeg-location", ffmpegPath!!.absolutePath)
        val youtubeDLResponse: YoutubeDLResponse
        val process: Process
        val exitCode: Int
        val outBuffer = StringBuilder() //stdout
        val errBuffer = StringBuilder() //stderr
        val startTime = System.currentTimeMillis()
        val args = request.buildCommand()
        val command: MutableList<String?> = ArrayList()
        command.addAll(listOf(pythonPath!!.absolutePath, ytdlpPath.absolutePath))
        command.addAll(args)
        val processBuilder = ProcessBuilder(command)
        processBuilder.environment().apply {
            this["LD_LIBRARY_PATH"] = ENV_LD_LIBRARY_PATH
            this["SSL_CERT_FILE"] = ENV_SSL_CERT_FILE
            this["PATH"] = System.getenv("PATH")!! + ":" + binariesDirectory.absolutePath
            this["PYTHONHOME"] = ENV_PYTHONHOME
            this["HOME"] = ENV_PYTHONHOME
        }

        process = try {
            processBuilder.start()
        } catch (e: IOException) {
            throw YoutubeDLException(e)
        }
        if (processId != null) {
            idProcessMap[processId] = process
        }
        val outStream = process.inputStream
        val errStream = process.errorStream
        val stdOutProcessor = StreamProcessExtractor(outBuffer, outStream, callback)
        val stdErrProcessor = StreamGobbler(errBuffer, errStream)
        exitCode = try {
            stdOutProcessor.join()
            stdErrProcessor.join()
            process.waitFor()
        } catch (e: InterruptedException) {
            process.destroy()
            if (processId != null) idProcessMap.remove(processId)
            throw e
        }
        val out = outBuffer.toString()
        val err = errBuffer.toString()
        if (exitCode > 0) {
            if (processId != null && !idProcessMap.containsKey(processId)) throw CanceledException()
            if (!ignoreErrors(request, out)) {
                idProcessMap.remove(processId)
                throw YoutubeDLException(err)
            }
        }
        idProcessMap.remove(processId)

        val elapsedTime = System.currentTimeMillis() - startTime
        youtubeDLResponse = YoutubeDLResponse(command, exitCode, elapsedTime, out, err)
        return youtubeDLResponse
    }

    @Synchronized
    @Throws(YoutubeDLException::class)
            /**
             * Updates yt-dlp
             * @param appContext the application context
             * @param updateChannel the update channel
             * @return the update status
             */
    fun updateYoutubeDL(
        appContext: Context, updateChannel: UpdateChannel = UpdateChannel.STABLE
    ): UpdateStatus? {
        assertInit()
        return try {
            YoutubeDLUpdater.update(appContext, updateChannel)
        } catch (e: IOException) {
            throw YoutubeDLException("Failed to update yt-dlp!", e)
        }
    }

    /**
     * Checks if the dependencies are installed
     * @return the installed dependencies
     */
    fun checkInstalledDependencies(appContext: Context): DownloadedDependencies {
        val libraryBaseDir = File(appContext.noBackupFilesDir, LIBRARY_NAME)
        val packagesDir = File(libraryBaseDir, PACKAGES_ROOT_NAME)

        val pythonDir = File(
            packagesDir, Constants.DirectoriesName.PYTHON
        )
        val ffmpegDir = File(
            packagesDir, Constants.DirectoriesName.FFMPEG
        )
        val aria2cDir = File(packagesDir, ARIA2C)

        val installedDependencies = DownloadedDependencies(
            pythonDir.exists(), ffmpegDir.exists(), aria2cDir.exists()
        )

        Log.i("YoutubeDL", installedDependencies.toString())
        return installedDependencies
    }


    /**
     * Gets the version of yt-dlp
     */
    fun version(appContext: Context?): String? {
        return YoutubeDLUpdater.version(appContext)
    }

    /**
     * Gets the version name of yt-dlp
     */
    fun versionName(appContext: Context?): String? {
        return YoutubeDLUpdater.versionName(appContext)
    }

    enum class UpdateStatus {
        DONE, ALREADY_UP_TO_DATE
    }

    sealed class UpdateChannel(val apiUrl: String) {
        data object STABLE :
            UpdateChannel("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest")

        data object NIGHTLY :
            UpdateChannel("https://api.github.com/repos/yt-dlp/yt-dlp-nightly-builds/releases/latest")

        data object MASTER :
            UpdateChannel("https://api.github.com/repos/yt-dlp/yt-dlp-master-builds/releases/latest")

        companion object {
            @JvmField
            val _STABLE: STABLE = STABLE

            @JvmField
            val _NIGHTLY: NIGHTLY = NIGHTLY

            @JvmField
            val _MASTER: MASTER = MASTER
        }
    }

    private const val PYTHON_LIB_VERSION = "pythonLibVersion"

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    @JvmStatic
    fun getInstance() = this
}

typealias dependencyDownloadCallback = (Dependency, Int) -> Unit
package ai.bitmesh

import java.io.File

/**
 * A file to upload. From an Android content `Uri`, read the bytes with
 * `contentResolver.openInputStream(uri)` and use [fromBytes].
 */
public class BitmeshFile private constructor(
    public val filename: String,
    public val contentType: String,
    private val read: () -> ByteArray,
) {
    internal fun bytes(): ByteArray = read()

    public companion object {
        public fun fromBytes(bytes: ByteArray, filename: String, contentType: String? = null): BitmeshFile =
            BitmeshFile(filename, contentType ?: guessType(filename)) { bytes }

        /** Read when the request is sent. */
        public fun fromFile(file: File, contentType: String? = null): BitmeshFile {
            require(file.isFile && file.canRead()) { "File does not exist or is not readable: ${file.path}" }
            return BitmeshFile(file.name, contentType ?: guessType(file.name)) { file.readBytes() }
        }

        private fun guessType(filename: String): String = when (filename.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "avif" -> "image/avif"
            "mp3" -> "audio/mpeg"
            "m4a", "mp4" -> "audio/mp4"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "webm" -> "audio/webm"
            "flac" -> "audio/flac"
            else -> "application/octet-stream"
        }
    }
}

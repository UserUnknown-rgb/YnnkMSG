package com.Ynnk.YnnkMsg.util

import android.content.Context
import android.graphics.*
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

object ImageUtils {

    private const val MAX_PHOTO_SIZE = 2048 // 2K pixels max dimension
    private const val MAX_AVATAR_SIZE = 512  // Avatar max dimension

    fun compressImage(context: Context, uri: Uri, outputFile: File, maxSize: Int = MAX_PHOTO_SIZE): Boolean {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return false
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            val compressed = scaleBitmap(bitmap, maxSize, maxSize)
            FileOutputStream(outputFile).use { out ->
                compressed.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun compressAvatar(context: Context, uri: Uri, outputFile: File): Boolean {
        return compressImage(context, uri, outputFile, MAX_AVATAR_SIZE)
    }

    private fun scaleBitmap(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxWidth && height <= maxHeight) return bitmap

        val scale = min(maxWidth.toFloat() / width, maxHeight.toFloat() / height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    fun createCircleBitmap(bitmap: Bitmap): Bitmap {
        val size = min(bitmap.width, bitmap.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = Rect(0, 0, size, size)
        canvas.drawOval(RectF(rect), paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val left = (bitmap.width - size) / 2
        val top = (bitmap.height - size) / 2
        canvas.drawBitmap(bitmap, Rect(left, top, left + size, top + size), rect, paint)
        return output
    }

    fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bmp = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            bmp
        } catch (e: Exception) {
            null
        }
    }

    fun saveAvatarToInternalStorage(context: Context, uri: Uri): String? {
        return try {
            val avatarDir = File(context.filesDir, "avatars")
            avatarDir.mkdirs()
            val avatarFile = File(avatarDir, "my_avatar.jpg")
            if (compressAvatar(context, uri, avatarFile)) {
                avatarFile.absolutePath
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}

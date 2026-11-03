package com.ajc.shottracer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.UUID

/** Publish only complete files. The app owns its MediaStore insert, so saving does
 * not require access to the user's other photos/videos. Gallery copies survive project deletion. */
internal object PhotoAlbum {
    @Synchronized fun save(context: Context, file: File): Uri {
        require(file.isFile && file.length()>0)
        val resolver=context.contentResolver
        val preferences=context.getSharedPreferences("album-exports",Context.MODE_PRIVATE)
        val key=file.canonicalPath
        preferences.getString(key,null)?.let { saved ->
            try { resolver.openAssetFileDescriptor(Uri.parse(saved),"r")?.use { return Uri.parse(saved) } } catch (_: Exception) { }
        }
        val values=ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME,"ShotTracer-${UUID.randomUUID()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE,"video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH,Environment.DIRECTORY_MOVIES+"/ShotTracer")
            put(MediaStore.Video.Media.IS_PENDING,1)
        }
        val uri=checkNotNull(resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),values))
        try {
            checkNotNull(resolver.openOutputStream(uri,"w")).use { output -> file.inputStream().use { it.copyTo(output) } }
            check(resolver.update(uri,ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING,0) },null,null)==1)
            preferences.edit().putString(key,uri.toString()).apply()
            return uri
        } catch (error: Exception) {
            try { resolver.delete(uri,null,null) } catch (_: Exception) { }
            throw error
        }
    }
}

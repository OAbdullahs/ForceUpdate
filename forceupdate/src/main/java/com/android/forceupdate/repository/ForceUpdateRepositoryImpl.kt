package com.android.forceupdate.repository

import android.app.Application
import android.app.DownloadManager
import android.app.DownloadManager.*
import android.app.PendingIntent.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller.*
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.android.forceupdate.broadcast.InstallBroadcastReceiver
import com.android.forceupdate.broadcast.InstallBroadcastReceiver.*
import com.android.forceupdate.repository.ForceUpdateRepositoryImpl.DownloadStatus.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

class ForceUpdateRepositoryImpl(private val application: Application) : ForceUpdateRepository {

    override suspend fun downloadApk(apkLink: String) = flow {
        val request = Request(Uri.parse(apkLink)).apply {
            this.setAllowedOverRoaming(true)
            this.setAllowedOverMetered(true)
            this.setNotificationVisibility(Request.VISIBILITY_VISIBLE)
            this.setDestinationInExternalFilesDir(application, null, "update.apk")
        }

        val downloadManager = application.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val sharedPreferences = application.getSharedPreferences(DOWNLOAD_ID_NAME, Context.MODE_PRIVATE)
        val id = sharedPreferences.getLong(DOWNLOAD_ID_KEY, -1L)
        downloadManager.remove(id)
        val downloadId = downloadManager.enqueue(request)
        val query = Query().setFilterById(downloadId)

        sharedPreferences.edit().apply {
            this.putLong(DOWNLOAD_ID_KEY, downloadId)
            this.apply()
        }

        withContext(Dispatchers.IO) {
            var isDownloading = true
            while (isDownloading) {
                val cursor = downloadManager.query(query)
                if (cursor != null && cursor.count >= 0 && cursor.moveToFirst()) {

                    val bytes = cursor.getInt(cursor.getColumnIndex(COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val totalSize = cursor.getInt(cursor.getColumnIndex(COLUMN_TOTAL_SIZE_BYTES))
                    val status = cursor.getInt(cursor.getColumnIndex(COLUMN_STATUS))
                    val percentage = ((bytes.toDouble() / totalSize) * 100).toInt()

                    withContext(Dispatchers.Main) {
                        this@flow.emit(DownloadProgress(percentage))
                    }

                    if (status == STATUS_SUCCESSFUL) {
                        withContext(Dispatchers.Main) {
                            isDownloading = false
                            val uri = cursor.getString(cursor.getColumnIndex(COLUMN_LOCAL_URI))
                            this@flow.emit(DownloadCompleted(File(Uri.parse(uri).path!!)))
                            this.cancel()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        isDownloading = false
                        this@flow.emit(DownloadCanceled)
                        this.cancel()
                    }
                }
            }
        }
    }

    sealed class DownloadStatus {
        data class DownloadProgress(val progress: Int) : DownloadStatus()
        data class DownloadCompleted(val localFile: File) : DownloadStatus()
        object DownloadCanceled : DownloadStatus()
    }

    override fun installApk(localFile: File): Flow<InstallStatus> {
        val contentUri = FileProvider.getUriForFile(application, application.packageName, localFile)
        val packageInstaller = application.packageManager.packageInstaller
        val contentResolver = application.contentResolver

        contentResolver.openInputStream(contentUri)?.use { apkStream ->
            val length = DocumentFile.fromSingleUri(application, contentUri)?.length() ?: -1
            val sessionParams = SessionParams(SessionParams.MODE_FULL_INSTALL)
            val sessionId = packageInstaller.createSession(sessionParams)
            val session = packageInstaller.openSession(sessionId)

            session.openWrite(localFile.name, 0, length).use { sessionStream ->
                apkStream.copyTo(sessionStream)
                session.fsync(sessionStream)
            }

            val intent = Intent(application, InstallBroadcastReceiver::class.java).apply {
                this.putExtra(LOCAL_FILE, localFile)
            }

            val pendingIntent = getBroadcast(application, 2, intent, FLAG_UPDATE_CURRENT)
            session.commit(pendingIntent.intentSender)
            session.close()
        }

        return InstallBroadcastReceiver.installStatus
    }

    companion object {
        const val LOCAL_FILE = "Local File"
        private const val DOWNLOAD_ID_KEY = "Download"
        private const val DOWNLOAD_ID_NAME = "Download ID"
    }
}
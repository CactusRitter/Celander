package com.celander.app.testing

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File

/**
 * A settings DataStore for tests. The default file storage replaces its file with `File.renameTo`, which fails on Windows
 * as soon as the file exists (the second save of a test); Okio's atomic move replaces it there too, so these tests give
 * the same result on every machine. The app itself runs on Android, where the default storage is fine.
 */
fun testPreferencesDataStore(
    scope: CoroutineScope,
    file: File,
    corruptionHandler: ReplaceFileCorruptionHandler<Preferences>? = null,
): DataStore<Preferences> = DataStoreFactory.create(
    storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { file.absolutePath.toPath() },
    corruptionHandler = corruptionHandler,
    scope = scope,
)

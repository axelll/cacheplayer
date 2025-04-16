package com.androiddd.exovideoplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import com.androiddd.exovideoplayer.ExoVideoPlayer.Companion.simpleCache
import com.androiddd.exovideoplayer.databinding.ActivityStorageInfoBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StorageInfoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStorageInfoBinding
    private val TAG = "StorageInfoActivity"

    // Запрос разрешений для Android 10 и выше
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                // Разрешения получены, обновляем информацию
                Toast.makeText(this, "Разрешения получены", Toast.LENGTH_SHORT).show()
                checkPermissionsAndUpdateInfo()
            } else {
                Toast.makeText(this, "Для работы с внешними накопителями необходимы разрешения", Toast.LENGTH_LONG).show()
                checkPermissionsAndUpdateInfo()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStorageInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Сбрасываем стили кнопок при запуске
        resetButtonStyles()

        // Настраиваем кнопку очистки кэша
        binding.btnClearCache.setOnClickListener {
            // Изменяем стиль кнопки при нажатии
            resetButtonStyles()
            setSelectedButtonStyle(binding.btnClearCache)
            clearAllCache()
        }

        // Настраиваем кнопку запроса прав
        binding.btnRequestPermissions.setOnClickListener {
            // Изменяем стиль кнопки при нажатии
            resetButtonStyles()
            setSelectedButtonStyle(binding.btnRequestPermissions)
            requestStoragePermissions()
            Toast.makeText(this, "Запрос прав на доступ к файловой системе", Toast.LENGTH_SHORT).show()
        }

        // Настраиваем кнопку пересоздания кэша
        binding.btnRecreateCache.setOnClickListener {
            // Изменяем стиль кнопки при нажатии
            resetButtonStyles()
            setSelectedButtonStyle(binding.btnRecreateCache)
            recreateCache()
        }

        // Проверяем наличие разрешений и обновляем информацию
        checkPermissionsAndUpdateInfo()
    }

    /**
     * Проверяет наличие разрешений и обновляет информацию о хранилищах
     */
    private fun checkPermissionsAndUpdateInfo() {
        // Проверяем наличие разрешений
        val hasPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            hasPermissions(permissions)
        }

        // Обновляем статус кнопки запроса прав
        binding.btnRequestPermissions.isEnabled = !hasPermissions

        // Обновляем стили кнопок
        resetButtonStyles()

        // Если кнопка запроса прав недоступна, выделяем ее серым
        if (!binding.btnRequestPermissions.isEnabled) {
            setDisabledButtonStyle(binding.btnRequestPermissions)
        } else {
            // Если кнопка запроса прав доступна, выделяем ее ярким цветом
            binding.btnRequestPermissions.setBackgroundColor(resources.getColor(android.R.color.holo_green_light))
            binding.btnRequestPermissions.strokeColor = resources.getColorStateList(android.R.color.white)
            binding.btnRequestPermissions.strokeWidth = 3
            binding.btnRequestPermissions.textSize = 16f
        }

        // Добавляем информацию о статусе разрешений
        val permissionStatus = if (hasPermissions) {
            "\n\nСтатус разрешений: Разрешения получены"
        } else {
            "\n\nСтатус разрешений: Требуется запрос прав"
        }

        // Обновляем информацию о хранилищах
        updateStorageInfo(permissionStatus)
    }

    /**
     * Запрашивает разрешения на доступ к файловой системе
     */
    private fun requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Для Android 11+ (API 30+)
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.addCategory("android.intent.category.DEFAULT")
                    intent.data = Uri.parse("package:$packageName")
                    requestPermissionLauncher.launch(intent)
                } catch (e: Exception) {
                    // Если не удалось открыть специфичный экран, открываем общий
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    requestPermissionLauncher.launch(intent)
                }
            } else {
                // Разрешения уже есть
                checkPermissionsAndUpdateInfo()
            }
        } else {
            // Для Android 10 и ниже (API 29-)
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )

            if (!hasPermissions(permissions)) {
                ActivityCompat.requestPermissions(this, permissions, STORAGE_PERMISSION_CODE)
            } else {
                // Разрешения уже есть
                checkPermissionsAndUpdateInfo()
            }
        }
    }

    private fun hasPermissions(permissions: Array<String>): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                // Разрешения получены
                Toast.makeText(this, "Разрешения получены", Toast.LENGTH_SHORT).show()
                checkPermissionsAndUpdateInfo()
            } else {
                Toast.makeText(this, "Для работы с внешними накопителями необходимы разрешения", Toast.LENGTH_LONG).show()
                checkPermissionsAndUpdateInfo()
            }
        }
    }

    private fun updateStorageInfo(additionalInfo: String = "") {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStorageInfo.text = "Сканирование хранилищ..."

        CoroutineScope(Dispatchers.IO).launch {
            val storageInfo = scanAvailableStorage()
            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE
                binding.tvStorageInfo.text = storageInfo + additionalInfo
            }
        }
    }

    /**
     * Сканирует все доступные хранилища и возвращает информацию о них
     */
    private fun scanAvailableStorage(): String {
        val result = StringBuilder()

        try {
            // Добавляем заголовок с датой и временем
            val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
            val currentDate = dateFormat.format(Date())
            result.append("Информация о хранилищах (${currentDate}):\n\n")

            // Получаем информацию о внутреннем хранилище
            val internalDir = applicationContext.filesDir
            val internalCacheDir = applicationContext.cacheDir
            val internalFreeSpace = internalDir.freeSpace
            val internalTotalSpace = internalDir.totalSpace
            val internalFreeMB = internalFreeSpace / (1024 * 1024)
            val internalTotalMB = internalTotalSpace / (1024 * 1024)

            result.append("1. Внутренняя память:\n")
            result.append("   Путь: ${internalDir.absolutePath}\n")
            result.append("   Кэш: ${internalCacheDir.absolutePath}\n")
            result.append("   Свободно: $internalFreeMB MB / $internalTotalMB MB\n")

            // Получаем все внешние хранилища
            val externalDirs = applicationContext.getExternalFilesDirs(null)
            result.append("\nВнешние хранилища (${externalDirs.size}):\n")

            externalDirs.forEachIndexed { index, dir ->
                if (dir != null) {
                    val freeSpace = dir.freeSpace
                    val totalSpace = dir.totalSpace
                    val freeMB = freeSpace / (1024 * 1024)
                    val totalMB = totalSpace / (1024 * 1024)
                    val isRemovable = isExternalStorageRemovable(dir)

                    result.append("${index + 2}. Внешнее хранилище ${if (isRemovable) "(съемное)" else ""}:\n")
                    result.append("   Путь: ${dir.absolutePath}\n")
                    result.append("   Свободно: $freeMB MB / $totalMB MB\n")
                    result.append("   Съемное: $isRemovable\n")

                    // Проверяем наличие ключевых слов в пути
                    val path = dir.absolutePath.lowercase()
                    val hasUSB = path.contains("usb")
                    val hasSDCard = path.contains("sdcard")
                    val hasEmulated = path.contains("emulated")
                    result.append("   Ключевые слова: USB=$hasUSB, SDCard=$hasSDCard, Emulated=$hasEmulated\n")
                }
            }

            // Добавляем информацию о системе
            result.append("\nИнформация о системе:\n")
            result.append("- Модель: ${Build.MODEL}\n")
            result.append("- Устройство: ${Build.DEVICE}\n")
            result.append("- Версия Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            result.append("- Производитель: ${Build.MANUFACTURER}\n")

            // Информация о текущем кэше
            result.append("\nТекущий кэш:\n")
            result.append("- Тип хранилища: ${ExoVideoPlayer.cacheStorageType}\n")

            // Получаем директорию кэша, которая используется в настоящий момент
            val bestCacheDir = ExoVideoPlayer.findBestCacheDir()
            val cacheExists = bestCacheDir.exists()
            val cacheIsDir = bestCacheDir.isDirectory
            val cacheCanWrite = bestCacheDir.canWrite()
            val cacheCanRead = bestCacheDir.canRead()
            result.append("- Путь кэша: ${bestCacheDir.absolutePath}\n")
            result.append("- Существует: $cacheExists, Директория: $cacheIsDir, Чтение: $cacheCanRead, Запись: $cacheCanWrite\n")

            // Проверяем файлы в директории кэша
            if (cacheExists && cacheIsDir && cacheCanRead) {
                val cacheFiles = bestCacheDir.listFiles()
                if (cacheFiles != null) {
                    result.append("- Файлы в директории кэша (${cacheFiles.size}):\n")
                    var totalFileSize = 0L
                    var totalAlternativeSize = 0L

                    cacheFiles.take(10).forEach { file ->
                        // Получаем размер файла стандартным способом
                        val fileSize = file.length()
                        val fileSizeMB = fileSize / (1024.0 * 1024.0)
                        val canReadFile = file.canRead()
                        totalFileSize += fileSize

                        // Альтернативные способы определения размера файла
                        val channelSize = getFileSizeWithChannel(file)
                        val channelSizeMB = channelSize / (1024.0 * 1024.0)

                        val randomAccessSize = getFileSizeWithRandomAccess(file)
                        val randomAccessSizeMB = randomAccessSize / (1024.0 * 1024.0)

                        val alternativeSize = getFileSizeAlternative(file)
                        val alternativeSizeMB = alternativeSize / (1024.0 * 1024.0)
                        totalAlternativeSize += alternativeSize

                        result.append("  - ${file.name}:\n")
                        result.append("    Размер (File.length): ${fileSizeMB.toInt()} MB\n")
                        result.append("    Размер (FileChannel): ${channelSizeMB.toInt()} MB\n")
                        result.append("    Размер (RandomAccess): ${randomAccessSizeMB.toInt()} MB\n")
                        result.append("    Размер (альтернативный): ${alternativeSizeMB.toInt()} MB\n")
                        result.append("    Чтение: $canReadFile\n")

                        // Если это директория, проверяем ее содержимое
                        if (file.isDirectory) {
                            val subFiles = file.listFiles()
                            if (subFiles != null) {
                                var subDirSize = 0L
                                result.append("    Содержимое директории (${subFiles.size} файлов):\n")
                                subFiles.take(5).forEach { subFile ->
                                    // Стандартный способ
                                    val subFileSize = subFile.length()
                                    val subFileSizeMB = subFileSize / (1024.0 * 1024.0)
                                    subDirSize += subFileSize

                                    // Альтернативные способы
                                    val channelSize = getFileSizeWithChannel(subFile)
                                    val channelSizeMB = channelSize / (1024.0 * 1024.0)

                                    val randomAccessSize = getFileSizeWithRandomAccess(subFile)
                                    val randomAccessSizeMB = randomAccessSize / (1024.0 * 1024.0)

                                    result.append("      - ${subFile.name}:\n")
                                    result.append("        File.length: ${subFileSizeMB.toInt()} MB\n")
                                    result.append("        FileChannel: ${channelSizeMB.toInt()} MB\n")
                                    result.append("        RandomAccess: ${randomAccessSizeMB.toInt()} MB\n")

                                    // Если это директория, проверяем ее содержимое (рекурсивно)
                                    if (subFile.isDirectory) {
                                        val nestedFiles = subFile.listFiles()
                                        if (nestedFiles != null && nestedFiles.isNotEmpty()) {
                                            result.append("        Содержимое (${nestedFiles.size} файлов):\n")
                                            nestedFiles.take(3).forEach { nestedFile ->
                                                val nestedFileSize = nestedFile.length()
                                                val nestedFileSizeMB = nestedFileSize / (1024.0 * 1024.0)

                                                val nestedChannelSize = getFileSizeWithChannel(nestedFile)
                                                val nestedChannelSizeMB = nestedChannelSize / (1024.0 * 1024.0)

                                                val nestedRandomAccessSize = getFileSizeWithRandomAccess(nestedFile)
                                                val nestedRandomAccessSizeMB = nestedRandomAccessSize / (1024.0 * 1024.0)

                                                result.append("          - ${nestedFile.name}:\n")
                                                result.append("            File.length: ${nestedFileSizeMB.toInt()} MB\n")
                                                result.append("            FileChannel: ${nestedChannelSizeMB.toInt()} MB\n")
                                                result.append("            RandomAccess: ${nestedRandomAccessSizeMB.toInt()} MB\n")
                                            }
                                            if (nestedFiles.size > 3) {
                                                result.append("          - ... и еще ${nestedFiles.size - 3} файлов\n")
                                            }
                                        }
                                    }
                                }
                                if (subFiles.size > 5) {
                                    result.append("      - ... и еще ${subFiles.size - 5} файлов\n")
                                }
                                val subDirSizeMB = subDirSize / (1024.0 * 1024.0)
                                result.append("    Общий размер директории: ${subDirSizeMB.toInt()} MB\n")
                            }
                        }
                    }

                    if (cacheFiles.size > 10) {
                        result.append("  - ... и еще ${cacheFiles.size - 10} файлов\n")
                    }

                    val totalFileSizeMB = totalFileSize / (1024.0 * 1024.0)
                    val totalAlternativeSizeMB = totalAlternativeSize / (1024.0 * 1024.0)
                    result.append("- Общий размер файлов (File.length): ${totalFileSizeMB.toInt()} MB\n")
                    result.append("- Общий размер файлов (альтернативный): ${totalAlternativeSizeMB.toInt()} MB\n")

                    // Проверяем другие директории на флешке
                    val parentDir = bestCacheDir.parentFile
                    if (parentDir != null && parentDir.exists() && parentDir.isDirectory) {
                        result.append("\n- Другие файлы на флешке:\n")
                        val parentFiles = parentDir.listFiles()
                        if (parentFiles != null) {
                            parentFiles.take(5).forEach { file ->
                                if (file != bestCacheDir) { // Пропускаем директорию кэша
                                    val fileSize = file.length()
                                    val fileSizeMB = fileSize / (1024.0 * 1024.0)

                                    val channelSize = getFileSizeWithChannel(file)
                                    val channelSizeMB = channelSize / (1024.0 * 1024.0)

                                    val randomAccessSize = getFileSizeWithRandomAccess(file)
                                    val randomAccessSizeMB = randomAccessSize / (1024.0 * 1024.0)

                                    result.append("  - ${file.name}:\n")
                                    result.append("    File.length: ${fileSizeMB.toInt()} MB\n")
                                    result.append("    FileChannel: ${channelSizeMB.toInt()} MB\n")
                                    result.append("    RandomAccess: ${randomAccessSizeMB.toInt()} MB\n")
                                }
                            }
                        }
                    }
                } else {
                    result.append("- Не удалось получить список файлов в директории кэша\n")
                }
            }

            // Проверяем базу данных кэша
            val cacheDbFile = File(bestCacheDir, "exoplayer.db")
            val cacheDbJournalFile = File(bestCacheDir, "exoplayer.db-journal")
            val cacheDbExists = cacheDbFile.exists()
            val cacheDbCanRead = cacheDbFile.canRead()
            val cacheDbSize = cacheDbFile.length()
            val cacheDbJournalExists = cacheDbJournalFile.exists()

            result.append("- База данных кэша:\n")
            result.append("  - Существует: $cacheDbExists, Чтение: $cacheDbCanRead, Размер: ${cacheDbSize / 1024} KB\n")
            result.append("  - Journal существует: $cacheDbJournalExists\n")

            // Проверяем размер кэша из Media3
            val cacheSize = simpleCache.cacheSpace
            val cacheSizeMB = cacheSize / (1024.0 * 1024.0)
            result.append("- Размер кэша (Media3): ${cacheSizeMB.toInt()} MB\n")

            // Проверяем ключи в кэше
            val cacheKeys = simpleCache.keys
            result.append("- Ключи в кэше (${cacheKeys.size}):\n")
            cacheKeys.forEachIndexed { index, key ->
                // Проверяем размер закэшированных данных для каждого ключа
                val cachedBytes = simpleCache.getCachedBytes(key, 0, Long.MAX_VALUE)
                val cachedMB = cachedBytes / (1024.0 * 1024.0)
                result.append("  ${index + 1}. $key: ${cachedMB.toInt()} MB\n")

                // Проверяем спаны для каждого ключа
                try {
                    val spans = simpleCache.getCachedSpans(key)
                    if (spans.isNotEmpty()) {
                        result.append("    - Спаны (${spans.size}): ")
                        spans.forEachIndexed { spanIndex, span ->
                            val spanSize = span.length
                            val spanSizeMB = spanSize / (1024.0 * 1024.0)
                            result.append("${spanSizeMB.toInt()} MB${if (spanIndex < spans.size - 1) ", " else ""}")
                        }
                        result.append("\n")
                    } else {
                        result.append("    - Спаны: нет\n")
                    }
                } catch (e: Exception) {
                    result.append("    - Ошибка при получении спанов: ${e.message}\n")
                }
            }

        } catch (e: Exception) {
            result.append("\nОшибка при сканировании хранилищ: ${e.message}")
            Log.e(TAG, "Error scanning storage", e)
        }

        return result.toString()
    }

    /**
     * Проверяет, является ли хранилище съемным (флешкой)
     */
    private fun isExternalStorageRemovable(path: File): Boolean {
        try {
            // На Android TV флешка обычно содержит в пути слова "usb" или "sdcard"
            val pathStr = path.absolutePath.lowercase()
            val isUSB = pathStr.contains("usb") || pathStr.contains("sdcard") || !pathStr.contains("emulated/0")

            // Также проверяем, что это не первый элемент в списке внешних хранилищ (обычно первый - это внутренняя память)
            val externalDirs = applicationContext.getExternalFilesDirs(null)
            val isNotFirstStorage = externalDirs.isNotEmpty() && externalDirs[0] != path

            // Дополнительная проверка - если свободного места больше, чем во внутренней памяти
            val hasMoreSpace = path.freeSpace > applicationContext.cacheDir.freeSpace * 1.5 // В 1.5 раза больше места

            return (isUSB || isNotFirstStorage) && hasMoreSpace
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if storage is removable", e)
            return false
        }
    }

    /**
     * Пересоздает кэш приложения
     */
    @OptIn(UnstableApi::class)
    private fun recreateCache() {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnRecreateCache.isEnabled = false

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Сначала очищаем кэш
                val cacheKeys = simpleCache.keys
                Log.d(TAG, "Clearing cache before recreation. Keys: $cacheKeys")

                // Удаляем все ключи из кэша
                cacheKeys.forEach { key ->
                    simpleCache.removeResource(key)
                }

                // Сбрасываем информацию о последнем файле
                ExoVideoPlayer.lastCachedFileName = ""
                ExoVideoPlayer.lastCachedUrl = null
                ExoVideoPlayer.lastCacheProgress = 0

                // Закрываем текущий кэш
                simpleCache.release()
                Log.d(TAG, "Cache released")

                // Удаляем файлы кэша напрямую
                val cacheDir = ExoVideoPlayer.findBestCacheDir()
                if (cacheDir.exists() && cacheDir.isDirectory) {
                    val files = cacheDir.listFiles()
                    if (files != null) {
                        for (file in files) {
                            val deleted = file.delete()
                            Log.d(TAG, "Deleting file ${file.absolutePath}: $deleted")
                        }
                    }
                }

                // Пересоздаем кэш
                ExoVideoPlayer.recreateCache()
                Log.d(TAG, "Cache recreated")

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.btnRecreateCache.isEnabled = true
                    Toast.makeText(this@StorageInfoActivity, "Кэш успешно пересоздан", Toast.LENGTH_SHORT).show()

                    // Обновляем стили кнопок
                    resetButtonStyles()

                    // Выделяем кнопку пересоздания кэша зеленым цветом, чтобы показать успешное завершение
                    binding.btnRecreateCache.setBackgroundColor(resources.getColor(android.R.color.holo_green_light))
                    binding.btnRecreateCache.strokeColor = resources.getColorStateList(android.R.color.white)
                    binding.btnRecreateCache.strokeWidth = 3
                    binding.btnRecreateCache.textSize = 16f

                    // Обновляем информацию о хранилищах
                    updateStorageInfo("Статус: Кэш пересоздан")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error recreating cache", e)
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.btnRecreateCache.isEnabled = true
                    Toast.makeText(this@StorageInfoActivity, "Ошибка при пересоздании кэша: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Очищает весь кэш приложения
     */
    @OptIn(UnstableApi::class)
    private fun clearAllCache() {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnClearCache.isEnabled = false

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Получаем все ключи в кэше
                val cacheKeys = simpleCache.keys
                Log.d(TAG, "Clearing all cache. Keys before: $cacheKeys")

                // Удаляем все ключи из кэша
                cacheKeys.forEach { key ->
                    simpleCache.removeResource(key)
                    Log.d(TAG, "Removed cache for key: $key")
                }

                // Сбрасываем информацию о последнем файле
                ExoVideoPlayer.lastCachedFileName = ""
                ExoVideoPlayer.lastCachedUrl = null
                ExoVideoPlayer.lastCacheProgress = 0

                // Пытаемся удалить файлы напрямую
                try {
                    val cacheDir = ExoVideoPlayer.findBestCacheDir()
                    if (cacheDir.exists() && cacheDir.isDirectory) {
                        cacheDir.listFiles()?.forEach { file ->
                            if (file.isFile) {
                                val deleted = file.delete()
                                Log.d(TAG, "Deleting file ${file.absolutePath}: $deleted")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error deleting cache files directly", e)
                }

                // Обновляем UI
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.btnClearCache.isEnabled = true
                    Toast.makeText(this@StorageInfoActivity, "Кэш полностью очищен", Toast.LENGTH_SHORT).show()

                    // Обновляем стили кнопок
                    resetButtonStyles()

                    // Выделяем кнопку очистки кэша зеленым цветом, чтобы показать успешное завершение
                    binding.btnClearCache.setBackgroundColor(resources.getColor(android.R.color.holo_green_light))
                    binding.btnClearCache.strokeColor = resources.getColorStateList(android.R.color.white)
                    binding.btnClearCache.strokeWidth = 3
                    binding.btnClearCache.textSize = 16f

                    // Обновляем информацию о хранилищах
                    updateStorageInfo()
                }

                Log.d(TAG, "Cache cleared successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing cache", e)
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.btnClearCache.isEnabled = true
                    Toast.makeText(this@StorageInfoActivity, "Ошибка при очистке кэша: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Альтернативный способ определения размера файла
     */
    private fun getFileSizeAlternative(file: File): Long {
        if (!file.exists() || !file.canRead()) {
            return 0L
        }

        if (file.isDirectory) {
            var size = 0L
            val files = file.listFiles()
            if (files != null) {
                for (subFile in files) {
                    size += getFileSizeAlternative(subFile)
                }
            }
            return size
        }

        // Пробуем разные методы определения размера файла
        val sizeFromChannel = getFileSizeWithChannel(file)
        if (sizeFromChannel > 0) {
            return sizeFromChannel
        }

        val sizeFromRandomAccess = getFileSizeWithRandomAccess(file)
        if (sizeFromRandomAccess > 0) {
            return sizeFromRandomAccess
        }

        val sizeFromStatFs = getFileSizeWithStatFs(file)
        if (sizeFromStatFs > 0) {
            return sizeFromStatFs
        }

        // Если все методы не сработали, пробуем читать файл (медленный метод)
        return getFileSizeByReading(file)
    }

    /**
     * Получает размер файла с помощью FileChannel
     */
    private fun getFileSizeWithChannel(file: File): Long {
        try {
            val fileInputStream = file.inputStream()
            val channel = fileInputStream.channel
            val size = channel.size()
            channel.close()
            fileInputStream.close()
            return size
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file size with channel for ${file.absolutePath}", e)
            return 0L
        }
    }

    /**
     * Получает размер файла с помощью RandomAccessFile
     */
    private fun getFileSizeWithRandomAccess(file: File): Long {
        try {
            val randomAccessFile = java.io.RandomAccessFile(file, "r")
            val size = randomAccessFile.length()
            randomAccessFile.close()
            return size
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file size with random access for ${file.absolutePath}", e)
            return 0L
        }
    }

    /**
     * Получает размер файла с помощью StatFs
     */
    private fun getFileSizeWithStatFs(file: File): Long {
        try {
            // Этот метод работает только для директорий, но может помочь в некоторых случаях
            if (file.isDirectory) {
                val statFs = android.os.StatFs(file.absolutePath)
                val blockSize = statFs.blockSizeLong
                val totalBlocks = statFs.blockCountLong
                return blockSize * totalBlocks
            }
            return 0L
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file size with StatFs for ${file.absolutePath}", e)
            return 0L
        }
    }

    /**
     * Получает размер файла путем чтения (медленный метод)
     */
    private fun getFileSizeByReading(file: File): Long {
        try {
            // Пытаемся открыть файл и прочитать его размер
            val inputStream = file.inputStream()
            val available = inputStream.available().toLong()

            // Если available вернул 0, пробуем прочитать файл до конца
            if (available <= 0) {
                var size = 0L
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    size += bytesRead
                }
                inputStream.close()
                return size
            }

            inputStream.close()
            return available
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file size by reading for ${file.absolutePath}", e)
            return 0L
        }
    }

    /**
     * Сбрасывает стили всех кнопок к обычному
     */
    private fun resetButtonStyles() {
        // Устанавливаем обычный стиль для всех кнопок
        binding.btnRequestPermissions.setBackgroundColor(resources.getColor(android.R.color.holo_blue_dark))
        binding.btnClearCache.setBackgroundColor(resources.getColor(android.R.color.holo_blue_dark))
        binding.btnRecreateCache.setBackgroundColor(resources.getColor(android.R.color.holo_blue_dark))

        // Устанавливаем белую обводку для всех кнопок
        binding.btnRequestPermissions.strokeColor = resources.getColorStateList(android.R.color.white)
        binding.btnClearCache.strokeColor = resources.getColorStateList(android.R.color.white)
        binding.btnRecreateCache.strokeColor = resources.getColorStateList(android.R.color.white)

        // Устанавливаем тонкую обводку для всех кнопок
        binding.btnRequestPermissions.strokeWidth = 1
        binding.btnClearCache.strokeWidth = 1
        binding.btnRecreateCache.strokeWidth = 1

        // Устанавливаем нормальный размер текста
        binding.btnRequestPermissions.textSize = 14f
        binding.btnClearCache.textSize = 14f
        binding.btnRecreateCache.textSize = 14f

        // Делаем кнопку запроса прав неактивной, если она не включена
        if (!binding.btnRequestPermissions.isEnabled) {
            setDisabledButtonStyle(binding.btnRequestPermissions)
        }
    }

    /**
     * Устанавливает стиль выбранной кнопки
     */
    private fun setSelectedButtonStyle(button: com.google.android.material.button.MaterialButton) {
        // Устанавливаем ярко-красный цвет фона для лучшей заметности
        button.setBackgroundColor(resources.getColor(android.R.color.holo_red_light))

        // Устанавливаем белую обводку
        button.strokeColor = resources.getColorStateList(android.R.color.white)

        // Устанавливаем очень толстую обводку
        button.strokeWidth = 5

        // Увеличиваем размер текста для выделения
        button.textSize = 16f
    }

    /**
     * Устанавливает стиль неактивной кнопки
     */
    private fun setDisabledButtonStyle(button: com.google.android.material.button.MaterialButton) {
        // Устанавливаем серый цвет фона
        button.setBackgroundColor(resources.getColor(android.R.color.darker_gray))

        // Устанавливаем серую обводку
        button.strokeColor = resources.getColorStateList(android.R.color.darker_gray)

        // Устанавливаем тонкую обводку
        button.strokeWidth = 1

        // Уменьшаем размер текста
        button.textSize = 12f

        // Делаем текст серым
        button.setTextColor(resources.getColor(android.R.color.white))
    }

    companion object {
        private const val STORAGE_PERMISSION_CODE = 100
    }
}

package com.zed.metaclean

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import com.zed.metaclean.ui.theme.MetaCleanTheme
import kotlinx.coroutines.launch
import java.io.*
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // 处理相册分享的图片
            val sharedImages = handleSharedImages(intent)

            MetaCleanTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        sharedImages
                    )
                }
            }
        }
    }
}

@Composable
fun MainScreen(modifier: Modifier = Modifier, sharedImages: List<Uri> = emptyList()) {
    val context = LocalContext.current
    var selectedImages by remember { mutableStateOf(sharedImages) } // 默认选中分享的图片
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris -> selectedImages = uris }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.padding(16.dp)) {
        Button(onClick = { imagePickerLauncher.launch("image/*") }) {
            Text("选择图片")
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn {
            items(selectedImages) { uri ->
                val bitmap = remember { loadBitmapFromUri(context, uri) }
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().height(200.dp)
                    )

                    Button(onClick = {
                        val newUri = cleanAndSaveImage(context, uri)
                        if (newUri != null) {
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    message = "图片已保存",
                                    actionLabel = "查看",
                                    duration = SnackbarDuration.Short
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    openGallery(context, newUri)
                                }
                            }
                        }
                    }) {
                        Text("保存去信息的图片")
                    }
                }
            }
        }

        SnackbarHost(hostState = snackbarHostState)
    }
}

/**
 * 从 Uri 读取 Bitmap（兼容 Android 版本）
 */
private fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source)
        } else {
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
    } catch (e: IOException) {
        e.printStackTrace()
        null
    }
}

/**
 * 清理图片的 Exif 信息并保存到相册
 */
private fun cleanAndSaveImage(context: Context, uri: Uri): Uri? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            // 创建临时文件存储图片数据
            val tempFile = File.createTempFile("temp", ".jpg", context.cacheDir)
            tempFile.outputStream().use { outputStream -> inputStream.copyTo(outputStream) }

            // 清除 EXIF 数据
            val cleanedFile = removeExifData(tempFile) ?: return null

            // 定义存储到相册的文件名
            val randomFileName = "IMG_${UUID.randomUUID()}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, randomFileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ExifCleaner")
            }

            // 插入到相册
            val imageUri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null

            // 写入去除 Exif 的图片
            context.contentResolver.openOutputStream(imageUri)?.use { outputStream ->
                FileInputStream(cleanedFile).use { inputStream -> inputStream.copyTo(outputStream) }
            }

            imageUri
        }
    } catch (e: IOException) {
        e.printStackTrace()
        null
    }
}

/**
 * 去除 Exif 信息，返回去除后的文件
 */
private fun removeExifData(imageFile: File): File? {
    return try {
        val exif = ExifInterface(imageFile.absolutePath)

        // 反射获取 ExifInterface 内所有 TAG_ 开头的字段，动态清除
        ExifInterface::class.java.fields
            .filter { it.name.startsWith("TAG_") }
            .mapNotNull { it.get(null) as? String }
            .forEach { tag -> exif.setAttribute(tag, null) }

        // 保存修改后的 EXIF 信息
        exif.saveAttributes()

        imageFile // 直接返回原文件，避免额外创建新文件
    } catch (e: IOException) {
        e.printStackTrace()
        null
    }
}

/**
 * 打开相册查看图片
 */
private fun openGallery(context: Context, uri: Uri?) {
    uri ?: return
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "image/*")
        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
    }
    context.startActivity(intent)
}

/**
 * 处理从相册分享的图片
 */
private fun handleSharedImages(intent: Intent): List<Uri> {
    return when (intent.action) {
        Intent.ACTION_SEND -> {  // 处理单张图片
            (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { listOf(it) } ?: emptyList()
        }
        Intent.ACTION_SEND_MULTIPLE -> {  // 处理多张图片
            (intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)) ?: emptyList()
        }
        else -> emptyList()
    }
}

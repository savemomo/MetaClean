package com.zed.metaclean

import android.content.ContentValues
import android.content.Context
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
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import com.zed.metaclean.ui.theme.MetaCleanTheme
import java.io.*
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MetaCleanTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var selectedImages by remember { mutableStateOf<List<Uri>>(emptyList()) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        selectedImages = uris
    }

    Column(modifier = modifier.padding(16.dp)) {
        Button(onClick = { imagePickerLauncher.launch("image/*") }) {
            Text("选择图片")
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn {
            items(selectedImages) { uri ->
                val bitmap by remember(uri) { mutableStateOf(loadBitmapFromUri(context, uri)) }
                bitmap?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                    )
                    Button(onClick = {
                        val newUri = cleanAndSaveImage(context, uri)
                        newUri?.let { println("图片已保存: $it") }
                    }) {
                        Text("清除 EXIF 并保存")
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
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

fun cleanAndSaveImage(context: Context, uri: Uri): Uri? {
    try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val tempFile = File.createTempFile("temp", ".jpg", context.cacheDir)

        // 复制原始图像
        inputStream.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }

        // 清除 EXIF 数据
        removeExifData(tempFile)

        val randomFileName = "IMG_${UUID.randomUUID()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, randomFileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ExifCleaner")
        }

        val imageUri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ) ?: return null

        context.contentResolver.openOutputStream(imageUri)?.use { output ->
            FileInputStream(tempFile).use { input -> input.copyTo(output) }
        }

        return imageUri
    } catch (e: IOException) {
        e.printStackTrace()
    }
    return null
}

fun removeExifData(imageFile: File) {
    try {
        val exif = ExifInterface(imageFile.absolutePath)

        // 获取所有 EXIF 标签并清空
        for (tag in exif.javaClass.fields) {
            if (tag.name.startsWith("TAG_")) {
                val exifTag = tag.get(null) as? String
                if (exifTag != null) {
                    exif.setAttribute(exifTag, null)
                }
            }
        }

        exif.saveAttributes()
    } catch (e: IOException) {
        e.printStackTrace()
    }
}
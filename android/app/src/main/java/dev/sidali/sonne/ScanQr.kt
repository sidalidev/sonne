package dev.sidali.sonne

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executor

/**
 * Lecteur de QR code sans dépendance nouvelle : aperçu Camera2 dans un TextureView, images
 * YUV analysées par ZXing core (déjà utilisé pour dessiner le QR d'appairage).
 * La permission CAMERA doit être accordée avant d'afficher ce composant.
 * `surTexte` est appelé une seule fois, sur le fil principal, avec le contenu du premier QR lu.
 */
@Composable
fun LecteurQr(surTexte: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val camera = remember { CameraQr(context, surTexte) }
    val taille = remember { camera.taille }
    // En portrait, l'image du capteur est tournée d'un quart de tour : on garde ses proportions.
    val portrait = LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE
    val rapport = if (portrait) taille.height.toFloat() / taille.width else taille.width.toFloat() / taille.height
    DisposableEffect(Unit) {
        onDispose { camera.fermer() }
    }
    Box(modifier) {
        AndroidView(
            factory = { ctx -> TextureView(ctx).also { camera.attacher(it) } },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(rapport),
        )
    }
}

/** Caméra arrière, aperçu et analyse. Tout ce qui touche la caméra passe sur un fil dédié. */
@SuppressLint("MissingPermission") // CAMERA vérifiée par l'écran avant d'afficher le lecteur
private class CameraQr(context: Context, private val surTexte: (String) -> Unit) {

    private val contexte = context.applicationContext ?: context
    private val gestionnaire = contexte.getSystemService(CameraManager::class.java)
    private val principal = Handler(Looper.getMainLooper())
    private val fil = HandlerThread("sonne-qr").apply { start() }
    private val arriere = Handler(fil.looper)
    private val executeur = Executor { arriere.post(it) }
    private val lecteur = QRCodeReader()
    private val indices = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))

    private val idCamera: String? = try {
        gestionnaire.cameraIdList.firstOrNull {
            gestionnaire.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: gestionnaire.cameraIdList.firstOrNull()
    } catch (e: Exception) {
        null
    }

    /** Taille commune à l'aperçu et à l'analyse : la plus grande jusqu'à 1280 × 960. */
    val taille: Size = choisirTaille()

    @Volatile
    private var ferme = false

    @Volatile
    private var lu = false
    private var appareil: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var images: ImageReader? = null
    private var surfaceApercu: Surface? = null

    private fun choisirTaille(): Size {
        val defaut = Size(640, 480)
        val id = idCamera ?: return defaut
        return try {
            val carte = gestionnaire.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return defaut
            val apercu = carte.getOutputSizes(SurfaceTexture::class.java)?.toSet() ?: emptySet()
            carte.getOutputSizes(ImageFormat.YUV_420_888)
                ?.filter { it in apercu && it.width <= 1280 && it.height <= 960 }
                ?.maxByOrNull { it.width * it.height } ?: defaut
        } catch (e: Exception) {
            defaut
        }
    }

    fun attacher(vue: TextureView) {
        vue.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, l: Int, h: Int) {
                st.setDefaultBufferSize(taille.width, taille.height)
                val surface = Surface(st)
                arriere.post { ouvrir(surface) }
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, l: Int, h: Int) = Unit

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                fermer()
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
    }

    private fun ouvrir(surface: Surface) {
        val id = idCamera ?: return
        if (ferme) return
        surfaceApercu = surface
        val lecteurImages = ImageReader.newInstance(taille.width, taille.height, ImageFormat.YUV_420_888, 2)
        lecteurImages.setOnImageAvailableListener({ analyser(it) }, arriere)
        images = lecteurImages
        try {
            gestionnaire.openCamera(id, executeur, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (ferme) {
                        camera.close()
                        return
                    }
                    appareil = camera
                    demarrerSession(camera, surface, lecteurImages.surface)
                }

                override fun onDisconnected(camera: CameraDevice) = camera.close()

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.w(TAG, "Camera error ($error)")
                    camera.close()
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open the camera: ${e.message}")
        }
    }

    private fun demarrerSession(camera: CameraDevice, apercu: Surface, analyse: Surface) {
        val sorties = listOf(OutputConfiguration(apercu), OutputConfiguration(analyse))
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            sorties,
            executeur,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (ferme) {
                        s.close()
                        return
                    }
                    session = s
                    try {
                        val requete = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(apercu)
                            addTarget(analyse)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }
                        s.setRepeatingRequest(requete.build(), null, arriere)
                    } catch (e: Exception) {
                        Log.w(TAG, "Cannot start the preview: ${e.message}")
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    Log.w(TAG, "Camera session refused")
                }
            },
        )
        try {
            camera.createCaptureSession(config)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open a camera session: ${e.message}")
        }
    }

    /** Analyse la luminance (plan Y) de la dernière image ; les images en retard sont sautées. */
    private fun analyser(source: ImageReader) {
        val image = try {
            source.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return
        try {
            if (lu || ferme) return
            val plan = image.planes[0]
            val pas = plan.rowStride
            val l = image.width
            val h = image.height
            val tampon = plan.buffer
            val octets = ByteArray(pas * h)
            tampon.get(octets, 0, minOf(tampon.remaining(), octets.size))
            val luminance = PlanarYUVLuminanceSource(octets, pas, h, 0, 0, l, h, false)
            val texte = try {
                lecteur.decode(BinaryBitmap(HybridBinarizer(luminance)), indices).text
            } catch (e: ReaderException) {
                null
            } finally {
                lecteur.reset()
            }
            if (texte != null && !lu) {
                lu = true
                principal.post { if (!ferme) surTexte(texte) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot decode the frame: ${e.message}")
        } finally {
            image.close()
        }
    }

    fun fermer() {
        if (ferme) return
        ferme = true
        arriere.post {
            try {
                session?.close()
                appareil?.close()
                images?.close()
                surfaceApercu?.release()
            } catch (_: Exception) {
            }
            session = null
            appareil = null
            images = null
            surfaceApercu = null
            fil.quitSafely()
        }
    }

    private companion object {
        const val TAG = "SonneQr"
    }
}

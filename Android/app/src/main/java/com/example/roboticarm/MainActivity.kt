package com.example.roboticarm

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.example.roboticarm.ui.theme.RoboticArmTheme
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import kotlin.math.*

data class KinematicJointConfig(
    val id: Int,
    val label: String,
    val minLimit: Float,
    val maxLimit: Float,
    val homeAngle: Float
)

enum class ControlMode { SLIDERS, JOYSTICK, CALIBRAZIONE }

class MainActivity : ComponentActivity() {

    private var mqttClient: MqttAsyncClient? = null
    private var webViewInstance: WebView? = null

    // Rimosso il 4° attuatore (Pinza)
    private val robotJoints = listOf(
        KinematicJointConfig(0, "Leva 1: Link 1 (Attuatore 0)", 0f, 270f, 135f),
        KinematicJointConfig(1, "Leva 2: Link 2 (Attuatore 1)", 0f, 270f, 90f),
        KinematicJointConfig(2, "Leva 3: Link 3 (Attuatore 2)", 0f, 270f, 135f)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        setContent {
            RoboticArmTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF1E1E1E)) {
                    MainApplicationLayer()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    fun MainApplicationLayer() {
        var mqttServerUri by remember { mutableStateOf("wss://unexploratory-franchesca-lipochromic.ngrok-free.dev:443") }
        var mqttTopic by remember { mutableStateOf("robot/servos/cmd") }
        var showSettingsDialog by remember { mutableStateOf(false) }
        var controlMode by remember { mutableStateOf(ControlMode.SLIDERS) }

        // Rimossi i riferimenti a claw1.stl e claw2.stl
        val stlFiles = remember { mutableStateListOf("BaseArm-Body.stl", "Arm2_final.stl", "Arm3_final.stl", "Arm4.stl") }
        val jointAngles = remember { mutableStateMapOf<Int, Float>() }

        LaunchedEffect(Unit) {
            robotJoints.forEach { jointAngles[it.id] = it.homeAngle }
        }

        LaunchedEffect(mqttServerUri) {
            initMqttSubsystem(mqttServerUri)
        }

        if (showSettingsDialog) {
            SettingsDialog(
                currentUri = mqttServerUri,
                currentTopic = mqttTopic,
                currentStls = stlFiles,
                onDismiss = { showSettingsDialog = false },
                onSave = { newUri, newTopic, newStls ->
                    mqttServerUri = newUri
                    mqttTopic = newTopic
                    newStls.forEachIndexed { index, newFileName ->
                        if (stlFiles[index] != newFileName) {
                            stlFiles[index] = newFileName
                            webViewInstance?.evaluateJavascript("javascript:reloadSTL($index, '$newFileName');", null)
                        }
                    }
                    showSettingsDialog = false
                }
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Controllo Cinematico", color = Color.White, fontWeight = FontWeight.Bold)

                Button(
                    onClick = {
                        robotJoints.forEach { joint ->
                            jointAngles[joint.id] = 0f
                            dispatchKinematicCommand(joint.id, 0f)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("[ AZZERA MOTORI ]", color = Color.White, fontWeight = FontWeight.Bold)
                }

                IconButton(onClick = { showSettingsDialog = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "Impostazioni", tint = Color.White)
                }
            }

            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                RadioButton(selected = controlMode == ControlMode.SLIDERS, onClick = { controlMode = ControlMode.SLIDERS })
                Text("Leve", color = Color.White, modifier = Modifier.padding(top = 14.dp, end = 8.dp))

                RadioButton(selected = controlMode == ControlMode.JOYSTICK, onClick = { controlMode = ControlMode.JOYSTICK })
                Text("Joy", color = Color.White, modifier = Modifier.padding(top = 14.dp, end = 8.dp))

                RadioButton(selected = controlMode == ControlMode.CALIBRAZIONE, onClick = { controlMode = ControlMode.CALIBRAZIONE })
                Text("Calibrazione Costruttiva", color = Color.Cyan, modifier = Modifier.padding(top = 14.dp))
            }

            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                        val assetLoader = WebViewAssetLoader.Builder()
                            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                            .build()

                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest
                            ): WebResourceResponse? {
                                return assetLoader.shouldInterceptRequest(request.url)
                            }
                        }

                        webChromeClient = WebChromeClient()
                        loadUrl("https://appassets.androidplatform.net/assets/preview3d.html")
                        webViewInstance = this

                        postDelayed({
                            robotJoints.forEach { joint ->
                                dispatchKinematicCommand(joint.id, jointAngles[joint.id] ?: 0f)
                            }
                        }, 1000)
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(0.40f)
            )

            Box(modifier = Modifier.fillMaxWidth().weight(0.60f).padding(8.dp)) {
                when (controlMode) {
                    ControlMode.SLIDERS -> {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            robotJoints.forEach { jointConfig ->
                                val currentVal = jointAngles[jointConfig.id] ?: jointConfig.homeAngle
                                JointControlSlider(jointConfig, currentVal) { id, newVal ->
                                    jointAngles[id] = newVal
                                    dispatchKinematicCommand(id, newVal)
                                }
                            }
                        }
                    }
                    ControlMode.JOYSTICK -> {
                        // Implementazione joystick omessa visivamente per pulizia layout
                    }
                    ControlMode.CALIBRAZIONE -> {
                        CalibrationPanel(webViewInstance)
                    }
                }
            }
        }
    }

    private fun initMqttSubsystem(uri: String) {
        try {
            mqttClient?.disconnect()
            mqttClient = MqttAsyncClient(uri, "AndroidWssClient_${System.currentTimeMillis()}", MemoryPersistence())
            val options = MqttConnectOptions().apply {
                userName = "admin"
                password = "mqtt_secure_pass".toCharArray()
                isCleanSession = true
                connectionTimeout = 5
            }
            mqttClient?.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    runOnUiThread { Toast.makeText(applicationContext, "Broker MQTT Connesso", Toast.LENGTH_SHORT).show() }
                }
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    exception?.printStackTrace()
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun dispatchKinematicCommand(jointIndex: Int, angle: Float) {
        val angleInt = angle.roundToInt()
        if (mqttClient?.isConnected == true) {
            try {
                val payload = "$jointIndex,$angleInt".toByteArray()
                mqttClient?.publish("robot/servos/cmd", payload, 0, false)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        webViewInstance?.post {
            webViewInstance?.evaluateJavascript("javascript:updateJointAngle($jointIndex, $angleInt);", null)
        }
    }

    @Composable
    fun JointControlSlider(config: KinematicJointConfig, currentValue: Float, onUpdate: (Int, Float) -> Unit) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Text(
                text = "${config.label}: ${currentValue.roundToInt()}°",
                color = Color.LightGray,
                fontWeight = FontWeight.Bold
            )
            Slider(
                value = currentValue,
                onValueChange = { onUpdate(config.id, it) },
                valueRange = config.minLimit..config.maxLimit
            )
        }
    }

    @Composable
    fun CalibrationPanel(webView: WebView?) {
        var selectedNode by remember { mutableIntStateOf(1) }
        val calibState = remember { mutableStateMapOf<String, Float>() }

        fun updateJS(type: String, axis: String, value: Float) {
            calibState["${type}_${selectedNode}_${axis}"] = value
            webView?.evaluateJavascript("javascript:updateJointConfig($selectedNode, '$type', '$axis', $value);", null)
        }

        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text("Seleziona Nodo Cinematico (Indice JS):", color = Color.White)
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                // Ridotto iteratore nodi ai 4 rimanenti (0..3)
                (0..3).forEach { nodeIdx ->
                    Button(
                        onClick = { selectedNode = nodeIdx },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedNode == nodeIdx) Color.Cyan else Color.DarkGray
                        ),
                        modifier = Modifier.padding(4.dp)
                    ) { Text("N$nodeIdx", color = if (selectedNode == nodeIdx) Color.Black else Color.White) }
                }
            }

            Divider(color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))

            Text("Joint Position (Origine Rotazione)", color = Color.Yellow, fontWeight = FontWeight.Bold)
            CalibSlider("X", calibState["jointPos_${selectedNode}_x"] ?: 0f) { updateJS("jointPos", "x", it) }
            CalibSlider("Y", calibState["jointPos_${selectedNode}_y"] ?: 0f) { updateJS("jointPos", "y", it) }
            CalibSlider("Z", calibState["jointPos_${selectedNode}_z"] ?: 0f) { updateJS("jointPos", "z", it) }

            Divider(color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))

            Text("Mesh Offset (Traslazione Geometria STL)", color = Color.Green, fontWeight = FontWeight.Bold)
            CalibSlider("X", calibState["meshOffset_${selectedNode}_x"] ?: 0f) { updateJS("meshOffset", "x", it) }
            CalibSlider("Y", calibState["meshOffset_${selectedNode}_y"] ?: 0f) { updateJS("meshOffset", "y", it) }
            CalibSlider("Z", calibState["meshOffset_${selectedNode}_z"] ?: 0f) { updateJS("meshOffset", "z", it) }

            Divider(color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))

            Text("Mesh Rotation (Orientamento Assoluto STL)", color = Color.Magenta, fontWeight = FontWeight.Bold)
            CalibSlider("X", calibState["meshRotation_${selectedNode}_x"] ?: 0f, -360f..360f) { updateJS("meshRotation", "x", it) }
            CalibSlider("Y", calibState["meshRotation_${selectedNode}_y"] ?: 0f, -360f..360f) { updateJS("meshRotation", "y", it) }
            CalibSlider("Z", calibState["meshRotation_${selectedNode}_z"] ?: 0f, -360f..360f) { updateJS("meshRotation", "z", it) }
        }
    }

    @Composable
    fun CalibSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float> = -250f..250f, onValueChange: (Float) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "$label: ${value.roundToInt()}", color = Color.White, modifier = Modifier.width(60.dp))
            Slider(value = value, onValueChange = onValueChange, valueRange = range, modifier = Modifier.weight(1f))
        }
    }

    @Composable
    fun SettingsDialog(
        currentUri: String,
        currentTopic: String,
        currentStls: List<String>,
        onDismiss: () -> Unit,
        onSave: (String, String, List<String>) -> Unit
    ) {
        var uri by remember { mutableStateOf(currentUri) }
        var topic by remember { mutableStateOf(currentTopic) }
        val stls = remember { mutableStateListOf(*currentStls.toTypedArray()) }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Parametri di Sistema") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(value = uri, onValueChange = { uri = it }, label = { Text("Broker URI") })
                    OutlinedTextField(value = topic, onValueChange = { topic = it }, label = { Text("Topic CMD") })
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Puntatori File STL (in assets/)", fontWeight = FontWeight.Bold)
                    stls.forEachIndexed { index, fileName ->
                        OutlinedTextField(value = fileName, onValueChange = { stls[index] = it }, label = { Text("Geometria $index") })
                    }
                }
            },
            confirmButton = { Button(onClick = { onSave(uri, topic, stls.toList()) }) { Text("Applica") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
        )
    }
}
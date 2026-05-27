package com.example.roboticarm

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.roboticarm.ui.theme.RoboticArmTheme
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import kotlin.math.*

enum class ControlMode { SLIDERS, JOYSTICK }

class MainActivity : ComponentActivity() {

    private var mqttClient: MqttAsyncClient? = null
    private var webViewInstance: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        // Connessione WSS Sicura tramite WebSocket
        var mqttServerUri by remember { mutableStateOf("wss://unexploratory-franchesca-lipochromic.ngrok-free.dev:443") }
        var mqttTopic by remember { mutableStateOf("robot/servos/cmd") }
        var controlMode by remember { mutableStateOf(ControlMode.SLIDERS) }
        var showSettingsDialog by remember { mutableStateOf(false) }

        val stlFiles = remember { mutableStateListOf("base.stl", "link1.stl", "link2.stl", "claw.stl") }

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = controlMode == ControlMode.SLIDERS, onClick = { controlMode = ControlMode.SLIDERS })
                    Text("Leve Lineari", color = Color.White)
                    Spacer(modifier = Modifier.width(16.dp))
                    RadioButton(selected = controlMode == ControlMode.JOYSTICK, onClick = { controlMode = ControlMode.JOYSTICK })
                    Text("Dual Joystick", color = Color.White)
                }
                IconButton(onClick = { showSettingsDialog = true }) {
                    Icon(Icons.Default.Settings, contentDescription = "Impostazioni", tint = Color.White)
                }
            }

            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = true
                        settings.allowFileAccessFromFileURLs = true
                        settings.allowUniversalAccessFromFileURLs = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        webViewClient = WebViewClient()
                        webChromeClient = WebChromeClient()
                        loadUrl("file:///android_asset/preview3d.html")
                        webViewInstance = this
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(0.45f)
            )

            Box(modifier = Modifier.fillMaxWidth().weight(0.55f).padding(8.dp)) {
                if (controlMode == ControlMode.SLIDERS) {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        JointControlSlider("Leva 1: Link 1 (Servo 0 - Pin 4)", 0, ::dispatchKinematicCommand)
                        JointControlSlider("Leva 2: Link 2 (Servo 1 - Pin 5)", 1, ::dispatchKinematicCommand)
                        JointControlSlider("Leva 3: Link 3 (Servo 2 - Pin 47)", 2, ::dispatchKinematicCommand)
                        JointControlSlider("Leva 4: Claw (Servo 3 - Pin 48)", 3, ::dispatchKinematicCommand)
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        VirtualJoystick(
                            label = "Leva 1 (X) - Leva 2 (Y)",
                            onPositionChanged = { x, y ->
                                dispatchKinematicCommand(0, x)
                                dispatchKinematicCommand(1, y)
                            }
                        )
                        VirtualJoystick(
                            label = "Leva 3 (X) - Leva 4 (Y)",
                            onPositionChanged = { x, y ->
                                dispatchKinematicCommand(2, x)
                                dispatchKinematicCommand(3, y)
                            }
                        )
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
                    runOnUiThread { Toast.makeText(applicationContext, "Broker MQTT Agganciato", Toast.LENGTH_SHORT).show() }
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
    fun JointControlSlider(label: String, index: Int, onUpdate: (Int, Float) -> Unit) {
        var sliderValue by remember { mutableFloatStateOf(135f) }
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Text(text = "$label: ${sliderValue.roundToInt()}°", color = Color.LightGray, fontWeight = FontWeight.Bold)
            Slider(value = sliderValue, onValueChange = { sliderValue = it; onUpdate(index, it) }, valueRange = 0f..270f)
        }
    }

    @Composable
    fun VirtualJoystick(label: String, onPositionChanged: (xAngle: Float, yAngle: Float) -> Unit) {
        var thumbOffset by remember { mutableStateOf(Offset.Zero) }
        val radius = 150f

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = label, color = Color.LightGray, modifier = Modifier.padding(bottom = 16.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(160.dp)
                    .background(Color(0xFF333333), CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = {
                                thumbOffset = Offset.Zero
                                onPositionChanged(135f, 135f)
                            }
                        ) { change, dragAmount ->
                            change.consume()
                            val newPosition = thumbOffset + dragAmount
                            val distance = hypot(newPosition.x, newPosition.y)

                            thumbOffset = if (distance <= radius) {
                                newPosition
                            } else {
                                val ratio = radius / distance
                                Offset(newPosition.x * ratio, newPosition.y * ratio)
                            }

                            val mapX = ((thumbOffset.x + radius) / (2 * radius)) * 270f
                            val mapY = ((radius - thumbOffset.y) / (2 * radius)) * 270f

                            onPositionChanged(mapX, mapY)
                        }
                    }
            ) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(thumbOffset.x.roundToInt(), thumbOffset.y.roundToInt()) }
                        .size(50.dp)
                        .background(Color(0xFF00E5FF), CircleShape)
                )
            }
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
                        OutlinedTextField(value = fileName, onValueChange = { stls[index] = it }, label = { Text("Giunto $index") })
                    }
                }
            },
            confirmButton = { Button(onClick = { onSave(uri, topic, stls.toList()) }) { Text("Applica") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
        )
    }
}
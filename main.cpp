#include <Arduino.h>
#include <WiFi.h>
#include <PubSubClient.h>

// Credenziali Rete e MQTT
const char* ssid = "MERCUSYS_RE_8F7D";
const char* password = "12101210";
const char* mqtt_server = "6.tcp.eu.ngrok.io";
const int mqtt_port = 25629;
const char* mqtt_user = "admin";
const char* mqtt_pass = "mqtt_secure_pass";
const char* topic_cmd = "robot/servos/cmd";

WiFiClient espClient;
PubSubClient client(espClient);

// Topologia LEDC Nativa
constexpr uint8_t NUM_SERVOS = 4;
constexpr uint8_t SERVO_PINS[NUM_SERVOS] = {4, 5, 47, 48};
constexpr uint8_t LEDC_CHANNELS[NUM_SERVOS] = {0, 1, 2, 3}; 

// Parametri temporali PWM (50Hz, risoluzione 14-bit)
constexpr uint32_t PWM_FREQ = 50;
constexpr uint8_t PWM_RES = 14; 
constexpr uint16_t SERVO_MIN_PULSE = 500;  
constexpr uint16_t SERVO_MAX_PULSE = 2500; 

// ==========================================
// PROFILI CINEMATICI INDIPENDENTI
// ==========================================
// 0.0 = Movimento istantaneo (Massima coppia di spunto, nessuna interpolazione)
// 60.0 = 270 gradi in 4.5 secondi
constexpr float SPEED_DEG_PER_SEC[NUM_SERVOS] = {
    0.0,   // Servo 0 (GPIO 4)  - Pesante: Bypass cinematico
    60.0,  // Servo 1 (GPIO 5)  - Lento: 4.5s
    60.0,  // Servo 2 (GPIO 47) - Lento: 4.5s
    60.0   // Servo 3 (GPIO 48) - Lento: 4.5s
};

struct ServoState {
    float currentAngle;
    float targetAngle;
    uint32_t lastUpdate;
};

ServoState servoData[NUM_SERVOS];

// Calcolo Duty Cycle operando in virgola mobile
uint32_t calcolaDuty(float gradi) {
    if (gradi > 270.0) gradi = 270.0;
    if (gradi < 0.0) gradi = 0.0;
    
    float pulse_us = SERVO_MIN_PULSE + (gradi / 270.0) * (SERVO_MAX_PULSE - SERVO_MIN_PULSE);
    return (uint32_t)((pulse_us * 16384.0) / 20000.0);
}

void setup_wifi() {
    Serial.printf("\n[WIFI] Binding in corso su %s ", ssid);
    WiFi.begin(ssid, password);
    while (WiFi.status() != WL_CONNECTED) {
        delay(500);
        Serial.print(".");
    }
    Serial.printf("\n[WIFI] Link UP. IP: %s\n", WiFi.localIP().toString().c_str());
}

void mqtt_callback(char* topic, byte* payload, unsigned int length) {
    String msg = "";
    for (unsigned int i = 0; i < length; i++) {
        msg += (char)payload[i];
    }
    
    int delimiter = msg.indexOf(',');
    if (delimiter != -1) {
        int idx = msg.substring(0, delimiter).toInt();
        int angle = msg.substring(delimiter + 1).toInt();

        if (idx >= 0 && idx < NUM_SERVOS) {
            if (angle < 0) angle = 0;
            if (angle > 270) angle = 270;
            
            servoData[idx].targetAngle = (float)angle;
            Serial.printf("[CMD] Nuovo target iniettato: Servo %d -> %d gradi\n", idx, angle);
        } else {
            Serial.println("[ERR] Operazione rigettata: Indice out-of-bounds");
        }
    }
}

void reconnect() {
    while (!client.connected()) {
        Serial.print("[MQTT] Handshake TCP in corso... ");
        String clientId = "ESP32S3-KINEMATICS-" + String(random(0xffff), HEX);
        
        if (client.connect(clientId.c_str(), mqtt_user, mqtt_pass)) {
            Serial.println("OK");
            client.subscribe(topic_cmd);
        } else {
            Serial.printf("DROP. RC=%d. Retry in 3s.\n", client.state());
            delay(3000);
        }
    }
}

void setup() {
    Serial.begin(115200);
    delay(1000);
    Serial.println("\n[SYSTEM] Inizializzazione motore cinematico non bloccante");

    for (uint8_t i = 0; i < NUM_SERVOS; i++) {
        ledcSetup(LEDC_CHANNELS[i], PWM_FREQ, PWM_RES);
        ledcAttachPin(SERVO_PINS[i], LEDC_CHANNELS[i]);
        
        // Inizializzazione struttura dati (Avvio a 90 gradi)
        servoData[i].currentAngle = 90.0;
        servoData[i].targetAngle = 90.0;
        servoData[i].lastUpdate = millis();
        
        // Setup layer fisico al boot
        ledcWrite(LEDC_CHANNELS[i], calcolaDuty(servoData[i].currentAngle));
    }

    setup_wifi();
    client.setServer(mqtt_server, mqtt_port);
    client.setCallback(mqtt_callback);
}

void loop() {
    if (!client.connected()) {
        reconnect();
    }
    client.loop(); // Processamento stream di rete

    // ==========================================
    // ROUTINE DI INTERPOLAZIONE ASINCRONA
    // ==========================================
    uint32_t now = millis();
    
    for (uint8_t i = 0; i < NUM_SERVOS; i++) {
        float deltaT = (now - servoData[i].lastUpdate) / 1000.0;
        servoData[i].lastUpdate = now;

        // Esegue se c'è discrepanza tra posizione corrente e target
        if (abs(servoData[i].targetAngle - servoData[i].currentAngle) > 0.1) {
            
            // Bypass cinematico per carichi pesanti
            if (SPEED_DEG_PER_SEC[i] == 0.0) {
                servoData[i].currentAngle = servoData[i].targetAngle;
                ledcWrite(LEDC_CHANNELS[i], calcolaDuty(servoData[i].currentAngle));
                continue; // Salta il resto del calcolo per questo servo
            }
            
            // Interpolazione fluida per gli altri
            float step = SPEED_DEG_PER_SEC[i] * deltaT;
            
            if (servoData[i].targetAngle > servoData[i].currentAngle) {
                servoData[i].currentAngle += step;
                if (servoData[i].currentAngle > servoData[i].targetAngle) {
                    servoData[i].currentAngle = servoData[i].targetAngle; // Clamping
                }
            } else {
                servoData[i].currentAngle -= step;
                if (servoData[i].currentAngle < servoData[i].targetAngle) {
                    servoData[i].currentAngle = servoData[i].targetAngle; // Clamping
                }
            }
            
            // Scrittura del micro-step
            ledcWrite(LEDC_CHANNELS[i], calcolaDuty(servoData[i].currentAngle));
        }
    }
}
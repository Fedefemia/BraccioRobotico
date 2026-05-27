# Braccio Robotico 3 DOF - Sistema di Controllo Distribuito MQTT

## Autori: Femia Federico, Storato Nicola
Classe: 5cii
Anno Scolastico: 2025/2026
Panoramica dell'Architettura

Il progetto consiste nell'implementazione hardware e firmware di un braccio robotico a 3 gradi di libertà (DOF). L'architettura prevede un sistema di attuazione e controllo remoto basato sul protocollo di messaggistica asincrona MQTT. Le direttive di posizionamento possono essere iniettate nel sistema tramite interfacce client eterogenee: un'applicazione nativa Android, script operanti su Raspberry Pi, o un'interfaccia client-side eseguita su web browser.
### Specifiche Hardware

    Microcontrollore: ESP32-S3, equipaggiato con antenna esterna per l'ottimizzazione del guadagno RF in ambiente Wi-Fi.

    Attuatori: 4x Servomotori micro MG90S.

    Chassis: Componenti strutturali realizzati in manifattura additiva (stampa 3D) mediante l'estrusione di polimero PLA.

    Topologia di Alimentazione: Rete a doppio rail progettata per mitigare l'interferenza elettromagnetica e le cadute di tensione causate dai transienti di assorbimento induttivo dei motori:

        Rail 1: Alimentazione del modulo logico (ESP32-S3) e di 2 servomotori.

        Rail 2: Alimentazione di potenza dedicata ai restanti 2 servomotori.

### Infrastruttura di Rete e Comunicazione

    Message Broker: Istanza di Eclipse Mosquitto in esecuzione su host Ubuntu.

    Tunneling WAN: Esposizione del servizio MQTT su rete pubblica tramite reverse proxy TCP implementato con ngrok.

    Topic di Sottoscrizione: robot/servos/cmd

    Formato Payload: Parsing di stringhe con delimitatore (CSV) nel formato [ID_SERVO],[ANGOLO_TARGET].

### Architettura del Firmware

Il layer software è sviluppato in C++ tramite il framework Arduino su ambiente PlatformIO.

    Generazione del Segnale di Controllo: Modulazione PWM gestita a livello hardware tramite la periferica nativa LEDC del SoC ESP32. Il segnale opera con una frequenza di 50Hz e una risoluzione a 14-bit, massimizzando la granularità del duty cycle calcolato in virgola mobile.

    Motore Cinematico Non Bloccante: Il firmware esegue una routine di interpolazione asincrona basata sul differenziale temporale (deltaT). Il sistema calcola dinamicamente i micro-step necessari per eseguire transizioni fluide sui motori designati come "lenti", gestendo contemporaneamente il bypass cinematico per gli attuatori che richiedono l'erogazione della massima coppia di spunto in tempo reale.

Dipendenze e Ambiente di Build (platformio.ini)
Ini, TOML

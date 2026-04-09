# ESP32-S3 BLE Receiver (Guition JC4827W543)

This project implements a **Bluetooth Low Energy (BLE)** server on an ESP32-S3 to receive text data from an Android device. It is specifically configured for the **Guition 4.3" LCD board** (JC4827W543) and is designed to bridge Google Maps notification text to the ESP32 via a smartphone.

## Hardware Configuration
* **Microcontroller:** ESP32-S3-WROOM-1-N4R8
* **Development Board:** Guition 4.3" RGB LCD (JC4827W543)
* **Connectivity:** BLE 5.0
* **Features:** 8MB Octal SPI PSRAM

## Critical Build Settings
To ensure the Serial Monitor works and the BLE stack has enough memory to operate alongside the display hardware, the following settings **must** be used in the Arduino IDE under the **Tools** menu:

| Setting | Value | Why it matters |
| :--- | :--- | :--- |
| **Board** | `ESP32S3 Dev Module` | Targets the correct chip architecture. |
| **USB CDC On Boot** | `Enabled` | **Required** to see Serial.print output over the USB-C port. |
| **PSRAM** | `OPI PSRAM` | Enables the 8MB of external RAM on the N4R8 model. |
| **Flash Mode** | `QIO 80MHz` | Matches the high-speed flash memory on this specific board. |
| **Core Debug Level** | `Info` | Provides feedback on the BLE connection status in the console. |

---

## Project Content
* **`BLE_Write_Receiver.ino`**: Sets up the BLE Server. It uses a `BLECharacteristicCallbacks` class to intercept data sent from a smartphone. It handles the raw data pointer to ensure text is captured even when the mobile app defaults to different write modes.
* **`BLE_Read_Sender.ino`**: A script used to verify the ESP32 can send data back to a client (like nRF Connect), ensuring full bidirectional communication is possible.

## BLE Implementation
The system acts as a GATT Server.

The `onWrite` callback processes the data using `pCharacteristic->getData()` to prevent memory pointer issues common with the S3 architecture.

## 📱 Mobile Integration
Testing was performed using the **nRF Connect** app on Android:
1. Connect to **"MyESP32"**.
2. Locate the **Custom Service** (`4faf...`) and **Characteristic** (`beb5...`).
3. Tap the **Write** icon (Up Arrow).
4. Select **UTF-8** or **Text** format.
5. Entering text triggers the `onWrite` event, printing the message and its length to the Serial Monitor.

---
**Codes were taken from Arduino examples and edited by me**

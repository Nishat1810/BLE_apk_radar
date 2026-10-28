#include <Arduino.h>
#include <HardwareSerial.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// HLK-LD2450 UART -> BLE UART stream bridge
// Radar TX -> ESP32-S3 GPIO44 (RX)
// Radar RX -> ESP32-S3 GPIO43 (TX)
// GND -> GND, 5V -> 5VIN
// UART 256000, 8-N-1. No Wi-Fi.

static constexpr int RADAR_RX_PIN = 44;
static constexpr int RADAR_TX_PIN = 43;
static constexpr uint32_t RADAR_BAUD = 256000;

static constexpr char SERVICE_UUID[] = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E";
static constexpr char RX_UUID[]      = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E";
static constexpr char TX_UUID[]      = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E";

// Default ATT MTU 23 gives up to 20 bytes of notification data.
static constexpr size_t BLE_CHUNK = 20;
static constexpr uint16_t QUEUE_CAPACITY = 2048;
static constexpr uint16_t QUEUE_MASK = QUEUE_CAPACITY - 1;
static_assert((QUEUE_CAPACITY & QUEUE_MASK) == 0, "Queue size must be power of two");

HardwareSerial radarSerial(1);
BLECharacteristic* txCharacteristic = nullptr;
volatile bool deviceConnected = false;

uint8_t queueBuffer[QUEUE_CAPACITY];
volatile uint16_t head = 0;
volatile uint16_t tail = 0;

uint32_t radarBytes = 0;
uint32_t bleBytes = 0;
uint32_t droppedBytes = 0;
uint32_t lastStats = 0;

class ServerCallbacks final : public BLEServerCallbacks {
  void onConnect(BLEServer*) override {
    deviceConnected = true;
    Serial.println("BLE connected");
  }

  void onDisconnect(BLEServer*) override {
    deviceConnected = false;
    Serial.println("BLE disconnected; restarting advertising");
    BLEDevice::startAdvertising();
  }
};

class PhoneWriteCallbacks final : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* characteristic) override {
    // getValue() is Arduino String in some ESP32 BLE versions and
    // std::string in others. length() and c_str() work with both.
    const auto value = characteristic->getValue();
    const size_t valueLength = value.length();
    if (valueLength > 0) {
      // Transparent downlink: app commands pass to radar unchanged.
      radarSerial.write(
        reinterpret_cast<const uint8_t*>(value.c_str()),
        valueLength
      );
    }
  }
};

static inline uint16_t queueUsed() {
  return (head - tail) & QUEUE_MASK;
}

static inline bool pushByte(uint8_t b) {
  const uint16_t next = (head + 1) & QUEUE_MASK;
  if (next == tail) {
    ++droppedBytes;
    return false;
  }
  queueBuffer[head] = b;
  head = next;
  return true;
}

void setup() {
  Serial.begin(115200);

  // Increase RX buffering before begin; default TX buffering is sufficient
  // for occasional configuration commands from the app.
  radarSerial.setRxBufferSize(4096);
  radarSerial.begin(RADAR_BAUD, SERIAL_8N1, RADAR_RX_PIN, RADAR_TX_PIN);

  BLEDevice::init("LD2450_BLE_Bridge");
  BLEServer* server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  BLEService* service = server->createService(SERVICE_UUID);

  txCharacteristic = service->createCharacteristic(
    TX_UUID, BLECharacteristic::PROPERTY_NOTIFY
  );
  txCharacteristic->addDescriptor(new BLE2902());

  BLECharacteristic* rxCharacteristic = service->createCharacteristic(
    RX_UUID,
    BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR
  );
  rxCharacteristic->setCallbacks(new PhoneWriteCallbacks());

  service->start();

  BLEAdvertising* advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setScanResponse(true);
  advertising->setMinPreferred(0x06);
  advertising->setMinPreferred(0x12);
  BLEDevice::startAdvertising();

  Serial.println("LD2450 BLE bridge ready");
  Serial.printf("UART1 RX=%d TX=%d baud=%lu\n",
                RADAR_RX_PIN, RADAR_TX_PIN, (unsigned long)RADAR_BAUD);
}

void loop() {
  // Drain UART promptly. Never wait for a full frame or a fixed read length.
  while (radarSerial.available() > 0) {
    const int value = radarSerial.read();
    if (value < 0) break;
    ++radarBytes;
    if (deviceConnected) pushByte(static_cast<uint8_t>(value));
  }

  // Notify a small chunk per loop; Android reconstructs the continuous stream.
  if (deviceConnected && txCharacteristic != nullptr) {
    uint16_t count = queueUsed();
    if (count > BLE_CHUNK) count = BLE_CHUNK;

    if (count > 0) {
      uint8_t chunk[BLE_CHUNK];
      for (uint16_t i = 0; i < count; ++i) {
        chunk[i] = queueBuffer[tail];
        tail = (tail + 1) & QUEUE_MASK;
      }
      txCharacteristic->setValue(chunk, count);
      txCharacteristic->notify();
      bleBytes += count;
    }
  } else {
    // Don't send stale backlog after a reconnect.
    tail = head;
  }

  if (millis() - lastStats >= 2000) {
    lastStats = millis();
    Serial.printf("connected=%d radar=%lu BLE=%lu queued=%u dropped=%lu\n",
      (int)deviceConnected,
      (unsigned long)radarBytes,
      (unsigned long)bleBytes,
      (unsigned int)queueUsed(),
      (unsigned long)droppedBytes);
  }

  delay(1);
}

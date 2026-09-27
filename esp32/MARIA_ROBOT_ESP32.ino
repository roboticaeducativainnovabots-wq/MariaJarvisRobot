/*
  MARIA_ROBOT - ESP32 WROOM + Bluetooth Classic + L298N
  Compatible con ESP32 clásico (ESP-WROOM-32 / ESP-32S).
*/
#include "BluetoothSerial.h"

#if !defined(CONFIG_BT_ENABLED) || !defined(CONFIG_BLUEDROID_ENABLED)
#error Este programa necesita un ESP32 con Bluetooth Classic habilitado.
#endif

BluetoothSerial SerialBT;

const int ENA = 25;
const int IN1 = 26;
const int IN2 = 27;
const int ENB = 14;
const int IN3 = 32;
const int IN4 = 33;

int velocidad = 190;
unsigned long ultimoComando = 0;
const unsigned long TIMEOUT_SEGURIDAD = 2000;

void pwmMotores(int izq, int der) {
  izq = constrain(izq, 0, 255);
  der = constrain(der, 0, 255);
  ledcWrite(ENA, izq);
  ledcWrite(ENB, der);
}
void alto() {
  digitalWrite(IN1, LOW); digitalWrite(IN2, LOW);
  digitalWrite(IN3, LOW); digitalWrite(IN4, LOW);
  pwmMotores(0, 0);
}
void adelante() {
  digitalWrite(IN1, HIGH); digitalWrite(IN2, LOW);
  digitalWrite(IN3, HIGH); digitalWrite(IN4, LOW);
  pwmMotores(velocidad, velocidad);
}
void atras() {
  digitalWrite(IN1, LOW); digitalWrite(IN2, HIGH);
  digitalWrite(IN3, LOW); digitalWrite(IN4, HIGH);
  pwmMotores(velocidad, velocidad);
}
void izquierda() {
  digitalWrite(IN1, LOW); digitalWrite(IN2, HIGH);
  digitalWrite(IN3, HIGH); digitalWrite(IN4, LOW);
  pwmMotores(velocidad, velocidad);
}
void derecha() {
  digitalWrite(IN1, HIGH); digitalWrite(IN2, LOW);
  digitalWrite(IN3, LOW); digitalWrite(IN4, HIGH);
  pwmMotores(velocidad, velocidad);
}
void procesar(String cmd) {
  cmd.trim(); cmd.toUpperCase();
  if (cmd.length() == 0) return;
  ultimoComando = millis();
  if (cmd == "F") adelante();
  else if (cmd == "B") atras();
  else if (cmd == "L") izquierda();
  else if (cmd == "R") derecha();
  else if (cmd == "S") alto();
  else if (cmd.startsWith("V")) {
    int v = cmd.substring(1).toInt();
    velocidad = constrain(v, 80, 255);
  }
}
void setup() {
  Serial.begin(115200);
  pinMode(IN1, OUTPUT); pinMode(IN2, OUTPUT);
  pinMode(IN3, OUTPUT); pinMode(IN4, OUTPUT);
  ledcAttach(ENA, 15000, 8);
  ledcAttach(ENB, 15000, 8);
  alto();
  SerialBT.begin("MARIA_ROBOT");
}
void loop() {
  if (SerialBT.available()) procesar(SerialBT.readStringUntil('\n'));
  if (ultimoComando > 0 && millis() - ultimoComando > TIMEOUT_SEGURIDAD) {
    alto(); ultimoComando = 0;
  }
  delay(5);
}

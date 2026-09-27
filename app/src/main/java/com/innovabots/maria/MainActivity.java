package com.innovabots.maria;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final int REQ_PERMS = 44;
    private static final String ROBOT_NAME = "MARIA_ROBOT";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private TextToSpeech tts;
    private SpeechRecognizer recognizer;
    private TextView status;
    private EditText input;
    private Button connectButton;
    private BluetoothSocket btSocket;
    private OutputStream btOut;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private volatile boolean connected = false;
    private volatile String currentMotion = "S";
    private int speed = 190;

    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (connected) sendRobot(currentMotion);
            heartbeatHandler.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        tts = new TextToSpeech(this, this);
        requestNeededPermissions();
        setupSpeechRecognizer();
        heartbeatHandler.postDelayed(heartbeat, 500);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(10));
        root.setBackgroundColor(Color.rgb(5, 8, 13));

        EyesView eyes = new EyesView(this);
        root.addView(eyes, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(250)));

        TextView title = new TextView(this);
        title.setText("MARÍA");
        title.setTextColor(Color.rgb(70, 220, 245));
        title.setTextSize(30);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = new TextView(this);
        status.setText("Lista. Empareja MARIA_ROBOT desde Ajustes > Bluetooth.");
        status.setTextColor(Color.WHITE);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(8), dp(8), dp(8), dp(10));
        root.addView(status);

        LinearLayout top = row();
        connectButton = button("Conectar ESP32");
        Button talk = button("🎤 Hablar");
        top.addView(connectButton, weight());
        top.addView(talk, weight());
        root.addView(top);

        LinearLayout textRow = row();
        input = new EditText(this);
        input.setHint("Escríbele a María...");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.GRAY);
        input.setSingleLine(true);
        Button send = button("Enviar");
        textRow.addView(input, new LinearLayout.LayoutParams(0, dp(54), 3));
        textRow.addView(send, new LinearLayout.LayoutParams(0, dp(54), 1));
        root.addView(textRow);

        TextView speedLabel = new TextView(this);
        speedLabel.setText("Velocidad motores");
        speedLabel.setTextColor(Color.LTGRAY);
        speedLabel.setGravity(Gravity.CENTER);
        root.addView(speedLabel);

        SeekBar speedBar = new SeekBar(this);
        speedBar.setMax(255);
        speedBar.setProgress(speed);
        root.addView(speedBar);

        Button forward = button("▲ ADELANTE");
        root.addView(forward, new LinearLayout.LayoutParams(dp(210), dp(54)));

        LinearLayout mid = row();
        Button left = button("◀ IZQ");
        Button stop = button("■ ALTO");
        Button right = button("DER ▶");
        mid.addView(left, weight());
        mid.addView(stop, weight());
        mid.addView(right, weight());
        root.addView(mid);

        Button back = button("▼ ATRÁS");
        root.addView(back, new LinearLayout.LayoutParams(dp(210), dp(54)));

        TextView help = new TextView(this);
        help.setText("Di: “María, avanza”, “detente”, “gira a la izquierda”, “¿qué hora es?”");
        help.setTextColor(Color.GRAY);
        help.setGravity(Gravity.CENTER);
        help.setPadding(dp(5), dp(10), dp(5), 0);
        root.addView(help);

        setContentView(root);

        connectButton.setOnClickListener(v -> {
            if (connected) {
                disconnectRobot();
                say("ESP32 desconectado.");
            } else {
                connectRobot();
            }
        });
        talk.setOnClickListener(v -> startListening());
        send.setOnClickListener(v -> {
            String s = input.getText().toString().trim();
            if (!s.isEmpty()) {
                input.setText("");
                processCommand(s);
            }
        });

        forward.setOnClickListener(v -> movement("F", "Voy hacia adelante."));
        back.setOnClickListener(v -> movement("B", "Voy hacia atrás."));
        left.setOnClickListener(v -> movement("L", "Girando a la izquierda."));
        right.setOnClickListener(v -> movement("R", "Girando a la derecha."));
        stop.setOnClickListener(v -> movement("S", "Me detengo."));

        speedBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                speed = Math.max(80, value);
                if (connected && fromUser) sendRobot("V" + speed);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                say("Velocidad en " + speed);
            }
        });
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER);
        return l;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, dp(54), 1);
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }

    private int dp(int v) {
        return (int)(v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void requestNeededPermissions() {
        ArrayList<String> permissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (!permissions.isEmpty()) {
            requestPermissions(permissions.toArray(new String[0]), REQ_PERMS);
        }
    }

    private void setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.setText("Reconocimiento de voz no disponible. Usa texto o botones.");
            return;
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                status.setText("Te escucho...");
            }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {
                status.setText("Pensando...");
            }
            @Override public void onError(int error) {
                if (error == 12) {
                    status.setText("Voz en español no disponible. Instala Español (México) sin conexión o usa texto.");
                } else if (error == SpeechRecognizer.ERROR_NO_MATCH) {
                    status.setText("No entendí. Toca Hablar e inténtalo otra vez.");
                } else {
                    status.setText("Error de voz: " + error + ". Puedes escribir.");
                }
            }
            @Override public void onResults(Bundle results) {
                ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (list != null && !list.isEmpty()) processCommand(list.get(0));
            }
            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    private void startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestNeededPermissions();
            say("Necesito permiso de micrófono.");
            return;
        }
        if (recognizer == null) {
            say("El reconocimiento de voz no está disponible. Puedes escribirme.");
            return;
        }

        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-MX");
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Habla con María");
        recognizer.startListening(i);
    }

    private String norm(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "");
    }

    private boolean hasAny(String s, String... xs) {
        for (String x : xs) if (s.contains(x)) return true;
        return false;
    }

    private void processCommand(String original) {
        String s = norm(original);
        status.setText("Tú: " + original);

        if (hasAny(s, "detente", "alto", "parate", "frena", "stop")) {
            movement("S", "De acuerdo. Me detengo."); return;
        }
        if (hasAny(s, "avanza", "adelante", "ve hacia adelante")) {
            movement("F", "Voy hacia adelante."); return;
        }
        if (hasAny(s, "retrocede", "atras", "ve hacia atras")) {
            movement("B", "Voy hacia atrás."); return;
        }
        if (hasAny(s, "gira a la izquierda", "izquierda")) {
            movement("L", "Girando a la izquierda."); return;
        }
        if (hasAny(s, "gira a la derecha", "derecha")) {
            movement("R", "Girando a la derecha."); return;
        }

        if (s.contains("conecta") && (s.contains("esp32") || s.contains("robot"))) {
            connectRobot(); return;
        }
        if (s.contains("desconecta") && (s.contains("esp32") || s.contains("robot"))) {
            disconnectRobot(); say("Bluetooth desconectado."); return;
        }

        if (hasAny(s, "que hora es", "dime la hora", "hora actual")) {
            say("Son las " + new SimpleDateFormat("h:mm a", new Locale("es", "MX")).format(new Date()));
            return;
        }
        if (hasAny(s, "que dia es", "que fecha es", "fecha de hoy")) {
            say("Hoy es " + new SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy",
                    new Locale("es", "MX")).format(new Date()));
            return;
        }
        if (hasAny(s, "como te llamas", "cual es tu nombre", "quien eres")) {
            say("Me llamo María. Soy tu asistente virtual y puedo controlar tu robot con ESP32.");
            return;
        }
        if (hasAny(s, "hola", "buenos dias", "buenas tardes", "buenas noches")) {
            say("Hola. Soy María. ¿En qué te ayudo?");
            return;
        }
        if (hasAny(s, "como estas", "como te sientes")) {
            say("Estoy muy bien y lista para ayudarte. ¿Quieres conversar o mover el robot?");
            return;
        }
        if (hasAny(s, "que puedes hacer", "que sabes hacer", "ayuda")) {
            say("Puedo escucharte, hablar contigo, decirte la hora y la fecha, y controlar el robot.");
            return;
        }
        if (hasAny(s, "cuentame un chiste", "dime un chiste", "chiste")) {
            say("¿Por qué el robot cruzó la calle? Porque alguien le programó una ruta.");
            return;
        }
        if (hasAny(s, "gracias", "muchas gracias")) {
            say("Con gusto. Aquí estoy para ayudarte."); return;
        }
        if (hasAny(s, "adios", "hasta luego", "nos vemos")) {
            say("Hasta luego. Cuando me necesites, aquí estaré."); return;
        }
        if (s.contains("velocidad")) {
            say("La velocidad actual es " + speed + " de 255."); return;
        }
        if (s.contains("bluetooth") || s.contains("esp32")) {
            say(connected ? "Estoy conectada al ESP32." :
                    "Todavía no estoy conectada al ESP32. Empareja MARIA_ROBOT y toca Conectar ESP32.");
            return;
        }

        say("Te escuché decir: " + original +
                ". Esta primera versión conversa sobre temas sencillos y controla el robot.");
    }

    private void movement(String cmd, String phrase) {
        currentMotion = cmd;
        if (!connected) {
            say(phrase + " El ESP32 todavía no está conectado.");
            currentMotion = "S";
            return;
        }
        sendRobot(cmd);
        say(phrase);
    }

    private void connectRobot() {
        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestNeededPermissions();
            say("Autoriza Dispositivos cercanos y vuelve a tocar conectar.");
            return;
        }

        status.setText("Buscando " + ROBOT_NAME + "...");
        io.execute(() -> {
            try {
                BluetoothManager manager = (BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
                BluetoothAdapter adapter = manager.getAdapter();
                if (adapter == null || !adapter.isEnabled()) {
                    throw new IOException("Bluetooth del teléfono está apagado");
                }

                Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                BluetoothDevice target = null;
                for (BluetoothDevice d : bonded) {
                    String n = d.getName();
                    if (n != null && n.equalsIgnoreCase(ROBOT_NAME)) {
                        target = d;
                        break;
                    }
                }

                if (target == null) throw new IOException("No está emparejado " + ROBOT_NAME);

                BluetoothSocket socket = target.createRfcommSocketToServiceRecord(SPP_UUID);
                adapter.cancelDiscovery();
                socket.connect();

                btSocket = socket;
                btOut = socket.getOutputStream();
                connected = true;
                currentMotion = "S";

                writeRobotDirect("V" + speed);
                writeRobotDirect("S");

                runOnUiThread(() -> {
                    connectButton.setText("Desconectar");
                    say("Conectada al ESP32. Ya puedo mover el robot.");
                });
            } catch (Exception e) {
                connected = false;
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("No se pudo conectar")
                        .setMessage(e.getMessage() +
                                "\n\n1. Enciende el ESP32." +
                                "\n2. Ve a Ajustes > Bluetooth." +
                                "\n3. Empareja MARIA_ROBOT." +
                                "\n4. Regresa y toca Conectar ESP32.")
                        .setPositiveButton("Entendido", null)
                        .show());
            }
        });
    }

    private void writeRobotDirect(String cmd) throws IOException {
        if (btOut == null) throw new IOException("Salida Bluetooth no disponible");
        btOut.write((cmd + "\n").getBytes());
        btOut.flush();
    }

    private void sendRobot(String cmd) {
        if (!connected || btOut == null) return;
        io.execute(() -> {
            try {
                writeRobotDirect(cmd);
            } catch (IOException e) {
                connected = false;
                currentMotion = "S";
                runOnUiThread(() -> {
                    connectButton.setText("Conectar ESP32");
                    status.setText("Se perdió la conexión Bluetooth.");
                });
            }
        });
    }

    private void disconnectRobot() {
        try { if (btOut != null) btOut.close(); } catch (Exception ignored) {}
        try { if (btSocket != null) btSocket.close(); } catch (Exception ignored) {}
        btOut = null;
        btSocket = null;
        connected = false;
        currentMotion = "S";
        if (connectButton != null) connectButton.setText("Conectar ESP32");
    }

    private void say(String text) {
        status.setText("María: " + text);
        if (tts != null) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "maria");
    }

    @Override public void onInit(int statusCode) {
        if (statusCode == TextToSpeech.SUCCESS && tts != null) {
            int r = tts.setLanguage(new Locale("es", "MX"));
            tts.setSpeechRate(0.95f);
            if (r == TextToSpeech.LANG_MISSING_DATA ||
                    r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(new Locale("es", "ES"));
            }
        }
    }

    @Override public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) {
            Toast.makeText(this, "Permisos actualizados", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onDestroy() {
        heartbeatHandler.removeCallbacks(heartbeat);
        disconnectRobot();
        if (recognizer != null) recognizer.destroy();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        io.shutdownNow();
        super.onDestroy();
    }
}

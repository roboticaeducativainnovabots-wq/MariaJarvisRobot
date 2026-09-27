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
import android.speech.tts.UtteranceProgressListener;
import android.view.Gravity;
import android.view.WindowManager;
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
    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private TextToSpeech tts;
    private SpeechRecognizer recognizer;
    private TextView status;
    private EditText input;
    private Button connectButton;

    private BluetoothSocket btSocket;
    private OutputStream btOut;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private final Handler voiceHandler = new Handler(Looper.getMainLooper());

    private volatile boolean connected = false;
    private volatile String currentMotion = "S";
    private int speed = 190;

    // Escucha manos libres.
    private boolean continuousListening = true;
    private boolean isListening = false;
    private boolean mariaSpeaking = false;
    private boolean activityActive = false;
    private boolean useSystemLanguage = false;

    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (connected) sendRobot(currentMotion);
            heartbeatHandler.postDelayed(this, 500);
        }
    };

    private final Runnable restartVoice = new Runnable() {
        @Override public void run() {
            startListening();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // El teléfono funciona como la cara de María; mantenemos la pantalla encendida.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        buildUi();
        tts = new TextToSpeech(this, this);
        requestNeededPermissions();
        setupSpeechRecognizer();
        heartbeatHandler.postDelayed(heartbeat, 500);
    }

    @Override protected void onResume() {
        super.onResume();
        activityActive = true;
        scheduleVoiceRestart(900);
    }

    @Override protected void onPause() {
        activityActive = false;
        voiceHandler.removeCallbacks(restartVoice);
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
        }
        isListening = false;
        super.onPause();
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
        status.setText("🎤 Escucha automática activa. Habla con María.");
        status.setTextColor(Color.WHITE);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(8), dp(8), dp(8), dp(10));
        root.addView(status);

        connectButton = button("Conectar ESP32");
        root.addView(connectButton,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        TextView autoListen = new TextView(this);
        autoListen.setText("● MANOS LIBRES ACTIVADO — no necesitas tocar un botón");
        autoListen.setTextColor(Color.rgb(80, 230, 160));
        autoListen.setGravity(Gravity.CENTER);
        autoListen.setPadding(dp(4), dp(8), dp(4), dp(8));
        root.addView(autoListen);

        LinearLayout textRow = row();
        input = new EditText(this);
        input.setHint("También puedes escribirle a María...");
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
        help.setText("Solo habla: “María, avanza”, “María, detente”, “María, qué hora es”.");
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
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (!permissions.isEmpty()) {
            requestPermissions(permissions.toArray(new String[0]), REQ_PERMS);
        }
    }

    private void setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.setText("Reconocimiento de voz no disponible. Revisa el servicio de voz de Google.");
            return;
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                isListening = true;
                if (!mariaSpeaking) status.setText("🎤 María está escuchando...");
            }

            @Override public void onBeginningOfSpeech() {
                if (!mariaSpeaking) status.setText("🎤 Te escucho...");
            }

            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}

            @Override public void onEndOfSpeech() {
                isListening = false;
                if (!mariaSpeaking) status.setText("Procesando...");
            }

            @Override public void onError(int error) {
                isListening = false;

                if (!continuousListening || !activityActive || mariaSpeaking) return;

                if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                        (Build.VERSION.SDK_INT >= 31 &&
                                error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)) {
                    useSystemLanguage = true;
                    status.setText("🎤 Ajustando idioma de voz...");
                    scheduleVoiceRestart(600);
                    return;
                }

                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    status.setText("Activa el permiso de micrófono para María.");
                    return;
                }

                // Timeout y NO_MATCH son normales en escucha continua: simplemente vuelve a escuchar.
                if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    status.setText("🎤 María está escuchando...");
                    scheduleVoiceRestart(250);
                    return;
                }

                // Para errores de red/servidor/cliente, espera un poco y reintenta.
                status.setText("🎤 Reconectando escucha...");
                scheduleVoiceRestart(1200);
            }

            @Override public void onResults(Bundle results) {
                isListening = false;
                ArrayList<String> list =
                        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);

                if (list != null && !list.isEmpty()) {
                    String heard = list.get(0);
                    status.setText("Tú: " + heard);
                    processCommand(heard);
                } else {
                    scheduleVoiceRestart(300);
                }

                // Si el comando no produjo voz (por ejemplo mientras conecta Bluetooth),
                // continuamos escuchando igualmente.
                if (!mariaSpeaking) scheduleVoiceRestart(500);
            }

            @Override public void onPartialResults(Bundle partialResults) {
                ArrayList<String> list =
                        partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (list != null && !list.isEmpty() && !mariaSpeaking) {
                    status.setText("🎤 " + list.get(0));
                }
            }

            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    private void scheduleVoiceRestart(long delayMs) {
        voiceHandler.removeCallbacks(restartVoice);
        if (continuousListening && activityActive && !mariaSpeaking) {
            voiceHandler.postDelayed(restartVoice, delayMs);
        }
    }

    private void startListening() {
        if (!continuousListening || !activityActive || mariaSpeaking || isListening) return;

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestNeededPermissions();
            status.setText("María necesita permiso de micrófono.");
            return;
        }

        if (recognizer == null) {
            setupSpeechRecognizer();
            if (recognizer == null) return;
        }

        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);

        if (!useSystemLanguage) {
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX");
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-MX");
        }

        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 650L);

        try {
            recognizer.startListening(i);
            status.setText("🎤 María está escuchando...");
        } catch (Exception e) {
            isListening = false;
            status.setText("🎤 Reiniciando micrófono...");
            scheduleVoiceRestart(1000);
        }
    }

    private String norm(String s) {
        String n = Normalizer.normalize(
                s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "");
    }

    private boolean hasAny(String s, String... xs) {
        for (String x : xs) if (s.contains(x)) return true;
        return false;
    }

    private void processCommand(String original) {
        String s = norm(original);
        status.setText("Tú: " + original);

        // STOP siempre tiene prioridad por seguridad, aunque no diga "María".
        if (hasAny(s, "detente", "alto", "parate", "frena", "stop")) {
            movement("S", "De acuerdo. Me detengo.");
            return;
        }

        if (hasAny(s, "avanza", "adelante", "ve hacia adelante")) {
            movement("F", "Voy hacia adelante.");
            return;
        }

        if (hasAny(s, "retrocede", "atras", "ve hacia atras")) {
            movement("B", "Voy hacia atrás.");
            return;
        }

        if (hasAny(s, "gira a la izquierda", "izquierda")) {
            movement("L", "Girando a la izquierda.");
            return;
        }

        if (hasAny(s, "gira a la derecha", "derecha")) {
            movement("R", "Girando a la derecha.");
            return;
        }

        if (s.contains("conecta") && (s.contains("esp32") || s.contains("robot"))) {
            connectRobot();
            return;
        }

        if (s.contains("desconecta") && (s.contains("esp32") || s.contains("robot"))) {
            disconnectRobot();
            say("Bluetooth desconectado.");
            return;
        }

        if (hasAny(s, "que hora es", "dime la hora", "hora actual")) {
            say("Son las " +
                    new SimpleDateFormat("h:mm a", new Locale("es", "MX")).format(new Date()));
            return;
        }

        if (hasAny(s, "que dia es", "que fecha es", "fecha de hoy")) {
            say("Hoy es " +
                    new SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy",
                            new Locale("es", "MX")).format(new Date()));
            return;
        }

        if (hasAny(s, "como te llamas", "cual es tu nombre", "quien eres")) {
            say("Me llamo María. Soy tu asistente virtual y puedo controlar tu robot con ESP32.");
            return;
        }

        if (hasAny(s, "hola maria", "hola", "buenos dias", "buenas tardes", "buenas noches")) {
            say("Hola. Soy María. Te escucho.");
            return;
        }

        if (hasAny(s, "como estas", "como te sientes")) {
            say("Estoy muy bien y lista para ayudarte.");
            return;
        }

        if (hasAny(s, "que puedes hacer", "que sabes hacer", "ayuda")) {
            say("Puedo escucharte sin botones, hablar contigo, decirte la hora y controlar el robot.");
            return;
        }

        if (hasAny(s, "cuentame un chiste", "dime un chiste", "chiste")) {
            say("¿Por qué el robot cruzó la calle? Porque alguien le programó una ruta.");
            return;
        }

        if (hasAny(s, "gracias", "muchas gracias")) {
            say("Con gusto. Aquí estoy para ayudarte.");
            return;
        }

        if (hasAny(s, "adios", "hasta luego", "nos vemos")) {
            say("Hasta luego. Seguiré atenta mientras la aplicación esté abierta.");
            return;
        }

        if (s.contains("velocidad")) {
            say("La velocidad actual es " + speed + " de 255.");
            return;
        }

        if (s.contains("bluetooth") || s.contains("esp32")) {
            say(connected
                    ? "Estoy conectada al ESP32."
                    : "Todavía no estoy conectada al ESP32. Empareja MARIA_ROBOT y toca Conectar ESP32.");
            return;
        }

        // Evita responder a sonidos aleatorios muy cortos del ambiente.
        if (s.trim().length() < 3) {
            scheduleVoiceRestart(300);
            return;
        }

        say("Te escuché decir: " + original +
                ". Esta versión controla el robot y responde conversaciones sencillas.");
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
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            requestNeededPermissions();
            say("Autoriza dispositivos cercanos para conectar el ESP32.");
            return;
        }

        status.setText("Buscando " + ROBOT_NAME + "...");
        io.execute(() -> {
            try {
                BluetoothManager manager =
                        (BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
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

                if (target == null) {
                    throw new IOException("No está emparejado " + ROBOT_NAME);
                }

                BluetoothSocket socket =
                        target.createRfcommSocketToServiceRecord(SPP_UUID);
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
                runOnUiThread(() -> {
                    new AlertDialog.Builder(this)
                            .setTitle("No se pudo conectar")
                            .setMessage(e.getMessage() +
                                    "\n\n1. Enciende el ESP32." +
                                    "\n2. Ve a Ajustes > Bluetooth." +
                                    "\n3. Empareja MARIA_ROBOT." +
                                    "\n4. Regresa y toca Conectar ESP32.")
                            .setPositiveButton("Entendido", null)
                            .show();
                    scheduleVoiceRestart(700);
                });
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
                    scheduleVoiceRestart(500);
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
        mariaSpeaking = true;
        voiceHandler.removeCallbacks(restartVoice);

        if (recognizer != null && isListening) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
            isListening = false;
        }

        status.setText("María: " + text);

        if (tts != null) {
            int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "maria");
            if (result == TextToSpeech.ERROR) {
                mariaSpeaking = false;
                scheduleVoiceRestart(400);
            }
        } else {
            mariaSpeaking = false;
            scheduleVoiceRestart(400);
        }
    }

    @Override public void onInit(int statusCode) {
        if (statusCode == TextToSpeech.SUCCESS && tts != null) {
            int r = tts.setLanguage(new Locale("es", "MX"));
            tts.setSpeechRate(0.95f);

            if (r == TextToSpeech.LANG_MISSING_DATA ||
                    r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(new Locale("es", "ES"));
            }

            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {
                    mariaSpeaking = true;
                }

                @Override public void onDone(String utteranceId) {
                    runOnUiThread(() -> {
                        mariaSpeaking = false;
                        status.setText("🎤 María está escuchando...");
                        scheduleVoiceRestart(350);
                    });
                }

                @Override public void onError(String utteranceId) {
                    runOnUiThread(() -> {
                        mariaSpeaking = false;
                        scheduleVoiceRestart(350);
                    });
                }
            });

            scheduleVoiceRestart(500);
        }
    }

    @Override public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_PERMS) {
            boolean micGranted =
                    checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED;

            Toast.makeText(
                    this,
                    micGranted
                            ? "Micrófono autorizado. María ya está escuchando."
                            : "María necesita permiso de micrófono.",
                    Toast.LENGTH_SHORT
            ).show();

            if (micGranted) scheduleVoiceRestart(500);
        }
    }

    @Override protected void onDestroy() {
        activityActive = false;
        continuousListening = false;

        heartbeatHandler.removeCallbacks(heartbeat);
        voiceHandler.removeCallbacksAndMessages(null);

        disconnectRobot();

        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
            recognizer.destroy();
        }

        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }

        io.shutdownNow();
        super.onDestroy();
    }
}

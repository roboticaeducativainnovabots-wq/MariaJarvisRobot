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
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
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
    private EyesView eyesView;

    private BluetoothSocket btSocket;
    private OutputStream btOut;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private final Handler voiceHandler = new Handler(Looper.getMainLooper());

    private volatile boolean connected = false;
    private volatile String currentMotion = "S";
    private int speed = 190;

    private boolean continuousListening = true;
    private boolean isListening = false;
    private boolean mariaSpeaking = false;
    private boolean activityActive = false;
    private boolean useSystemLanguage = false;
    private boolean settingsOpen = false;
    private String lastStatus = "Lista";

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

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        enterImmersiveMode();
        buildEyeOnlyUi();

        tts = new TextToSpeech(this, this);
        requestNeededPermissions();
        setupSpeechRecognizer();
        heartbeatHandler.postDelayed(heartbeat, 500);
    }

    private void enterImmersiveMode() {
        Window window = getWindow();

        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersiveMode();
    }

    @Override protected void onResume() {
        super.onResume();
        activityActive = true;
        enterImmersiveMode();
        scheduleVoiceRestart(800);
    }

    @Override protected void onPause() {
        activityActive = false;
        voiceHandler.removeCallbacks(restartVoice);

        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
        }

        isListening = false;
        if (eyesView != null) eyesView.setMode(EyesView.MODE_IDLE);
        super.onPause();
    }

    private void buildEyeOnlyUi() {
        eyesView = new EyesView(this);
        eyesView.setMode(EyesView.MODE_IDLE);

        // Menú oculto: mantener presionada cualquier parte de la pantalla.
        eyesView.setOnLongClickListener(v -> {
            openSettings();
            return true;
        });

        setContentView(eyesView);
    }

    private void setStatus(String text) {
        lastStatus = text == null ? "" : text;
    }

    private void openSettings() {
        if (settingsOpen) return;

        settingsOpen = true;
        voiceHandler.removeCallbacks(restartVoice);

        if (recognizer != null && isListening) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
            isListening = false;
        }

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(14), dp(24), dp(8));

        TextView bluetoothState = new TextView(this);
        bluetoothState.setTextSize(18);
        bluetoothState.setPadding(0, dp(8), 0, dp(12));
        bluetoothState.setText(connected
                ? "ESP32: conectado a " + ROBOT_NAME
                : "ESP32: desconectado");
        panel.addView(bluetoothState);

        Button btButton = new Button(this);
        btButton.setAllCaps(false);
        btButton.setText(connected ? "Desconectar ESP32" : "Conectar ESP32");
        panel.addView(btButton);

        TextView speedTitle = new TextView(this);
        speedTitle.setPadding(0, dp(16), 0, dp(4));
        speedTitle.setText("Velocidad motores: " + speed);
        panel.addView(speedTitle);

        SeekBar speedBar = new SeekBar(this);
        speedBar.setMax(255);
        speedBar.setMin(80);
        speedBar.setProgress(speed);
        panel.addView(speedBar);

        Switch listenSwitch = new Switch(this);
        listenSwitch.setText("Escucha automática");
        listenSwitch.setChecked(continuousListening);
        listenSwitch.setPadding(0, dp(14), 0, dp(8));
        panel.addView(listenSwitch);

        TextView hint = new TextView(this);
        hint.setPadding(0, dp(12), 0, 0);
        hint.setText(
                "También puedes decir: “María, abre configuración”, " +
                "“María, conecta el ESP32” o “María, velocidad 180”."
        );
        panel.addView(hint);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Configuración de María")
                .setView(panel)
                .setNegativeButton("Cerrar", null)
                .create();

        btButton.setOnClickListener(v -> {
            if (connected) {
                disconnectRobot();
                bluetoothState.setText("ESP32: desconectado");
                btButton.setText("Conectar ESP32");
            } else {
                connectRobot();
                bluetoothState.setText("ESP32: conectando...");
            }
        });

        speedBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                speed = Math.max(80, value);
                speedTitle.setText("Velocidad motores: " + speed);
                if (fromUser && connected) sendRobot("V" + speed);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        listenSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            continuousListening = isChecked;

            if (!isChecked) {
                voiceHandler.removeCallbacks(restartVoice);
                if (recognizer != null) {
                    try { recognizer.cancel(); } catch (Exception ignored) {}
                }
                isListening = false;
                eyesView.setMode(EyesView.MODE_IDLE);
            }
        });

        dialog.setOnDismissListener(d -> {
            settingsOpen = false;
            enterImmersiveMode();
            if (continuousListening) scheduleVoiceRestart(350);
        });

        dialog.show();
    }

    private int dp(int value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
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
            setStatus("Reconocimiento de voz no disponible");
            if (eyesView != null) eyesView.setMode(EyesView.MODE_IDLE);
            return;
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(this);

        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                isListening = true;
                setStatus("Escuchando");
                if (eyesView != null) eyesView.setMode(EyesView.MODE_LISTENING);
            }

            @Override public void onBeginningOfSpeech() {
                setStatus("Escuchando voz");
                if (eyesView != null) eyesView.setMode(EyesView.MODE_LISTENING);
            }

            @Override public void onRmsChanged(float rmsdB) {
                if (eyesView != null) eyesView.setVoiceLevel(rmsdB);
            }

            @Override public void onBufferReceived(byte[] buffer) {}

            @Override public void onEndOfSpeech() {
                isListening = false;
                setStatus("Pensando");
                if (!mariaSpeaking && eyesView != null) {
                    eyesView.setMode(EyesView.MODE_THINKING);
                }
            }

            @Override public void onError(int error) {
                isListening = false;

                if (settingsOpen || !continuousListening || !activityActive || mariaSpeaking) {
                    return;
                }

                if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                        (Build.VERSION.SDK_INT >= 31 &&
                                error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)) {
                    useSystemLanguage = true;
                    setStatus("Ajustando idioma");
                    scheduleVoiceRestart(600);
                    return;
                }

                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    setStatus("Falta permiso de micrófono");
                    if (eyesView != null) eyesView.setMode(EyesView.MODE_IDLE);
                    return;
                }

                if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    setStatus("Escuchando");
                    if (eyesView != null) eyesView.setMode(EyesView.MODE_LISTENING);
                    scheduleVoiceRestart(220);
                    return;
                }

                setStatus("Reconectando voz");
                if (eyesView != null) eyesView.setMode(EyesView.MODE_IDLE);
                scheduleVoiceRestart(1000);
            }

            @Override public void onResults(Bundle results) {
                isListening = false;

                ArrayList<String> list =
                        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);

                if (list != null && !list.isEmpty()) {
                    String heard = list.get(0);
                    setStatus("Escuché: " + heard);
                    if (eyesView != null) eyesView.setMode(EyesView.MODE_THINKING);
                    processCommand(heard);
                }

                if (!mariaSpeaking && !settingsOpen) scheduleVoiceRestart(450);
            }

            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    private void scheduleVoiceRestart(long delayMs) {
        voiceHandler.removeCallbacks(restartVoice);

        if (continuousListening &&
                activityActive &&
                !mariaSpeaking &&
                !settingsOpen) {
            voiceHandler.postDelayed(restartVoice, delayMs);
        }
    }

    private void startListening() {
        if (!continuousListening ||
                !activityActive ||
                mariaSpeaking ||
                settingsOpen ||
                isListening) {
            return;
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestNeededPermissions();
            setStatus("María necesita permiso de micrófono");
            return;
        }

        if (recognizer == null) {
            setupSpeechRecognizer();
            if (recognizer == null) return;
        }

        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        );

        if (!useSystemLanguage) {
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX");
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-MX");
        }

        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L);
        i.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                650L
        );

        try {
            recognizer.startListening(i);
            setStatus("Escuchando");
            if (eyesView != null) eyesView.setMode(EyesView.MODE_LISTENING);
        } catch (Exception e) {
            isListening = false;
            setStatus("Reiniciando micrófono");
            if (eyesView != null) eyesView.setMode(EyesView.MODE_IDLE);
            scheduleVoiceRestart(900);
        }
    }

    private String norm(String s) {
        String n = Normalizer.normalize(
                s.toLowerCase(Locale.ROOT),
                Normalizer.Form.NFD
        );
        return n.replaceAll("\\p{M}", "");
    }

    private boolean hasAny(String s, String... values) {
        for (String value : values) {
            if (s.contains(value)) return true;
        }
        return false;
    }

    private void processCommand(String original) {
        String s = norm(original);

        if (hasAny(
                s,
                "abre configuracion",
                "abrir configuracion",
                "abre ajustes",
                "abrir ajustes",
                "configuracion maria"
        )) {
            openSettings();
            return;
        }

        // Seguridad: la orden de detener tiene prioridad y no necesita palabra de activación.
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

        if (s.contains("velocidad")) {
            Integer spokenSpeed = extractNumber(s);

            if (spokenSpeed != null) {
                speed = Math.max(80, Math.min(255, spokenSpeed));

                if (connected) {
                    sendRobot("V" + speed);
                }

                say("Velocidad ajustada a " + speed + ".");
            } else {
                say("La velocidad actual es " + speed + " de 255.");
            }
            return;
        }

        if (hasAny(s, "que hora es", "dime la hora", "hora actual")) {
            say(
                    "Son las " +
                    new SimpleDateFormat(
                            "h:mm a",
                            new Locale("es", "MX")
                    ).format(new Date())
            );
            return;
        }

        if (hasAny(s, "que dia es", "que fecha es", "fecha de hoy")) {
            say(
                    "Hoy es " +
                    new SimpleDateFormat(
                            "EEEE d 'de' MMMM 'de' yyyy",
                            new Locale("es", "MX")
                    ).format(new Date())
            );
            return;
        }

        if (hasAny(s, "como te llamas", "cual es tu nombre", "quien eres")) {
            say("Me llamo María. Soy tu asistente virtual y controlo tu robot.");
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
            say(
                    "Puedo escucharte sin botones, controlar el robot, " +
                    "decirte la hora y abrir mi configuración por voz."
            );
            return;
        }

        if (hasAny(s, "cuentame un chiste", "dime un chiste", "chiste")) {
            say("¿Por qué el robot cruzó la calle? Porque alguien le programó una ruta.");
            return;
        }

        if (hasAny(s, "gracias", "muchas gracias")) {
            say("Con gusto.");
            return;
        }

        if (s.contains("bluetooth") || s.contains("esp32")) {
            say(
                    connected
                    ? "Estoy conectada al ESP32."
                    : "Todavía no estoy conectada al ESP32."
            );
            return;
        }

        if (s.trim().length() >= 3) {
            say(
                    "Te escuché decir: " + original +
                    ". Mi conversación libre se puede ampliar en una siguiente versión."
            );
        } else {
            scheduleVoiceRestart(300);
        }
    }

    private Integer extractNumber(String text) {
        String digits = text.replaceAll("[^0-9]", " ").trim();

        if (!digits.isEmpty()) {
            String[] parts = digits.split("\\s+");
            try {
                return Integer.parseInt(parts[0]);
            } catch (NumberFormatException ignored) {}
        }

        String[][] words = {
                {"ochenta", "80"},
                {"noventa", "90"},
                {"cien", "100"},
                {"ciento veinte", "120"},
                {"ciento cincuenta", "150"},
                {"ciento ochenta", "180"},
                {"doscientos", "200"},
                {"doscientos veinte", "220"},
                {"doscientos cincuenta", "250"},
                {"doscientos cincuenta y cinco", "255"}
        };

        for (String[] pair : words) {
            if (text.contains(pair[0])) {
                return Integer.parseInt(pair[1]);
            }
        }

        return null;
    }

    private void movement(String cmd, String phrase) {
        currentMotion = cmd;

        if (!connected) {
            currentMotion = "S";
            say(phrase + " El ESP32 todavía no está conectado.");
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

        setStatus("Conectando ESP32");
        if (eyesView != null) eyesView.setMode(EyesView.MODE_THINKING);

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
                    String name = d.getName();

                    if (name != null && name.equalsIgnoreCase(ROBOT_NAME)) {
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
                    if (eyesView != null) eyesView.setConnected(true);
                    say("Conectada al ESP32.");
                });

            } catch (Exception e) {
                connected = false;

                runOnUiThread(() -> {
                    if (eyesView != null) {
                        eyesView.setConnected(false);
                        eyesView.setMode(EyesView.MODE_IDLE);
                    }

                    Toast.makeText(
                            this,
                            "No pude conectar MARIA_ROBOT. Empareja el ESP32 en Bluetooth.",
                            Toast.LENGTH_LONG
                    ).show();

                    scheduleVoiceRestart(700);
                });
            }
        });
    }

    private void writeRobotDirect(String cmd) throws IOException {
        if (btOut == null) {
            throw new IOException("Salida Bluetooth no disponible");
        }

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
                    if (eyesView != null) {
                        eyesView.setConnected(false);
                        eyesView.setMode(EyesView.MODE_IDLE);
                    }

                    setStatus("Se perdió Bluetooth");
                    scheduleVoiceRestart(500);
                });
            }
        });
    }

    private void disconnectRobot() {
        try {
            if (btOut != null) btOut.close();
        } catch (Exception ignored) {}

        try {
            if (btSocket != null) btSocket.close();
        } catch (Exception ignored) {}

        btOut = null;
        btSocket = null;
        connected = false;
        currentMotion = "S";

        if (eyesView != null) {
            eyesView.setConnected(false);
            eyesView.setMode(EyesView.MODE_IDLE);
        }
    }

    private void say(String text) {
        mariaSpeaking = true;
        voiceHandler.removeCallbacks(restartVoice);

        if (eyesView != null) {
            eyesView.setMode(EyesView.MODE_SPEAKING);
        }

        if (recognizer != null && isListening) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
            isListening = false;
        }

        setStatus("María: " + text);

        if (tts != null) {
            int result = tts.speak(
                    text,
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "maria"
            );

            if (result == TextToSpeech.ERROR) {
                mariaSpeaking = false;
                if (eyesView != null) eyesView.setMode(EyesView.MODE_IDLE);
                scheduleVoiceRestart(400);
            }
        } else {
            mariaSpeaking = false;
            if (eyesView != null) eyesView.setMode(EyesView.MODE_IDLE);
            scheduleVoiceRestart(400);
        }
    }

    @Override public void onInit(int statusCode) {
        if (statusCode == TextToSpeech.SUCCESS && tts != null) {
            int result = tts.setLanguage(new Locale("es", "MX"));
            tts.setSpeechRate(0.95f);

            if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(new Locale("es", "ES"));
            }

            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {
                    mariaSpeaking = true;
                    runOnUiThread(() -> {
                        if (eyesView != null) eyesView.setMode(EyesView.MODE_SPEAKING);
                    });
                }

                @Override public void onDone(String utteranceId) {
                    runOnUiThread(() -> {
                        mariaSpeaking = false;

                        if (eyesView != null) {
                            eyesView.setMode(EyesView.MODE_LISTENING);
                        }

                        scheduleVoiceRestart(300);
                    });
                }

                @Override public void onError(String utteranceId) {
                    runOnUiThread(() -> {
                        mariaSpeaking = false;

                        if (eyesView != null) {
                            eyesView.setMode(EyesView.MODE_IDLE);
                        }

                        scheduleVoiceRestart(350);
                    });
                }
            });

            scheduleVoiceRestart(500);
        }
    }

    @Override public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_PERMS) {
            boolean micGranted =
                    checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED;

            Toast.makeText(
                    this,
                    micGranted
                            ? "Micrófono autorizado. María está escuchando."
                            : "María necesita permiso de micrófono.",
                    Toast.LENGTH_SHORT
            ).show();

            if (micGranted) scheduleVoiceRestart(400);
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

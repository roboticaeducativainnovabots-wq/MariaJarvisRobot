package com.innovabots.maria;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
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

import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

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

public class MainActivity extends Activity
        implements TextToSpeech.OnInitListener, RecognitionListener {

    private static final int REQ_PERMS = 44;
    private static final String ROBOT_NAME = "MARIA_ROBOT";
    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private TextToSpeech tts;
    private EyesView eyesView;

    // Vosk offline
    private Model voskModel;
    private SpeechService speechService;
    private boolean modelReady = false;
    private boolean continuousListening = true;
    private boolean mariaSpeaking = false;
    private boolean activityActive = false;
    private boolean settingsOpen = false;
    private String voiceState = "Cargando modelo español...";
    private String lastPhrase = "";
    private long lastPhraseTime = 0L;

    // ESP32
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

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        enterImmersiveMode();
        buildEyeOnlyUi();

        tts = new TextToSpeech(this, this);
        requestNeededPermissions();

        LibVosk.setLogLevel(LogLevel.WARNINGS);
        initOfflineSpanishModel();

        heartbeatHandler.postDelayed(heartbeat, 500);
    }

    private void enterImmersiveMode() {
        Window window = getWindow();

        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();

            if (controller != null) {
                controller.hide(
                        WindowInsets.Type.statusBars() |
                        WindowInsets.Type.navigationBars()
                );
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
        startOfflineListeningIfReady();
    }

    @Override protected void onPause() {
        activityActive = false;
        pauseOfflineListening();

        if (eyesView != null) {
            eyesView.setMode(EyesView.MODE_IDLE);
        }

        super.onPause();
    }

    private void buildEyeOnlyUi() {
        eyesView = new EyesView(this);
        eyesView.setMode(EyesView.MODE_THINKING);

        eyesView.setOnLongClickListener(v -> {
            openSettings();
            return true;
        });

        setContentView(eyesView);
    }

    private int dp(int value) {
        return (int)(
                value * getResources().getDisplayMetrics().density + 0.5f
        );
    }

    private void setVoiceState(String state) {
        voiceState = state;
    }

    private void initOfflineSpanishModel() {
        setVoiceState("Preparando español offline");

        if (eyesView != null) {
            eyesView.setMode(EyesView.MODE_THINKING);
        }

        StorageService.unpack(
                this,
                "model-es",
                "model-es-unpacked",
                model -> {
                    voskModel = model;
                    modelReady = true;
                    setVoiceState("Vosk español listo");

                    if (eyesView != null) {
                        eyesView.setMode(EyesView.MODE_IDLE);
                    }

                    startOfflineListeningIfReady();
                },
                exception -> {
                    modelReady = false;
                    setVoiceState(
                            "Error cargando modelo: " + exception.getMessage()
                    );

                    if (eyesView != null) {
                        eyesView.setMode(EyesView.MODE_IDLE);
                    }

                    Toast.makeText(
                            this,
                            "No pude cargar el modelo de voz español: " +
                            exception.getMessage(),
                            Toast.LENGTH_LONG
                    ).show();
                }
        );
    }

    private void startOfflineListeningIfReady() {
        if (!activityActive ||
                settingsOpen ||
                mariaSpeaking ||
                !continuousListening ||
                !modelReady ||
                voskModel == null ||
                speechService != null) {
            return;
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestNeededPermissions();
            setVoiceState("Esperando permiso de micrófono");
            return;
        }

        try {
            Recognizer recognizer =
                    new Recognizer(voskModel, 16000.0f);

            speechService =
                    new SpeechService(recognizer, 16000.0f);

            speechService.startListening(this);

            setVoiceState("Escuchando offline");

            if (eyesView != null) {
                eyesView.setMode(EyesView.MODE_LISTENING);
            }

        } catch (IOException e) {
            setVoiceState(
                    "Error abriendo micrófono Vosk: " + e.getMessage()
            );

            if (eyesView != null) {
                eyesView.setMode(EyesView.MODE_IDLE);
            }

            Toast.makeText(
                    this,
                    "No pude abrir el micrófono.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void pauseOfflineListening() {
        if (speechService != null) {
            try {
                speechService.setPause(true);
            } catch (Exception ignored) {}
        }
    }

    private void resumeOfflineListening() {
        if (!activityActive ||
                settingsOpen ||
                mariaSpeaking ||
                !continuousListening) {
            return;
        }

        if (speechService != null) {
            try {
                speechService.setPause(false);
                setVoiceState("Escuchando offline");

                if (eyesView != null) {
                    eyesView.setMode(EyesView.MODE_LISTENING);
                }

                return;
            } catch (Exception ignored) {}
        }

        startOfflineListeningIfReady();
    }

    private void stopOfflineListening() {
        if (speechService != null) {
            try {
                speechService.stop();
            } catch (Exception ignored) {}

            try {
                speechService.shutdown();
            } catch (Exception ignored) {}

            speechService = null;
        }
    }

    // ---------------- VOSK CALLBACKS ----------------

    @Override public void onPartialResult(String hypothesis) {
        String partial = jsonValue(hypothesis, "partial");

        if (!partial.isEmpty() &&
                !mariaSpeaking &&
                !settingsOpen) {
            setVoiceState("Oyendo: " + partial);

            if (eyesView != null) {
                eyesView.setMode(EyesView.MODE_LISTENING);
            }
        }
    }

    @Override public void onResult(String hypothesis) {
        String text = jsonValue(hypothesis, "text");
        handleRecognizedText(text);
    }

    @Override public void onFinalResult(String hypothesis) {
        // SpeechService puede emitir este callback al detenerse.
        String text = jsonValue(hypothesis, "text");

        if (!text.isEmpty() &&
                activityActive &&
                !mariaSpeaking &&
                !settingsOpen) {
            handleRecognizedText(text);
        }
    }

    @Override public void onError(Exception exception) {
        setVoiceState(
                "Error Vosk: " + exception.getMessage()
        );

        runOnUiThread(() -> {
            stopOfflineListening();

            if (eyesView != null) {
                eyesView.setMode(EyesView.MODE_IDLE);
            }

            if (activityActive &&
                    continuousListening &&
                    !settingsOpen &&
                    !mariaSpeaking) {
                eyesView.postDelayed(
                        this::startOfflineListeningIfReady,
                        800
                );
            }
        });
    }

    @Override public void onTimeout() {
        setVoiceState("Reiniciando escucha");

        runOnUiThread(() -> {
            stopOfflineListening();

            if (activityActive &&
                    continuousListening &&
                    !settingsOpen &&
                    !mariaSpeaking) {
                startOfflineListeningIfReady();
            }
        });
    }

    private String jsonValue(
            String json,
            String key
    ) {
        try {
            return new JSONObject(json)
                    .optString(key, "")
                    .trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private void handleRecognizedText(String text) {
        if (text == null) return;

        text = text.trim();
        if (text.isEmpty()) return;

        long now = System.currentTimeMillis();

        // Evita que una misma frase se procese dos veces por callbacks consecutivos.
        if (text.equals(lastPhrase) &&
                now - lastPhraseTime < 1800) {
            return;
        }

        lastPhrase = text;
        lastPhraseTime = now;

        final String recognized = text;

        runOnUiThread(() -> {
            if (mariaSpeaking || settingsOpen) return;

            setVoiceState("Entendido: " + recognized);

            if (eyesView != null) {
                eyesView.setMode(EyesView.MODE_THINKING);
            }

            processCommand(recognized);
        });
    }

    // ---------------- MENÚ OCULTO ----------------

    private void openSettings() {
        if (settingsOpen) return;

        settingsOpen = true;
        pauseOfflineListening();

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(
                dp(24),
                dp(12),
                dp(24),
                dp(8)
        );

        TextView voiceInfo = new TextView(this);
        voiceInfo.setTextSize(17);
        voiceInfo.setPadding(0, dp(4), 0, dp(10));
        voiceInfo.setText(
                "Voz: Vosk offline en español\n" +
                "Modelo: " +
                (modelReady ? "listo" : "cargando") +
                "\nEstado: " + voiceState
        );
        panel.addView(voiceInfo);

        Switch listenSwitch = new Switch(this);
        listenSwitch.setText("Escucha automática");
        listenSwitch.setChecked(continuousListening);
        listenSwitch.setPadding(0, dp(6), 0, dp(10));
        panel.addView(listenSwitch);

        TextView btState = new TextView(this);
        btState.setTextSize(17);
        btState.setText(
                connected
                ? "ESP32: conectado a " + ROBOT_NAME
                : "ESP32: desconectado"
        );
        panel.addView(btState);

        Button btButton = new Button(this);
        btButton.setAllCaps(false);
        btButton.setText(
                connected
                ? "Desconectar ESP32"
                : "Conectar ESP32"
        );
        panel.addView(btButton);

        TextView speedTitle = new TextView(this);
        speedTitle.setPadding(0, dp(12), 0, dp(4));
        speedTitle.setText(
                "Velocidad motores: " + speed
        );
        panel.addView(speedTitle);

        SeekBar speedBar = new SeekBar(this);
        speedBar.setMax(255);
        speedBar.setMin(80);
        speedBar.setProgress(speed);
        panel.addView(speedBar);

        TextView hint = new TextView(this);
        hint.setPadding(0, dp(10), 0, 0);
        hint.setText(
                "María ya no usa el reconocedor de voz del teléfono. " +
                "El modelo español está dentro de la aplicación y funciona sin Internet."
        );
        panel.addView(hint);

        AlertDialog dialog =
                new AlertDialog.Builder(this)
                .setTitle("Configuración de María 1.4")
                .setView(panel)
                .setNegativeButton("Cerrar", null)
                .create();

        listenSwitch.setOnCheckedChangeListener(
                (buttonView, checked) -> {
                    continuousListening = checked;

                    if (!checked) {
                        pauseOfflineListening();

                        if (eyesView != null) {
                            eyesView.setMode(
                                    EyesView.MODE_IDLE
                            );
                        }
                    }
                }
        );

        btButton.setOnClickListener(v -> {
            if (connected) {
                disconnectRobot();
                btState.setText(
                        "ESP32: desconectado"
                );
                btButton.setText(
                        "Conectar ESP32"
                );
            } else {
                connectRobot();
                btState.setText(
                        "ESP32: conectando..."
                );
            }
        });

        speedBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(
                            SeekBar seekBar,
                            int value,
                            boolean fromUser
                    ) {
                        speed = Math.max(80, value);

                        speedTitle.setText(
                                "Velocidad motores: " +
                                speed
                        );

                        if (fromUser && connected) {
                            sendRobot("V" + speed);
                        }
                    }

                    @Override public void onStartTrackingTouch(
                            SeekBar seekBar
                    ) {}

                    @Override public void onStopTrackingTouch(
                            SeekBar seekBar
                    ) {}
                }
        );

        dialog.setOnDismissListener(d -> {
            settingsOpen = false;
            enterImmersiveMode();

            if (continuousListening) {
                resumeOfflineListening();
            }
        });

        dialog.show();
    }

    private void requestNeededPermissions() {
        ArrayList<String> permissions =
                new ArrayList<>();

        if (checkSelfPermission(
                Manifest.permission.RECORD_AUDIO
        ) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(
                    Manifest.permission.RECORD_AUDIO
            );
        }

        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(
                        Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(
                    Manifest.permission.BLUETOOTH_CONNECT
            );
        }

        if (!permissions.isEmpty()) {
            requestPermissions(
                    permissions.toArray(new String[0]),
                    REQ_PERMS
            );
        }
    }

    // ---------------- COMANDOS ----------------

    private String norm(String text) {
        String normalized =
                Normalizer.normalize(
                        text.toLowerCase(Locale.ROOT),
                        Normalizer.Form.NFD
                );

        return normalized.replaceAll(
                "\\p{M}",
                ""
        );
    }

    private boolean hasAny(
            String text,
            String... values
    ) {
        for (String value : values) {
            if (text.contains(value)) return true;
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

        if (hasAny(
                s,
                "detente",
                "alto",
                "parate",
                "frena",
                "stop"
        )) {
            movement(
                    "S",
                    "De acuerdo. Me detengo."
            );
            return;
        }

        if (hasAny(
                s,
                "avanza",
                "adelante",
                "ve hacia adelante"
        )) {
            movement(
                    "F",
                    "Voy hacia adelante."
            );
            return;
        }

        if (hasAny(
                s,
                "retrocede",
                "atras",
                "ve hacia atras"
        )) {
            movement(
                    "B",
                    "Voy hacia atrás."
            );
            return;
        }

        if (hasAny(
                s,
                "gira a la izquierda",
                "izquierda"
        )) {
            movement(
                    "L",
                    "Girando a la izquierda."
            );
            return;
        }

        if (hasAny(
                s,
                "gira a la derecha",
                "derecha"
        )) {
            movement(
                    "R",
                    "Girando a la derecha."
            );
            return;
        }

        if (s.contains("conecta") &&
                (s.contains("esp32") ||
                 s.contains("robot"))) {
            connectRobot();
            return;
        }

        if (s.contains("desconecta") &&
                (s.contains("esp32") ||
                 s.contains("robot"))) {
            disconnectRobot();
            say("Bluetooth desconectado.");
            return;
        }

        if (s.contains("velocidad")) {
            Integer spokenSpeed =
                    extractNumber(s);

            if (spokenSpeed != null) {
                speed = Math.max(
                        80,
                        Math.min(
                                255,
                                spokenSpeed
                        )
                );

                if (connected) {
                    sendRobot(
                            "V" + speed
                    );
                }

                say(
                        "Velocidad ajustada a " +
                        speed +
                        "."
                );
            } else {
                say(
                        "La velocidad actual es " +
                        speed +
                        " de 255."
                );
            }

            return;
        }

        if (hasAny(
                s,
                "que hora es",
                "dime la hora",
                "hora actual"
        )) {
            say(
                    "Son las " +
                    new SimpleDateFormat(
                            "h:mm a",
                            new Locale(
                                    "es",
                                    "MX"
                            )
                    ).format(new Date())
            );
            return;
        }

        if (hasAny(
                s,
                "que dia es",
                "que fecha es",
                "fecha de hoy"
        )) {
            say(
                    "Hoy es " +
                    new SimpleDateFormat(
                            "EEEE d 'de' MMMM 'de' yyyy",
                            new Locale(
                                    "es",
                                    "MX"
                            )
                    ).format(new Date())
            );
            return;
        }

        if (hasAny(
                s,
                "como te llamas",
                "cual es tu nombre",
                "quien eres"
        )) {
            say(
                    "Me llamo María. " +
                    "Soy tu asistente virtual " +
                    "y controlo tu robot."
            );
            return;
        }

        if (hasAny(
                s,
                "hola maria",
                "hola",
                "buenos dias",
                "buenas tardes",
                "buenas noches"
        )) {
            say(
                    "Hola. Soy María. Te escucho."
            );
            return;
        }

        if (hasAny(
                s,
                "como estas",
                "como te sientes"
        )) {
            say(
                    "Estoy muy bien y lista para ayudarte."
            );
            return;
        }

        if (hasAny(
                s,
                "que puedes hacer",
                "que sabes hacer",
                "ayuda"
        )) {
            say(
                    "Puedo escucharte sin Internet, " +
                    "controlar el robot, decirte la hora " +
                    "y abrir mi configuración."
            );
            return;
        }

        if (hasAny(
                s,
                "cuentame un chiste",
                "dime un chiste",
                "chiste"
        )) {
            say(
                    "¿Por qué el robot cruzó la calle? " +
                    "Porque alguien le programó una ruta."
            );
            return;
        }

        if (hasAny(
                s,
                "gracias",
                "muchas gracias"
        )) {
            say("Con gusto.");
            return;
        }

        if (s.contains("bluetooth") ||
                s.contains("esp32")) {
            say(
                    connected
                    ? "Estoy conectada al ESP32."
                    : "Todavía no estoy conectada al ESP32."
            );
            return;
        }

        // Para comprobar que el reconocimiento funciona,
        // María repite cualquier frase desconocida.
        if (s.trim().length() >= 2) {
            say(
                    "Te escuché decir " +
                    original +
                    "."
            );
        }
    }

    private Integer extractNumber(
            String text
    ) {
        String digits =
                text.replaceAll(
                        "[^0-9]",
                        " "
                ).trim();

        if (!digits.isEmpty()) {
            String[] parts =
                    digits.split("\\s+");

            try {
                return Integer.parseInt(
                        parts[0]
                );
            } catch (
                    NumberFormatException ignored
            ) {}
        }

        String[][] words = {
                {
                        "doscientos cincuenta y cinco",
                        "255"
                },
                {
                        "doscientos cincuenta",
                        "250"
                },
                {
                        "doscientos veinte",
                        "220"
                },
                {
                        "doscientos",
                        "200"
                },
                {
                        "ciento ochenta",
                        "180"
                },
                {
                        "ciento cincuenta",
                        "150"
                },
                {
                        "ciento veinte",
                        "120"
                },
                {"cien", "100"},
                {"noventa", "90"},
                {"ochenta", "80"}
        };

        for (String[] pair : words) {
            if (text.contains(pair[0])) {
                return Integer.parseInt(
                        pair[1]
                );
            }
        }

        return null;
    }

    // ---------------- ROBOT ----------------

    private void movement(
            String command,
            String phrase
    ) {
        currentMotion = command;

        if (!connected) {
            currentMotion = "S";

            say(
                    phrase +
                    " El ESP32 todavía no está conectado."
            );
            return;
        }

        sendRobot(command);
        say(phrase);
    }

    private void connectRobot() {
        if (Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(
                        Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED) {
            requestNeededPermissions();

            say(
                    "Autoriza dispositivos cercanos " +
                    "para conectar el ESP32."
            );
            return;
        }

        if (eyesView != null) {
            eyesView.setMode(
                    EyesView.MODE_THINKING
            );
        }

        io.execute(() -> {
            try {
                BluetoothManager manager =
                        (BluetoothManager)
                        getSystemService(
                                BLUETOOTH_SERVICE
                        );

                BluetoothAdapter adapter =
                        manager.getAdapter();

                if (adapter == null ||
                        !adapter.isEnabled()) {
                    throw new IOException(
                            "Bluetooth del teléfono está apagado"
                    );
                }

                Set<BluetoothDevice> bonded =
                        adapter.getBondedDevices();

                BluetoothDevice target = null;

                for (BluetoothDevice device : bonded) {
                    String name =
                            device.getName();

                    if (name != null &&
                            name.equalsIgnoreCase(
                                    ROBOT_NAME
                            )) {
                        target = device;
                        break;
                    }
                }

                if (target == null) {
                    throw new IOException(
                            "No está emparejado " +
                            ROBOT_NAME
                    );
                }

                BluetoothSocket socket =
                        target.createRfcommSocketToServiceRecord(
                                SPP_UUID
                        );

                adapter.cancelDiscovery();
                socket.connect();

                btSocket = socket;
                btOut =
                        socket.getOutputStream();

                connected = true;
                currentMotion = "S";

                writeRobotDirect(
                        "V" + speed
                );
                writeRobotDirect("S");

                runOnUiThread(() -> {
                    if (eyesView != null) {
                        eyesView.setConnected(
                                true
                        );
                    }

                    say(
                            "Conectada al ESP32."
                    );
                });

            } catch (Exception e) {
                connected = false;

                runOnUiThread(() -> {
                    if (eyesView != null) {
                        eyesView.setConnected(
                                false
                        );
                        eyesView.setMode(
                                EyesView.MODE_IDLE
                        );
                    }

                    Toast.makeText(
                            this,
                            "No pude conectar MARIA_ROBOT. " +
                            "Empareja el ESP32 en Bluetooth.",
                            Toast.LENGTH_LONG
                    ).show();

                    resumeOfflineListening();
                });
            }
        });
    }

    private void writeRobotDirect(
            String command
    ) throws IOException {
        if (btOut == null) {
            throw new IOException(
                    "Salida Bluetooth no disponible"
            );
        }

        btOut.write(
                (command + "\n").getBytes()
        );
        btOut.flush();
    }

    private void sendRobot(
            String command
    ) {
        if (!connected ||
                btOut == null) {
            return;
        }

        io.execute(() -> {
            try {
                writeRobotDirect(
                        command
                );
            } catch (IOException e) {
                connected = false;
                currentMotion = "S";

                runOnUiThread(() -> {
                    if (eyesView != null) {
                        eyesView.setConnected(
                                false
                        );
                        eyesView.setMode(
                                EyesView.MODE_IDLE
                        );
                    }
                });
            }
        });
    }

    private void disconnectRobot() {
        try {
            if (btOut != null) {
                btOut.close();
            }
        } catch (Exception ignored) {}

        try {
            if (btSocket != null) {
                btSocket.close();
            }
        } catch (Exception ignored) {}

        btOut = null;
        btSocket = null;
        connected = false;
        currentMotion = "S";

        if (eyesView != null) {
            eyesView.setConnected(false);
        }
    }

    // ---------------- VOZ DE MARÍA ----------------

    private void say(String text) {
        mariaSpeaking = true;
        pauseOfflineListening();

        if (eyesView != null) {
            eyesView.setMode(
                    EyesView.MODE_SPEAKING
            );
        }

        setVoiceState("María hablando");

        if (tts != null) {
            int result =
                    tts.speak(
                            text,
                            TextToSpeech.QUEUE_FLUSH,
                            null,
                            "maria"
                    );

            if (result ==
                    TextToSpeech.ERROR) {
                mariaSpeaking = false;
                resumeOfflineListening();
            }
        } else {
            mariaSpeaking = false;
            resumeOfflineListening();
        }
    }

    @Override public void onInit(
            int statusCode
    ) {
        if (statusCode ==
                TextToSpeech.SUCCESS &&
                tts != null) {
            int result =
                    tts.setLanguage(
                            new Locale(
                                    "es",
                                    "MX"
                            )
                    );

            tts.setSpeechRate(0.95f);

            if (result ==
                    TextToSpeech.LANG_MISSING_DATA ||
                    result ==
                    TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(
                        new Locale(
                                "es",
                                "ES"
                        )
                );
            }

            tts.setOnUtteranceProgressListener(
                    new UtteranceProgressListener() {
                        @Override public void onStart(
                                String utteranceId
                        ) {
                            mariaSpeaking = true;

                            runOnUiThread(() -> {
                                if (eyesView != null) {
                                    eyesView.setMode(
                                            EyesView.MODE_SPEAKING
                                    );
                                }
                            });
                        }

                        @Override public void onDone(
                                String utteranceId
                        ) {
                            runOnUiThread(() -> {
                                mariaSpeaking = false;

                                if (eyesView != null) {
                                    eyesView.setMode(
                                            EyesView.MODE_LISTENING
                                    );
                                }

                                resumeOfflineListening();
                            });
                        }

                        @Override public void onError(
                                String utteranceId
                        ) {
                            runOnUiThread(() -> {
                                mariaSpeaking = false;
                                resumeOfflineListening();
                            });
                        }
                    }
            );
        }
    }

    @Override public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode == REQ_PERMS) {
            boolean micGranted =
                    checkSelfPermission(
                            Manifest.permission.RECORD_AUDIO
                    ) ==
                    PackageManager.PERMISSION_GRANTED;

            Toast.makeText(
                    this,
                    micGranted
                    ? "Micrófono autorizado. María ya escucha offline."
                    : "María necesita permiso de micrófono.",
                    Toast.LENGTH_SHORT
            ).show();

            if (micGranted) {
                startOfflineListeningIfReady();
            }
        }
    }

    @Override protected void onDestroy() {
        activityActive = false;
        continuousListening = false;

        heartbeatHandler.removeCallbacks(
                heartbeat
        );

        stopOfflineListening();
        disconnectRobot();

        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }

        io.shutdownNow();

        super.onDestroy();
    }
}

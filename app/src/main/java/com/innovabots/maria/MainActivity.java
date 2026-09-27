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
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
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

    private static final int SAMPLE_RATE = 16000;

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

    // Voz
    private boolean continuousListening = true;
    private boolean recognitionRunning = false;
    private boolean mariaSpeaking = false;
    private boolean activityActive = false;
    private boolean useSystemLanguage = false;
    private boolean settingsOpen = false;

    // Detector local de voz: mantiene el micrófono atento sin dejar SpeechRecognizer
    // abierto continuamente.
    private volatile boolean voiceDetectorRunning = false;
    private AudioRecord audioRecord;
    private Thread voiceDetectorThread;
    private int voiceThreshold = 1050;
    private int lastMicLevel = 0;
    private int lastRecognitionError = 0;
    private String voiceState = "Iniciando";

    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (connected) sendRobot(currentMotion);
            heartbeatHandler.postDelayed(this, 500);
        }
    };

    private final Runnable restartVoiceDetector = new Runnable() {
        @Override public void run() {
            startVoiceDetector();
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
        scheduleVoiceDetectorRestart(700);
    }

    @Override protected void onPause() {
        activityActive = false;
        voiceHandler.removeCallbacks(restartVoiceDetector);
        stopVoiceDetector();

        if (recognizer != null && recognitionRunning) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
        }

        recognitionRunning = false;

        if (eyesView != null) {
            eyesView.setMode(EyesView.MODE_IDLE);
        }

        super.onPause();
    }

    private void buildEyeOnlyUi() {
        eyesView = new EyesView(this);
        eyesView.setMode(EyesView.MODE_IDLE);

        // Mantener pulsado para abrir el menú.
        eyesView.setOnLongClickListener(v -> {
            openSettings();
            return true;
        });

        setContentView(eyesView);
    }

    private int dp(int value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void setVoiceState(String state) {
        voiceState = state;
    }

    private void openSettings() {
        if (settingsOpen) return;

        settingsOpen = true;
        voiceHandler.removeCallbacks(restartVoiceDetector);
        stopVoiceDetector();

        if (recognizer != null && recognitionRunning) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
            recognitionRunning = false;
        }

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(12), dp(24), dp(8));

        TextView btState = new TextView(this);
        btState.setTextSize(18);
        btState.setPadding(0, dp(6), 0, dp(10));
        btState.setText(
                connected
                ? "ESP32: conectado a " + ROBOT_NAME
                : "ESP32: desconectado"
        );
        panel.addView(btState);

        Button btButton = new Button(this);
        btButton.setAllCaps(false);
        btButton.setText(
                connected ? "Desconectar ESP32" : "Conectar ESP32"
        );
        panel.addView(btButton);

        TextView speedTitle = new TextView(this);
        speedTitle.setPadding(0, dp(14), 0, dp(4));
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
        listenSwitch.setPadding(0, dp(12), 0, dp(6));
        panel.addView(listenSwitch);

        TextView sensTitle = new TextView(this);
        sensTitle.setPadding(0, dp(12), 0, dp(4));
        sensTitle.setText("Sensibilidad del micrófono: " + sensitivityLabel());
        panel.addView(sensTitle);

        // 0 = poco sensible (umbral alto); 100 = muy sensible (umbral bajo).
        SeekBar sensitivity = new SeekBar(this);
        sensitivity.setMax(100);
        sensitivity.setProgress(thresholdToProgress(voiceThreshold));
        panel.addView(sensitivity);

        TextView diagnostic = new TextView(this);
        diagnostic.setPadding(0, dp(12), 0, dp(4));
        diagnostic.setText(buildDiagnosticText());
        panel.addView(diagnostic);

        Button testVoice = new Button(this);
        testVoice.setAllCaps(false);
        testVoice.setText("Probar micrófono");
        panel.addView(testVoice);

        TextView hint = new TextView(this);
        hint.setPadding(0, dp(10), 0, 0);
        hint.setText(
                "Mantén presionada la pantalla para volver aquí. " +
                "Los comandos no necesitan empezar con “María”; también puedes decir solo “avanza” o “detente”."
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
                btState.setText("ESP32: desconectado");
                btButton.setText("Conectar ESP32");
            } else {
                connectRobot();
                btState.setText("ESP32: conectando...");
            }
        });

        speedBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(
                    SeekBar seekBar,
                    int value,
                    boolean fromUser
            ) {
                speed = Math.max(80, value);
                speedTitle.setText("Velocidad motores: " + speed);

                if (fromUser && connected) {
                    sendRobot("V" + speed);
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        listenSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            continuousListening = checked;

            if (!checked) {
                stopVoiceDetector();

                if (recognizer != null && recognitionRunning) {
                    try { recognizer.cancel(); } catch (Exception ignored) {}
                }

                recognitionRunning = false;

                if (eyesView != null) {
                    eyesView.setMode(EyesView.MODE_IDLE);
                }
            }
        });

        sensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(
                    SeekBar seekBar,
                    int progress,
                    boolean fromUser
            ) {
                voiceThreshold = progressToThreshold(progress);
                sensTitle.setText(
                        "Sensibilidad del micrófono: " + sensitivityLabel()
                );
                diagnostic.setText(buildDiagnosticText());
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        testVoice.setOnClickListener(v -> {
            settingsOpen = false;
            dialog.dismiss();

            Toast.makeText(
                    this,
                    "Prueba: habla normalmente. María activará el reconocimiento cuando detecte tu voz.",
                    Toast.LENGTH_LONG
            ).show();

            scheduleVoiceDetectorRestart(350);
        });

        dialog.setOnDismissListener(d -> {
            settingsOpen = false;
            enterImmersiveMode();

            if (continuousListening) {
                scheduleVoiceDetectorRestart(350);
            }
        });

        dialog.show();
    }

    private String buildDiagnosticText() {
        return "Diagnóstico de voz\n" +
                "Estado: " + voiceState + "\n" +
                "Nivel micrófono: " + lastMicLevel + "\n" +
                "Umbral: " + voiceThreshold + "\n" +
                "Último error reconocimiento: " +
                (lastRecognitionError == 0 ? "ninguno" : lastRecognitionError) + "\n" +
                "Reconocedor Android disponible: " +
                (SpeechRecognizer.isRecognitionAvailable(this) ? "sí" : "no");
    }

    private String sensitivityLabel() {
        if (voiceThreshold <= 650) return "muy alta";
        if (voiceThreshold <= 1000) return "alta";
        if (voiceThreshold <= 1500) return "media";
        if (voiceThreshold <= 2300) return "baja";
        return "muy baja";
    }

    private int thresholdToProgress(int threshold) {
        int min = 450;
        int max = 3200;
        int clamped = Math.max(min, Math.min(max, threshold));
        return 100 - ((clamped - min) * 100 / (max - min));
    }

    private int progressToThreshold(int progress) {
        int min = 450;
        int max = 3200;
        return max - (progress * (max - min) / 100);
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
            requestPermissions(
                    permissions.toArray(new String[0]),
                    REQ_PERMS
            );
        }
    }

    private void setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setVoiceState("El teléfono no tiene un servicio de reconocimiento disponible");
            return;
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(this);

        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                recognitionRunning = true;
                setVoiceState("Reconociendo voz");

                if (eyesView != null) {
                    eyesView.setMode(EyesView.MODE_LISTENING);
                }
            }

            @Override public void onBeginningOfSpeech() {
                setVoiceState("Escuchando frase");

                if (eyesView != null) {
                    eyesView.setMode(EyesView.MODE_LISTENING);
                }
            }

            @Override public void onRmsChanged(float rmsdB) {
                if (eyesView != null) {
                    eyesView.setVoiceLevel(rmsdB);
                }
            }

            @Override public void onBufferReceived(byte[] buffer) {}

            @Override public void onEndOfSpeech() {
                recognitionRunning = false;
                setVoiceState("Procesando frase");

                if (!mariaSpeaking && eyesView != null) {
                    eyesView.setMode(EyesView.MODE_THINKING);
                }
            }

            @Override public void onError(int error) {
                recognitionRunning = false;
                lastRecognitionError = error;

                if (settingsOpen ||
                        !continuousListening ||
                        !activityActive ||
                        mariaSpeaking) {
                    return;
                }

                if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                        (Build.VERSION.SDK_INT >= 31 &&
                                error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)) {
                    useSystemLanguage = true;
                    setVoiceState("Usando idioma del teléfono");
                } else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    setVoiceState("Sin permiso de micrófono");
                    return;
                } else if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    setVoiceState("Reconocedor ocupado");
                } else if (error == SpeechRecognizer.ERROR_NO_MATCH) {
                    setVoiceState("No entendí la frase");
                } else if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    setVoiceState("No llegó voz al reconocedor");
                } else {
                    setVoiceState("Error de reconocimiento " + error);
                }

                if (eyesView != null) {
                    eyesView.setMode(EyesView.MODE_IDLE);
                }

                // Volvemos al detector local en vez de reiniciar SpeechRecognizer
                // una y otra vez.
                scheduleVoiceDetectorRestart(
                        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 1200 : 500
                );
            }

            @Override public void onResults(Bundle results) {
                recognitionRunning = false;
                lastRecognitionError = 0;

                ArrayList<String> list =
                        results.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                        );

                if (list != null && !list.isEmpty()) {
                    String heard = list.get(0);
                    setVoiceState("Entendido: " + heard);

                    if (eyesView != null) {
                        eyesView.setMode(EyesView.MODE_THINKING);
                    }

                    processCommand(heard);
                } else {
                    setVoiceState("Sin resultado");
                    scheduleVoiceDetectorRestart(350);
                }

                if (!mariaSpeaking && !settingsOpen) {
                    scheduleVoiceDetectorRestart(500);
                }
            }

            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    private void scheduleVoiceDetectorRestart(long delayMs) {
        voiceHandler.removeCallbacks(restartVoiceDetector);

        if (continuousListening &&
                activityActive &&
                !mariaSpeaking &&
                !settingsOpen &&
                !recognitionRunning) {
            voiceHandler.postDelayed(restartVoiceDetector, delayMs);
        }
    }

    private void startVoiceDetector() {
        if (!continuousListening ||
                !activityActive ||
                mariaSpeaking ||
                settingsOpen ||
                recognitionRunning ||
                voiceDetectorRunning) {
            return;
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestNeededPermissions();
            setVoiceState("Esperando permiso de micrófono");
            return;
        }

        int minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );

        if (minBuffer <= 0) {
            setVoiceState("AudioRecord no disponible");
            return;
        }

        int bufferSize = Math.max(minBuffer, 4096);

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release();
                audioRecord = null;
                setVoiceState("No pude abrir el micrófono");
                return;
            }

            voiceDetectorRunning = true;
            setVoiceState("Micrófono atento");

            if (eyesView != null) {
                eyesView.setMode(EyesView.MODE_IDLE);
            }

            audioRecord.startRecording();

            voiceDetectorThread = new Thread(() -> runVoiceDetector(bufferSize));
            voiceDetectorThread.setName("MariaVoiceDetector");
            voiceDetectorThread.start();

        } catch (Exception e) {
            voiceDetectorRunning = false;
            safeReleaseAudioRecord();
            setVoiceState("Error al abrir micrófono");
            scheduleVoiceDetectorRestart(1000);
        }
    }

    private void runVoiceDetector(int bufferSize) {
        short[] buffer = new short[Math.max(1024, bufferSize / 2)];
        int activeFrames = 0;

        while (voiceDetectorRunning && audioRecord != null) {
            int read;

            try {
                read = audioRecord.read(
                        buffer,
                        0,
                        buffer.length
                );
            } catch (Exception e) {
                break;
            }

            if (read <= 0) continue;

            double sum = 0.0;

            for (int i = 0; i < read; i++) {
                double sample = buffer[i];
                sum += sample * sample;
            }

            int rms = (int)Math.sqrt(sum / read);
            lastMicLevel = rms;

            if (rms >= voiceThreshold) {
                activeFrames++;
            } else {
                activeFrames = Math.max(0, activeFrames - 1);
            }

            // Unas decenas de milisegundos de voz real son suficientes.
            if (activeFrames >= 2) {
                voiceDetectorRunning = false;
                setVoiceState("Voz detectada");

                runOnUiThread(() -> {
                    if (eyesView != null) {
                        eyesView.setMode(EyesView.MODE_LISTENING);
                    }
                });

                break;
            }
        }

        safeStopAndReleaseAudioRecord();

        if (!activityActive ||
                settingsOpen ||
                mariaSpeaking ||
                !continuousListening) {
            return;
        }

        runOnUiThread(() -> {
            if (!recognitionRunning) {
                voiceHandler.postDelayed(
                        this::startRecognitionSession,
                        80
                );
            }
        });
    }

    private void stopVoiceDetector() {
        voiceDetectorRunning = false;

        try {
            if (audioRecord != null &&
                    audioRecord.getRecordingState()
                            == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.stop();
            }
        } catch (Exception ignored) {}

        safeReleaseAudioRecord();
    }

    private void safeStopAndReleaseAudioRecord() {
        try {
            if (audioRecord != null &&
                    audioRecord.getRecordingState()
                            == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.stop();
            }
        } catch (Exception ignored) {}

        safeReleaseAudioRecord();
    }

    private void safeReleaseAudioRecord() {
        try {
            if (audioRecord != null) {
                audioRecord.release();
            }
        } catch (Exception ignored) {}

        audioRecord = null;
        voiceDetectorRunning = false;
    }

    private void startRecognitionSession() {
        if (!continuousListening ||
                !activityActive ||
                mariaSpeaking ||
                settingsOpen ||
                recognitionRunning) {
            return;
        }

        if (recognizer == null) {
            setupSpeechRecognizer();

            if (recognizer == null) {
                setVoiceState("Reconocedor Android no disponible");
                return;
            }
        }

        Intent intent = new Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        );

        intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        );

        if (!useSystemLanguage) {
            intent.putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    "es-MX"
            );
            intent.putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                    "es-MX"
            );
        }

        intent.putExtra(
                RecognizerIntent.EXTRA_PREFER_OFFLINE,
                false
        );
        intent.putExtra(
                RecognizerIntent.EXTRA_MAX_RESULTS,
                5
        );
        intent.putExtra(
                RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                true
        );

        try {
            recognitionRunning = true;
            setVoiceState("Reconociendo");

            if (eyesView != null) {
                eyesView.setMode(EyesView.MODE_LISTENING);
            }

            recognizer.startListening(intent);

        } catch (Exception e) {
            recognitionRunning = false;
            setVoiceState("No pude iniciar el reconocedor");
            scheduleVoiceDetectorRestart(800);
        }
    }

    private String norm(String text) {
        String normalized = Normalizer.normalize(
                text.toLowerCase(Locale.ROOT),
                Normalizer.Form.NFD
        );

        return normalized.replaceAll("\\p{M}", "");
    }

    private boolean hasAny(String text, String... values) {
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

        // Alto tiene prioridad por seguridad.
        if (hasAny(
                s,
                "detente",
                "alto",
                "parate",
                "frena",
                "stop"
        )) {
            movement("S", "De acuerdo. Me detengo.");
            return;
        }

        if (hasAny(
                s,
                "avanza",
                "adelante",
                "ve hacia adelante"
        )) {
            movement("F", "Voy hacia adelante.");
            return;
        }

        if (hasAny(
                s,
                "retrocede",
                "atras",
                "ve hacia atras"
        )) {
            movement("B", "Voy hacia atrás.");
            return;
        }

        if (hasAny(
                s,
                "gira a la izquierda",
                "izquierda"
        )) {
            movement("L", "Girando a la izquierda.");
            return;
        }

        if (hasAny(
                s,
                "gira a la derecha",
                "derecha"
        )) {
            movement("R", "Girando a la derecha.");
            return;
        }

        if (s.contains("conecta") &&
                (s.contains("esp32") || s.contains("robot"))) {
            connectRobot();
            return;
        }

        if (s.contains("desconecta") &&
                (s.contains("esp32") || s.contains("robot"))) {
            disconnectRobot();
            say("Bluetooth desconectado.");
            return;
        }

        if (s.contains("velocidad")) {
            Integer spokenSpeed = extractNumber(s);

            if (spokenSpeed != null) {
                speed = Math.max(
                        80,
                        Math.min(255, spokenSpeed)
                );

                if (connected) {
                    sendRobot("V" + speed);
                }

                say("Velocidad ajustada a " + speed + ".");
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
                            new Locale("es", "MX")
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
                            new Locale("es", "MX")
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
                    "Me llamo María. Soy tu asistente virtual y controlo tu robot."
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
            say("Hola. Soy María. Te escucho.");
            return;
        }

        if (hasAny(
                s,
                "como estas",
                "como te sientes"
        )) {
            say("Estoy muy bien y lista para ayudarte.");
            return;
        }

        if (hasAny(
                s,
                "que puedes hacer",
                "que sabes hacer",
                "ayuda"
        )) {
            say(
                    "Puedo escucharte sin botones, controlar el robot, " +
                    "decirte la hora y abrir mi configuración por voz."
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
                    "¿Por qué el robot cruzó la calle? Porque alguien le programó una ruta."
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

        if (s.trim().length() >= 3) {
            say(
                    "Te escuché decir: " +
                    original +
                    ". Mi conversación libre se puede ampliar en una siguiente versión."
            );
        } else {
            scheduleVoiceDetectorRestart(300);
        }
    }

    private Integer extractNumber(String text) {
        String digits =
                text.replaceAll("[^0-9]", " ").trim();

        if (!digits.isEmpty()) {
            String[] parts = digits.split("\\s+");

            try {
                return Integer.parseInt(parts[0]);
            } catch (NumberFormatException ignored) {}
        }

        String[][] words = {
                {"doscientos cincuenta y cinco", "255"},
                {"doscientos cincuenta", "250"},
                {"doscientos veinte", "220"},
                {"doscientos", "200"},
                {"ciento ochenta", "180"},
                {"ciento cincuenta", "150"},
                {"ciento veinte", "120"},
                {"cien", "100"},
                {"noventa", "90"},
                {"ochenta", "80"}
        };

        for (String[] pair : words) {
            if (text.contains(pair[0])) {
                return Integer.parseInt(pair[1]);
            }
        }

        return null;
    }

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
                    "Autoriza dispositivos cercanos para conectar el ESP32."
            );
            return;
        }

        setVoiceState("Conectando ESP32");

        if (eyesView != null) {
            eyesView.setMode(EyesView.MODE_THINKING);
        }

        io.execute(() -> {
            try {
                BluetoothManager manager =
                        (BluetoothManager)getSystemService(
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
                    String name = device.getName();

                    if (name != null &&
                            name.equalsIgnoreCase(ROBOT_NAME)) {
                        target = device;
                        break;
                    }
                }

                if (target == null) {
                    throw new IOException(
                            "No está emparejado " + ROBOT_NAME
                    );
                }

                BluetoothSocket socket =
                        target.createRfcommSocketToServiceRecord(
                                SPP_UUID
                        );

                adapter.cancelDiscovery();
                socket.connect();

                btSocket = socket;
                btOut = socket.getOutputStream();
                connected = true;
                currentMotion = "S";

                writeRobotDirect("V" + speed);
                writeRobotDirect("S");

                runOnUiThread(() -> {
                    if (eyesView != null) {
                        eyesView.setConnected(true);
                    }

                    say("Conectada al ESP32.");
                });

            } catch (Exception e) {
                connected = false;

                runOnUiThread(() -> {
                    if (eyesView != null) {
                        eyesView.setConnected(false);
                        eyesView.setMode(
                                EyesView.MODE_IDLE
                        );
                    }

                    Toast.makeText(
                            this,
                            "No pude conectar MARIA_ROBOT. Empareja el ESP32 en Bluetooth.",
                            Toast.LENGTH_LONG
                    ).show();

                    scheduleVoiceDetectorRestart(700);
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

    private void sendRobot(String command) {
        if (!connected || btOut == null) return;

        io.execute(() -> {
            try {
                writeRobotDirect(command);
            } catch (IOException e) {
                connected = false;
                currentMotion = "S";

                runOnUiThread(() -> {
                    if (eyesView != null) {
                        eyesView.setConnected(false);
                        eyesView.setMode(
                                EyesView.MODE_IDLE
                        );
                    }

                    setVoiceState(
                            "Se perdió Bluetooth"
                    );

                    scheduleVoiceDetectorRestart(500);
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
        voiceHandler.removeCallbacks(
                restartVoiceDetector
        );

        stopVoiceDetector();

        if (recognizer != null &&
                recognitionRunning) {
            try {
                recognizer.cancel();
            } catch (Exception ignored) {}

            recognitionRunning = false;
        }

        if (eyesView != null) {
            eyesView.setMode(EyesView.MODE_SPEAKING);
        }

        setVoiceState("María hablando");

        if (tts != null) {
            int result = tts.speak(
                    text,
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "maria"
            );

            if (result == TextToSpeech.ERROR) {
                mariaSpeaking = false;

                if (eyesView != null) {
                    eyesView.setMode(EyesView.MODE_IDLE);
                }

                scheduleVoiceDetectorRestart(400);
            }
        } else {
            mariaSpeaking = false;

            if (eyesView != null) {
                eyesView.setMode(EyesView.MODE_IDLE);
            }

            scheduleVoiceDetectorRestart(400);
        }
    }

    @Override public void onInit(int statusCode) {
        if (statusCode == TextToSpeech.SUCCESS &&
                tts != null) {
            int result =
                    tts.setLanguage(
                            new Locale("es", "MX")
                    );

            tts.setSpeechRate(0.95f);

            if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(
                        new Locale("es", "ES")
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
                                setVoiceState("Micrófono atento");

                                if (eyesView != null) {
                                    eyesView.setMode(
                                            EyesView.MODE_IDLE
                                    );
                                }

                                scheduleVoiceDetectorRestart(250);
                            });
                        }

                        @Override public void onError(
                                String utteranceId
                        ) {
                            runOnUiThread(() -> {
                                mariaSpeaking = false;

                                if (eyesView != null) {
                                    eyesView.setMode(
                                            EyesView.MODE_IDLE
                                    );
                                }

                                scheduleVoiceDetectorRestart(350);
                            });
                        }
                    }
            );

            scheduleVoiceDetectorRestart(450);
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
                    ) == PackageManager.PERMISSION_GRANTED;

            Toast.makeText(
                    this,
                    micGranted
                    ? "Micrófono autorizado. María está atenta."
                    : "María necesita permiso de micrófono.",
                    Toast.LENGTH_SHORT
            ).show();

            if (micGranted) {
                scheduleVoiceDetectorRestart(350);
            }
        }
    }

    @Override protected void onDestroy() {
        activityActive = false;
        continuousListening = false;

        heartbeatHandler.removeCallbacks(heartbeat);
        voiceHandler.removeCallbacksAndMessages(null);

        stopVoiceDetector();
        disconnectRobot();

        if (recognizer != null) {
            try {
                recognizer.cancel();
            } catch (Exception ignored) {}

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

package com.kaua.ritmo;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Random;

public class BlockedOverlayService extends Service {
    public static final String EXTRA_DOMAIN = "domain";

    private static final String[] PHRASES = {
            "Você é mais forte do que qualquer vício.",
            "A vontade passa. Seu progresso fica.",
            "Você não chegou até aqui para voltar agora.",
            "Cada escolha certa fortalece quem você quer se tornar.",
            "Só porque você pode acessar, não significa que precisa.",
            "Mais uma escolha a favor de você. Continue.",
            "Um impulso dura pouco. A sua meta vale muito mais.",
            "Você já venceu a vontade antes. Pode vencer de novo.",
            "Seu futuro agradece a decisão que você toma agora.",
            "Não negocie com aquilo que você decidiu deixar para trás."
    };

    private WindowManager windowManager;
    private View overlayView;
    private TextView domainText;
    private TextView phraseText;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String domain = intent == null ? "site bloqueado" : intent.getStringExtra(EXTRA_DOMAIN);
        if (domain == null || domain.trim().isEmpty()) domain = "site bloqueado";
        showOverlay(domain);
        return START_NOT_STICKY;
    }

    private void showOverlay(String domain) {
        if (overlayView != null) {
            if (domainText != null) domainText.setText(domain);
            if (phraseText != null) phraseText.setText(randomPhrase());
            return;
        }

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.argb(225, 10, 18, 32));
        root.setPadding(dp(22), dp(28), dp(22), dp(28));
        root.setClickable(true);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(24), dp(26), dp(24), dp(24));
        card.setBackground(makeRoundedBackground(Color.WHITE, dp(28)));

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.gravity = Gravity.CENTER;
        cardParams.leftMargin = dp(4);
        cardParams.rightMargin = dp(4);
        root.addView(card, cardParams);

        TextView shield = text("🛡️", 42, Color.rgb(31, 41, 55), true);
        shield.setGravity(Gravity.CENTER);
        card.addView(shield, matchWrap(0, 0, 0, 8));

        TextView title = text("Acesso bloqueado pelo Ritmo", 23, Color.rgb(17, 24, 39), true);
        title.setGravity(Gravity.CENTER);
        card.addView(title, matchWrap(0, 0, 0, 8));

        TextView subtitle = text("Você tentou acessar", 14, Color.rgb(107, 114, 128), false);
        subtitle.setGravity(Gravity.CENTER);
        card.addView(subtitle, matchWrap(0, 0, 0, 4));

        domainText = text(domain, 17, Color.rgb(37, 99, 235), true);
        domainText.setGravity(Gravity.CENTER);
        card.addView(domainText, matchWrap(0, 0, 0, 22));

        phraseText = text(randomPhrase(), 20, Color.rgb(31, 41, 55), true);
        phraseText.setGravity(Gravity.CENTER);
        phraseText.setLineSpacing(0, 1.12f);
        card.addView(phraseText, matchWrap(0, 0, 0, 12));

        TextView helper = text(
                "O Ritmo bloqueou este acesso para proteger a meta que você escolheu.",
                14,
                Color.rgb(107, 114, 128),
                false
        );
        helper.setGravity(Gravity.CENTER);
        helper.setLineSpacing(0, 1.1f);
        card.addView(helper, matchWrap(0, 0, 0, 24));

        Button openRitmo = new Button(this);
        openRitmo.setText("Voltar ao Ritmo");
        openRitmo.setTextSize(16);
        openRitmo.setTextColor(Color.WHITE);
        openRitmo.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        openRitmo.setAllCaps(false);
        openRitmo.setBackground(makeRoundedBackground(Color.rgb(37, 99, 235), dp(18)));
        openRitmo.setOnClickListener(v -> {
            Intent open = new Intent(this, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(open);
            removeOverlay();
            stopSelf();
        });
        card.addView(openRitmo, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)
        ));

        Button close = new Button(this);
        close.setText("Fechar aviso");
        close.setTextSize(15);
        close.setTextColor(Color.rgb(75, 85, 99));
        close.setAllCaps(false);
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setOnClickListener(v -> {
            removeOverlay();
            stopSelf();
        });
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
        );
        closeParams.topMargin = dp(8);
        card.addView(close, closeParams);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;

        overlayView = root;
        try {
            windowManager.addView(overlayView, params);
        } catch (Exception e) {
            overlayView = null;
            stopSelf();
        }
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return tv;
    }

    private LinearLayout.LayoutParams matchWrap(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private android.graphics.drawable.GradientDrawable makeRoundedBackground(int color, int radius) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private String randomPhrase() {
        return PHRASES[new Random().nextInt(PHRASES.length)];
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void removeOverlay() {
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception ignored) {
            }
        }
        overlayView = null;
        domainText = null;
        phraseText = null;
    }

    @Override
    public void onDestroy() {
        removeOverlay();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

package com.example.cloudviewer;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private TextView cityName, temperature, cloudCover, humidity, lastUpdate, weatherIcon, statusHint, errorText;
    private Button refreshBtn, retryBtn;
    private LinearLayout errorBanner;
    private LocationManager locationManager;
    private ExecutorService executorService;
    private Handler mainHandler;

    private Handler refreshHandler = new Handler(Looper.getMainLooper());
    private Runnable refreshRunnable;
    private static final long REFRESH_INTERVAL = 30 * 60 * 1000;

    private static final String WEATHER_API_URL = "https://api.open-meteo.com/v1/forecast";
    private static final String CLOUD_MAP_URL = "https://embed.windy.com/embed2.html";

    private double currentLat, currentLon;
    private boolean hasLocation = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        initWebView();
        initLocationServices();
        setupAutoRefresh();
    }

    private void initViews() {
        webView = findViewById(R.id.webview);
        cityName = findViewById(R.id.city_name);
        temperature = findViewById(R.id.temperature);
        cloudCover = findViewById(R.id.cloud_cover);
        humidity = findViewById(R.id.humidity);
        lastUpdate = findViewById(R.id.last_update);
        weatherIcon = findViewById(R.id.weather_icon);
        statusHint = findViewById(R.id.status_hint);
        errorText = findViewById(R.id.error_text);
        refreshBtn = findViewById(R.id.refresh_btn);
        retryBtn = findViewById(R.id.retry_btn);
        errorBanner = findViewById(R.id.error_banner);

        executorService = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        refreshBtn.setOnClickListener(v -> {
            Toast.makeText(this, "正在刷新...", Toast.LENGTH_SHORT).show();
            hideError();
            requestLocationUpdate();
        });

        retryBtn.setOnClickListener(v -> {
            hideError();
            requestLocationUpdate();
        });
    }

    private void initWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(android.webkit.WebView view, int errorCode,
                                        String description, String failingUrl) {
                showError("云图加载失败: " + description);
            }
        });
        webView.loadUrl(CLOUD_MAP_URL);
    }

    private void initLocationServices() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
            return;
        }
        requestLocationUpdate();
    }

    private void requestLocationUpdate() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            showError("请授予位置权限");
            return;
        }

        statusHint.setText("正在获取位置...");

        Location location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (location == null) {
            location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        }

        if (location != null) {
            onLocationObtained(location);
        } else {
            cityName.setText("定位失败");
            temperature.setText("--°C");
            showError("无法获取位置，请检查GPS和网络权限");
        }
    }

    private void onLocationObtained(Location location) {
        currentLat = location.getLatitude();
        currentLon = location.getLongitude();
        hasLocation = true;
        hideError();

        executorService.execute(() -> {
            try {
                String city = getCityFromLocation(currentLat, currentLon);
                JSONObject weatherData = getWeatherData(currentLat, currentLon);
                mainHandler.post(() -> updateUI(city, weatherData));
                updateCloudMap(currentLat, currentLon);
            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(() -> showError("天气数据加载失败，请检查网络连接"));
            }
        });
    }

    private String getCityFromLocation(double lat, double lon) throws Exception {
        Geocoder geocoder = new Geocoder(this, Locale.getDefault());
        List<Address> addresses = geocoder.getFromLocation(lat, lon, 1);
        if (addresses != null && !addresses.isEmpty()) {
            Address addr = addresses.get(0);
            String city = addr.getLocality();
            if (city == null) city = addr.getSubAdminArea();
            if (city == null) city = addr.getAdminArea();
            return city != null ? city : "未知城市";
        }
        return "未知城市";
    }

    private JSONObject getWeatherData(double lat, double lon) throws Exception {
        String urlString = WEATHER_API_URL +
                "?latitude=" + lat +
                "&longitude=" + lon +
                "&current=temperature_2m,relative_humidity_2m,cloud_cover,weather_code" +
                "&timezone=auto";

        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);

        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        connection.disconnect();

        return new JSONObject(response.toString()).getJSONObject("current");
    }

    private void updateUI(String city, JSONObject weatherData) {
        try {
            cityName.setText(city != null ? city : "未知位置");

            double temp = weatherData.getDouble("temperature_2m");
            temperature.setText(String.format(Locale.getDefault(), "%.0f°C", temp));

            int cloud = weatherData.getInt("cloud_cover");
            cloudCover.setText("云量: " + cloud + "%");

            // 湿度
            int hum = weatherData.getInt("relative_humidity_2m");
            humidity.setText("湿度: " + hum + "%");

            // 天气代码 → 图标
            int weatherCode = weatherData.optInt("weather_code", -1);
            weatherIcon.setText(getWeatherEmoji(weatherCode, cloud));

            String timeStamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            lastUpdate.setText("更新于 " + timeStamp);

            statusHint.setText("✓ 天气数据已更新");
            hideError();
        } catch (Exception e) {
            e.printStackTrace();
            statusHint.setText("数据解析异常");
        }
    }

    /**
     * open-meteo weather_code → emoji icon
     * 0=晴 1-3=少云 4+45+48=霾 51-57=小雨 61-67=雨 71-77=雪 80-82=阵雨 95-99=雷暴
     */
    private String getWeatherEmoji(int code, int cloudCover) {
        if (code == 0) {
            return cloudCover < 10 ? "☀️" : "🌤️";
        } else if (code <= 3) {
            return cloudCover < 50 ? "⛅" : "🌥️";
        } else if (code == 45 || code == 48) {
            return "🌫️";
        } else if (code >= 51 && code <= 57) {
            return "🌦️";
        } else if (code >= 61 && code <= 67) {
            return "🌧️";
        } else if (code >= 71 && code <= 77) {
            return "🌨️";
        } else if (code >= 80 && code <= 82) {
            return "⛈️";
        } else if (code >= 95 && code <= 99) {
            return "⛈️";
        }
        // fallback by cloud cover
        if (cloudCover < 30) return "☀️";
        if (cloudCover < 70) return "⛅";
        return "☁️";
    }

    private void updateCloudMap(double lat, double lon) {
        String locationUrl = CLOUD_MAP_URL +
                "?lat=" + lat +
                "&lon=" + lon +
                "&zoom=8&overlay=clouds";
        webView.loadUrl(locationUrl);
    }

    private void showError(String msg) {
        errorText.setText(msg);
        errorBanner.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        errorBanner.setVisibility(View.GONE);
    }

    // =================== 自动刷新 ===================

    private void setupAutoRefresh() {
        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                if (hasLocation) {
                    executorService.execute(() -> {
                        try {
                            JSONObject weatherData = getWeatherData(currentLat, currentLon);
                            mainHandler.post(() -> updateUI(cityName.getText().toString(), weatherData));
                            updateCloudMap(currentLat, currentLon);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                } else {
                    requestLocationUpdate();
                }
                refreshHandler.postDelayed(this, REFRESH_INTERVAL);
            }
        };
    }

    private void startAutoRefresh() {
        stopAutoRefresh();
        refreshHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL);
    }

    private void stopAutoRefresh() {
        refreshHandler.removeCallbacks(refreshRunnable);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        startAutoRefresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
        stopAutoRefresh();
    }

    @Override
    protected void onDestroy() {
        stopAutoRefresh();
        executorService.shutdown();
        if (webView != null) {
            webView.loadDataWithBaseURL(null, "", "text/html", "utf-8", null);
            webView.clearHistory();
            webView.clearCache(true);
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 && grantResults.length > 0 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            requestLocationUpdate();
        } else {
            showError("未获得位置权限，无法获取天气");
        }
    }
}

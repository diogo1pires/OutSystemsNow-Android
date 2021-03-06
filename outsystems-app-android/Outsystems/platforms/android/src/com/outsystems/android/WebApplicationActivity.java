/*
 * OutSystems Project
 * 
 * Copyright (C) 2014 OutSystems.
 * 
 * This software is proprietary.
 */
package com.outsystems.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.StateSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.webkit.DownloadListener;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import com.outsystems.android.core.CordovaLoaderWebClient;
import com.outsystems.android.core.CordovaWebViewActivity;
import com.outsystems.android.core.CordovaWebViewChromeClient;
import com.outsystems.android.core.DatabaseHandler;
import com.outsystems.android.core.EventLogger;
import com.outsystems.android.core.WebServicesClient;
import com.outsystems.android.helpers.ApplicationSettingsController;
import com.outsystems.android.helpers.DeepLinkController;
import com.outsystems.android.helpers.HubManagerHelper;
import com.outsystems.android.helpers.OfflineSupport;
import com.outsystems.android.mobileect.MobileECTController;
import com.outsystems.android.mobileect.interfaces.OSECTContainerListener;
import com.outsystems.android.model.AppSettings;
import com.outsystems.android.model.Application;
import com.outsystems.android.model.MobileECT;
import com.outsystems.android.widgets.CustomFontTextView;

import org.apache.cordova.CordovaInterface;
import org.apache.cordova.engine.SystemWebView;
import org.apache.cordova.engine.SystemWebViewEngine;
import org.apache.http.cookie.Cookie;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.List;

/**
 * Class description.
 *
 * @author <a href="mailto:vmfo@xpand-it.com">vmfo</a>
 * @version $Revision: 666 $
 *
 */
public class WebApplicationActivity extends CordovaWebViewActivity implements OSECTContainerListener {

    public static String KEY_APPLICATION = "key_application";
    public static String KEY_SINGLE_APPLICATION ="single_application";
    private static String OPEN_URL_EXTERNAL_BROWSER_PREFIX = "external:";

    private ImageButton buttonForth;
    protected ProgressDialog spinnerDialog = null;
    private ImageView imageView;

    private int flagNumberLoadings = 0;

    private MobileECTController mobileECTController;

    // Offline support
    private View networkErrorView;

    private boolean webViewLoadingFailed = false;
    private boolean spinnerEnabled = false;

    // Mobile Improvements
    private boolean applicationHasPreloader = false;
    private ProgressBar progressBar;

    private String failingUrl;

    public OnClickListener onClickListenerBack = new OnClickListener() {

        @Override
        public void onClick(View v) {
            if (cordovaWebView.canGoBack()) {
                LinearLayout viewLoading = (LinearLayout) findViewById(R.id.view_loading);
                if (viewLoading.getVisibility() != View.VISIBLE) {
                    startLoadingAnimation();
                }
                cordovaWebView.goBack();
                enableDisableButtonForth();
            } else {
                finish();
            }
        }
    };

    private OnClickListener onClickListenerForth = new OnClickListener() {

        @Override
        public void onClick(View v) {

            if (cordovaWebView.canGoForward()) {
                startLoadingAnimation();
                cordovaWebView.goForward();
                enableDisableButtonForth();
            }

        }
    };

    private OnClickListener onClickListenerApplication = new OnClickListener() {

        @Override
        public void onClick(View v) {
            finish();
        }
    };


    private OnClickListener onClickListenerOpenECT = new OnClickListener() {

        @Override
        public void onClick(View v) {
            mobileECTController.openECTView();
        }
    };


    private long LOADING_TIMEOUT = 350;

    @SuppressWarnings("deprecation")
    @SuppressLint("NewApi")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_web_application);

        // Check if deep link has valid settings
        if(DeepLinkController.getInstance().hasValidSettings()){
            //Reached the last activity... Time to invalidate it!
            DeepLinkController.getInstance().invalidate();
        }

        imageView = (ImageView) this.findViewById(R.id.image_view);

        cordovaWebView = (SystemWebView) this.findViewById(R.id.mainView);

        init();

        SystemWebViewEngine webViewEngine = (SystemWebViewEngine)appView.getEngine();

        Application application = null;
        boolean singleApp = false;
        Bundle bundle = getIntent().getExtras();
        if (bundle != null) {
            application = (Application) bundle.get("key_application");

            singleApp  =  bundle.get(KEY_SINGLE_APPLICATION) != null && (Boolean) bundle.get(KEY_SINGLE_APPLICATION);
        }

        // Local Url to load application
        String url = "";
        if (application != null) {
            if (HubManagerHelper.getInstance().getApplicationHosted() == null) {
                ApplicationOutsystems app = (ApplicationOutsystems) getApplication();
                app.registerDefaultHubApplication();
            }
            url = String.format(WebServicesClient.URL_WEB_APPLICATION, HubManagerHelper.getInstance()
                    .getApplicationHosted(), application.getPath());
        }

        cordovaWebView.setWebViewClient(new CordovaCustomWebClient(this.cordovaInterface,webViewEngine));
        CordovaCustomChromeClient chromeClient = new CordovaCustomChromeClient(webViewEngine, this.cordovaInterface);
        cordovaWebView.setWebChromeClient(chromeClient);

        cordovaWebView.getSettings().setJavaScriptEnabled(true);
        cordovaWebView.getSettings().setLightTouchEnabled(true);

        // Listener to Download Web File with Native Component - Download Manager
        cordovaWebView.setDownloadListener(new DownloadListener() {
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype,
                    long contentLength) {
                downloadAndOpenFile(WebApplicationActivity.this, url);
            }
        });


        // Synchronize WebView cookies with Login Request cookies
        CookieSyncManager.createInstance(getApplicationContext());
        // android.webkit.CookieManager.getInstance().removeAllCookie();

        List<String> cookies = WebServicesClient.getInstance().getLoginCookies();
        if (cookies != null && !cookies.isEmpty()){
            for(String cookieString : cookies){
                android.webkit.CookieManager.getInstance().setCookie(HubManagerHelper.getInstance().getApplicationHosted(), cookieString);
                CookieSyncManager.getInstance().sync();
            }
        }
        else{
            // For Demo Applications only
            List<Cookie> httpCookies= WebServicesClient.getInstance().getHttpCookies();
            Cookie sessionInfo;
            if (httpCookies != null && !httpCookies.isEmpty()){
                for(Cookie cookie : httpCookies){
                    sessionInfo = cookie;
                    String cookieString = sessionInfo.getName() + "=" + sessionInfo.getValue() + "; domain=" + sessionInfo.getDomain();
                    android.webkit.CookieManager.getInstance().setCookie(HubManagerHelper.getInstance().getApplicationHosted(), cookieString);
                    EventLogger.logMessage(getClass(), "HttpCookie: "+cookieString);
                }
            }

        }

        // Set in the user agent OutSystemsApp
        String ua = cordovaWebView.getSettings().getUserAgentString();
        String appVersion = getAppVersion();
        String newUA = ua.concat(" OutSystemsApp v." + appVersion);
        cordovaWebView.getSettings().setUserAgentString(newUA);

        // Offline Support
        cordovaWebView.getSettings().setAppCacheMaxSize( 50 * 1024 * 1024 ); // 50MB
        cordovaWebView.getSettings().setAppCachePath( getApplicationContext().getCacheDir().getAbsolutePath() );
        cordovaWebView.getSettings().setAllowFileAccess( true );
        cordovaWebView.getSettings().setAppCacheEnabled( true );
        cordovaWebView.getSettings().setJavaScriptEnabled( true );
        cordovaWebView.getSettings().setCacheMode( WebSettings.LOAD_NO_CACHE );

        // Allow remote debugging
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            cordovaWebView.setWebContentsDebuggingEnabled(true);
        }

        ApplicationOutsystems app = (ApplicationOutsystems)getApplication();

        if ( !app.isNetworkAvailable() ) { // loading offline
            cordovaWebView.getSettings().setCacheMode( WebSettings.LOAD_CACHE_ONLY );
        }

        OfflineSupport.getInstance(getApplicationContext()).clearCacheIfNeeded(cordovaWebView);

        this.networkErrorView = findViewById(R.id.networkErrorInclude);

        if(networkErrorView != null){
            networkErrorView.setVisibility(View.INVISIBLE);

            View retryButton = networkErrorView.findViewById(R.id.networkErrorButtonRetry);
            View appsListLink = networkErrorView.findViewById(R.id.networkErrorAppsListLink);

            retryButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View view) {
                    retryWebViewAction();
                }
            });

            appsListLink.setOnClickListener(onClickListenerApplication);
        }


        // Mobile ECT Feature

        View containerView = findViewById(R.id.ectViewGroup);
        View mainView = findViewById(R.id.mainViewGroup);
        DatabaseHandler database = new DatabaseHandler(getApplicationContext());
        MobileECT mobileECT = database.getMobileECT();
        database.close();

        boolean skipHelper = mobileECT != null && !mobileECT.isFirstLoad();

        mobileECTController = new MobileECTController(this,
                mainView,
                containerView,
                this.cordovaWebView,
                HubManagerHelper.getInstance().getApplicationHosted(),
                skipHelper);

        containerView.setVisibility(View.GONE);

        // Hide ECT Button
        ImageButton buttonECT = (ImageButton) findViewById(R.id.button_ect);
        if(buttonECT != null) {
            buttonECT.setOnClickListener(this.onClickListenerOpenECT);
            buttonECT.setVisibility(View.GONE);
        }

        // Mobile Improvements
        this.applicationHasPreloader = application != null && application.hasPreloader();
        this.progressBar = (ProgressBar)this.findViewById(R.id.progressBar);
        this.progressBar.setProgress(0);
        this.progressBar.setSecondaryProgress(0);
        chromeClient.setProgressBar(this.progressBar);

        if(applicationHasPreloader){
            ((LinearLayout) findViewById(R.id.view_loading)).setVisibility(View.GONE);
        }

        // Load Application

        if (savedInstanceState == null) {
            // Offline Support: app's url must have / at the end of url
            if(!url.endsWith("/") && url.indexOf("?") < 0 && !(url.endsWith(".aspx") || url.endsWith(".jsf"))){
                url = url + "/";
            }
            this.loadUrl(url);
        } else {
            ((LinearLayout) findViewById(R.id.view_loading)).setVisibility(View.GONE);
        }

        // Customization Toolbar
        // Get Views from Xml Layout
        ImageButton buttonApplications = (ImageButton) findViewById(R.id.button_applications);
        ImageButton buttonBack = (ImageButton) findViewById(R.id.button_back);
        buttonForth = (ImageButton) findViewById(R.id.button_forth);

        // Actions onClick
        buttonApplications.setOnClickListener(onClickListenerApplication);
        buttonBack.setOnClickListener(onClickListenerBack);
        buttonForth.setOnClickListener(onClickListenerForth);

        // Background with differents states
        int sdk = android.os.Build.VERSION.SDK_INT;
        if (sdk < android.os.Build.VERSION_CODES.JELLY_BEAN) {
            buttonApplications.setImageDrawable(createSelectorIconApplications(getResources().getDrawable(
                    R.drawable.icon_app_list)));
            buttonBack.setImageDrawable(createSelectorIconApplications(getResources().getDrawable(
                    R.drawable.icon_chevron_left)));
            buttonForth.setImageDrawable(createSelectorIconApplications(getResources().getDrawable(
                    R.drawable.icon_chevron_right)));
        } else {
            buttonApplications.setImageDrawable(createSelectorIconApplications(getResources().getDrawable(
                    R.drawable.icon_app_list)));
            buttonBack.setImageDrawable(createSelectorIconApplications(getResources().getDrawable(
                    R.drawable.icon_chevron_left)));
            buttonForth.setImageDrawable(createSelectorIconApplications(getResources().getDrawable(
                    R.drawable.icon_chevron_right)));

        }

        // Check if it's a single application
        if(singleApp){
            buttonApplications.setVisibility(View.INVISIBLE);
            buttonApplications.setOnClickListener(null);

            if(this.networkErrorView != null) {
                View backToAppList = this.networkErrorView.findViewById(R.id.networkErrorAppsListLink);
                backToAppList.setVisibility(View.GONE);
            }

        }


        // Application Settings

        boolean hasValidSettings = ApplicationSettingsController.getInstance().hasValidSettings();

        if(hasValidSettings){

            boolean hideNavigationBar = ApplicationSettingsController.getInstance().hideNavigationBar();
            if(hideNavigationBar){

                View navigationBar = findViewById(R.id.toolbar);
                if(navigationBar != null)
                    navigationBar.setVisibility(View.GONE);

                View divider = findViewById(R.id.divider_toolbar);
                if(divider != null)
                    divider.setVisibility(View.GONE);

            }

            AppSettings appSettings =  ApplicationSettingsController.getInstance().getSettings();


            boolean customBgColor = appSettings.getBackgroundColor() != null && !appSettings.getBackgroundColor().isEmpty();
            if(customBgColor){
                this.networkErrorView.setBackgroundColor(Color.parseColor(appSettings.getBackgroundColor()));
            }

            boolean customFgColor = appSettings.getForegroundColor() != null && !appSettings.getForegroundColor().isEmpty();
            if(customFgColor){
                int newColor = Color.parseColor(appSettings.getForegroundColor());
                PorterDuff.Mode mMode = PorterDuff.Mode.SRC_ATOP;

                CustomFontTextView networkErrorHeader = (CustomFontTextView)findViewById(R.id.networkErrorHeader);
                networkErrorHeader.setTextColor(newColor);

                CustomFontTextView networkErrorMessage = (CustomFontTextView)findViewById(R.id.networkErrorMessage);
                networkErrorMessage.setTextColor(newColor);

                CustomFontTextView networkErrorAppsListLink = (CustomFontTextView)findViewById(R.id.networkErrorAppsListLink);
                networkErrorAppsListLink.setTextColor(newColor);

                ImageView networkErrorImage = (ImageView)findViewById(R.id.imgNetworkError);
                Drawable drawable = networkErrorImage.getDrawable();
                drawable.setColorFilter(newColor, mMode);

                Button buttonRetry = (Button)findViewById(R.id.networkErrorButtonRetry);
                drawable = buttonRetry.getBackground();
                drawable.setColorFilter(newColor, PorterDuff.Mode.SRC_ATOP);
                buttonRetry.setTextColor(newColor);

                ProgressBar networkErrorProgressBar = (ProgressBar)findViewById(R.id.networkErrorProgressBar);
                drawable = networkErrorProgressBar.getIndeterminateDrawable();
                drawable.setColorFilter(newColor, PorterDuff.Mode.SRC_ATOP);
            }

            boolean customTintColor = appSettings.getTintColor() != null && !appSettings.getTintColor().isEmpty();

            if(customTintColor){
                int newColor = Color.parseColor(appSettings.getTintColor());
                PorterDuff.Mode mMode = PorterDuff.Mode.SRC_ATOP;

                // Back Button
                Drawable drawable = buttonBack.getDrawable();
                drawable.setColorFilter(newColor,mMode);

                // Forward Button
                drawable = buttonForth.getDrawable();
                drawable.setColorFilter(newColor,mMode);

                // Application List Button
                drawable = buttonApplications.getDrawable();
                drawable.setColorFilter(newColor, mMode);

                // ECT Button
                drawable = buttonECT.getDrawable();
                drawable.setColorFilter(newColor, mMode);

            }


        }


    }


    /*
     * (non-Javadoc)
     * 
     * @see android.app.Activity#onRestoreInstanceState(android.os.Bundle)
     */
    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);

        flagNumberLoadings++;
        imageView.setVisibility(View.VISIBLE);
        spinnerStart();
        try {
            if(savedInstanceState != null) {
                cordovaInterface.restoreInstanceState(savedInstanceState);
                cordovaWebView.restoreState(savedInstanceState);
            }
        }catch(Exception e){
            EventLogger.logError(this.getClass().toString(),e);
        }
    }



    @Override
    public void onResume() {
        super.onResume();
        stopLoadingAnimation();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                if (cordovaWebView.canGoBack()) {
                    cordovaWebView.goBack();
                    enableDisableButtonForth();
                } else {
                    finish();
                }
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }


    /**
     * Get the Android activity.
     *
     * @return
     */
    public Activity getActivity() {
        return this;
    }


    @SuppressWarnings("deprecation")
    @SuppressLint("NewApi")
    private void enableDisableButtonForth() {
        if (cordovaWebView.canGoForward()) {
            int sdk = android.os.Build.VERSION.SDK_INT;
            if (sdk < android.os.Build.VERSION_CODES.JELLY_BEAN) {
                buttonForth.setImageDrawable(createSelectorIconApplications(getResources().getDrawable(
                        R.drawable.icon_chevron_right)));
            } else {
                buttonForth.setImageDrawable(createSelectorIconApplications(getResources().getDrawable(
                        R.drawable.icon_chevron_right)));
            }
        } else {
            Drawable iconForth = getResources().getDrawable(R.drawable.icon_chevron_right_inactive);

            BitmapDrawable disabled = getDisableButton(iconForth);
            int sdk = android.os.Build.VERSION.SDK_INT;
            if (sdk < android.os.Build.VERSION_CODES.JELLY_BEAN) {
                buttonForth.setImageDrawable(disabled);
            } else {
                buttonForth.setImageDrawable(disabled);
            }
        }
    }

    /**
     * Creates the selector icon applications.
     *
     * @param icon the icon
     * @return the drawable
     */
    private Drawable createSelectorIconApplications(Drawable icon) {
        StateListDrawable drawable = new StateListDrawable();

        BitmapDrawable disabled = getDisableButton(icon);

        drawable.addState(new int[] { -android.R.attr.state_pressed }, icon);
        drawable.addState(new int[]{-android.R.attr.state_enabled}, icon);
        drawable.addState(StateSet.WILD_CARD, disabled);

        return drawable;
    }

    /**
     * Gets the disable button.
     *
     * @param icon the icon
     * @return the disable button
     */
    private BitmapDrawable getDisableButton(Drawable icon) {
        Bitmap enabledBitmap = ((BitmapDrawable) icon).getBitmap();

        // Setting alpha directly just didn't work, so we draw a new bitmap!
        Bitmap disabledBitmap = Bitmap.createBitmap(icon.getIntrinsicWidth(), icon.getIntrinsicHeight(),
                android.graphics.Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(disabledBitmap);

        Paint paint = new Paint();
        paint.setAlpha(90);
        canvas.drawBitmap(enabledBitmap, 0, 0, paint);

        BitmapDrawable disabled = new BitmapDrawable(getResources(), disabledBitmap);

        return disabled;
    }

    /**
     *  Mobile ECT Container
     */
    public void showMobileECTButton(final boolean show){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ImageButton buttonECT = (ImageButton) findViewById(R.id.button_ect);
                if (buttonECT != null) {
                    ApplicationOutsystems app = (ApplicationOutsystems) getApplication();
                    if (buttonECT != null) {
                        boolean showECT = app.isNetworkAvailable() && show;
                        buttonECT.setVisibility(showECT ? View.VISIBLE : View.GONE);

                        // Application Settings
                        boolean hasValidSettings = ApplicationSettingsController.getInstance().hasValidSettings();

                        if(hasValidSettings) {

                            AppSettings appSettings = ApplicationSettingsController.getInstance().getSettings();
                            boolean customTintColor = appSettings.getTintColor() != null && !appSettings.getTintColor().isEmpty();

                            if (customTintColor) {
                                int newColor = Color.parseColor(appSettings.getTintColor());
                                PorterDuff.Mode mMode = PorterDuff.Mode.SRC_ATOP;

                                Drawable drawable = buttonECT.getDrawable();
                                drawable.setColorFilter(newColor, mMode);
                            }
                        }

                        findViewById(R.id.toolbar).invalidate();
                    }
                }
            }
        });

    }

    @Override
    public void onSendFeedbackClickListener() {
        mobileECTController.sendFeedback();
    }

    @Override
    public void onCloseECTClickListener() {
        mobileECTController.closeECTView();
    }

    @Override
    public void onCloseECTHelperClickListener() {

        DatabaseHandler database = new DatabaseHandler(getApplicationContext());
        database.addMobileECT(false);
        database.close();
        mobileECTController.setSkipECTHelper(true);
    }

    @Override
    public void onShowECTFeatureListener(boolean show) {
        this.showMobileECTButton(show);
    }


    /**
     * The Class CordovaCustomWebClient.
     */
    public class CordovaCustomWebClient extends CordovaLoaderWebClient {

        public CordovaCustomWebClient(CordovaInterface cordova, SystemWebViewEngine engine) {
            super(cordova, engine);
        }

        @SuppressLint("DefaultLocale")
        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            EventLogger.logMessage(getClass(), "--------------- shouldOverrideUrlLoading ---------------");

			if(url.equals("about:blank"))
            	return super.shouldOverrideUrlLoading(view, url);

            if(url.startsWith(OPEN_URL_EXTERNAL_BROWSER_PREFIX)){
                String urlString = url.substring(OPEN_URL_EXTERNAL_BROWSER_PREFIX.length());
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(urlString));
                startActivity(browserIntent);
                return true;
            }
            EventLogger.logInfoMessage(this.getClass(), "PRELOADER: shouldOverrideUrlLoading - hasPreloader:" + applicationHasPreloader);
            if (!applicationHasPreloader) {
                if(imageView == null)
                    imageView = (ImageView) findViewById(R.id.image_view);

                if (imageView.getVisibility() != View.VISIBLE) {


                    final Handler handler = new Handler();
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {

                            startLoadingAnimation();
                        }
                    },LOADING_TIMEOUT);

                    if(progressBar != null) {
                        progressBar.setProgress(5);
                        progressBar.setVisibility(View.VISIBLE);
                    }
                }

            }

            return super.shouldOverrideUrlLoading(view, url);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            EventLogger.logMessage(getClass(), "________________ ONPAGEFINISHED _________________");
            enableDisableButtonForth();

            // Get Mobile ECT Api Info
            mobileECTController.getECTAPIInfo();

            if(!webViewLoadingFailed)
                cordovaWebView.setVisibility(View.VISIBLE);

            EventLogger.logInfoMessage(this.getClass(),"PRELOADER: onPageFinished - hasPreloader:"+applicationHasPreloader);
            if (applicationHasPreloader){
                EventLogger.logInfoMessage(this.getClass(),"PRELOADER: CurrentURL: "+url);
                applicationHasPreloader = url.contains("preloader.html");
            }
            else{
                stopLoadingAnimation();
            }

            showNetworkErrorView(webViewLoadingFailed);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            EventLogger.logMessage(getClass(), "________________ ONPAGESTARTED _________________");

            webViewLoadingFailed = false;

            EventLogger.logInfoMessage(this.getClass(),"PRELOADER: onPageStarted - hasPreloader:"+applicationHasPreloader);
            if (!applicationHasPreloader) {
                if(imageView == null)
                    imageView = (ImageView) findViewById(R.id.image_view);

                if (imageView.getVisibility() != View.VISIBLE) {
                    final Handler handler = new Handler();
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {

                            startLoadingAnimation();
                        }
                    }, LOADING_TIMEOUT);

                    if(progressBar != null) {
                        progressBar.setProgress(5);
                        progressBar.setVisibility(View.VISIBLE);
                    }
                }
            }
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            EventLogger.logMessage(getClass(), "onReceivedSslError: "+error.toString());
            failingUrl = view.getUrl();
            List<String> trustedHosts = WebServicesClient.getInstance().getTrustedHosts();
            String host = HubManagerHelper.getInstance().getApplicationHosted();

            if (trustedHosts != null && host != null) {
                for (String trustedHost : trustedHosts) {
                    if (host.contains(trustedHost)) {
                        handler.proceed();
                        return;
                    }
                }
            }

            if(networkErrorView == null || networkErrorView.getVisibility() != View.VISIBLE ){
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Resources res = getResources();
                        String message = String.format(res.getString(R.string.invalid_ssl_message), HubManagerHelper.getInstance().getApplicationHosted());

                        new AlertDialog.Builder(WebApplicationActivity.this)
                                .setTitle(R.string.invalid_ssl_title)
                                .setMessage(message)
                                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int which) {
                                        WebServicesClient.getInstance().addTrustedHostname(HubManagerHelper.getInstance().getApplicationHosted());
                                        EventLogger.logError(this.getClass(),"URL: "+ failingUrl);
                                        cordovaWebView.loadUrl(failingUrl);
                                    }
                                })
                                .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int which) {
                                        // do nothing
                                        webViewLoadingFailed = true;
                                        showNetworkErrorView(true);
                                    }
                                })
                                .setIcon(android.R.drawable.ic_dialog_alert)
                                .show();
                    }
                });
            }
            else{
                webViewLoadingFailed = true;
                showNetworkErrorRetryLoading(true);
            }

            handler.cancel();
        }

        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            super.onReceivedError(view, errorCode, description, failingUrl);
            EventLogger.logMessage(getClass(), "________________ ONRECEIVEDERROR _________________");
            EventLogger.logMessage(getClass(), "ErrorCode: "+errorCode+" - Description: "+description);

            cordovaWebView.setVisibility(View.INVISIBLE);
            webViewLoadingFailed = true;
            stopLoadingAnimation();

            EventLogger.logMessage(getClass(), "onReceivedError - webViewLoadingFailed: "+webViewLoadingFailed);
        }

    }


    @SuppressWarnings("deprecation")
    private void startLoadingAnimation() {
        int currentOrientation = this.getResources().getConfiguration().orientation;
        if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            this.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else {
            this.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        }

	    BitmapDrawable ob = null;

        EventLogger.logMessage(getClass(), "startLoadingAnimation - webViewLoadingFailed: " + webViewLoadingFailed);

        // releasing old image
        try {
            Bitmap bitmap = ((BitmapDrawable) imageView.getBackground()).getBitmap();

            if(bitmap != null){

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                    imageView.setBackground(null);
                }

                bitmap.recycle();
                bitmap = null;
            }

        }catch( Exception e){
            // Avoid crash
        }

        if(networkErrorView.getVisibility() == View.VISIBLE){
            ob = new BitmapDrawable(getBitmapForVisibleRegion(networkErrorView));
        }
        else{
            ob = new BitmapDrawable(getBitmapForVisibleRegion(cordovaWebView));
        }

	    imageView.setBackgroundDrawable(ob);

	    imageView.setVisibility(View.VISIBLE);

    }

    /**
     * Stop loading animation.
     */
    private void stopLoadingAnimation() {
        if (imageView.getVisibility() == View.VISIBLE) {
            final Animation animationFadeOut = AnimationUtils.loadAnimation(getApplicationContext(), R.anim.fadeout);
            imageView.setVisibility(View.GONE);
            imageView.startAnimation(animationFadeOut);
        }
        //spinnerStop();

        this.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    /**
     * Show the spinner. Must be called from the UI thread.
     */
    public void spinnerStart() {
        if(spinnerEnabled)
            return;

        spinnerEnabled = true;

        flagNumberLoadings++;
        LinearLayout viewLoading = (LinearLayout) findViewById(R.id.view_loading);
        if (viewLoading.getVisibility() != View.VISIBLE) {
            viewLoading.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Stop spinner - Must be called from UI thread
     */
    public void spinnerStop() {

        spinnerEnabled = false;

        if (flagNumberLoadings > 1) {
            flagNumberLoadings = 0;
            return;
        }
        LinearLayout viewLoading = (LinearLayout) findViewById(R.id.view_loading);
        if (viewLoading.getVisibility() == View.VISIBLE) {
            viewLoading.setVisibility(View.GONE);
        }
        flagNumberLoadings--;
    }

    /**
     * Gets the bitmap for visible region.
     *
     * @param webview the webview
     * @return the bitmap for visible region
     */
    public Bitmap getBitmapForVisibleRegion(View webview) {
        try {
            Bitmap returnedBitmap = null;
            webview.setDrawingCacheEnabled(true);
            returnedBitmap = Bitmap.createBitmap(webview.getDrawingCache());
            webview.setDrawingCacheEnabled(false);
            return returnedBitmap;
        } catch (Exception e) {
            EventLogger.logError(getClass(), e.toString());
            return null;
        }
    }

    /**
     * Gets the app version.
     *
     * @return the app version
     */
    private String getAppVersion() {
        try {
            String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return versionName;
        } catch (NameNotFoundException e) {
            EventLogger.logError(getClass(), e);
        }
        return "";
    }

    // ----------------------------------------------//
    // Download File and Open with Native application//
    // ----------------------------------------------//
    private static final HashMap<String, String> MIME_TYPES;
    static {
        MIME_TYPES = new HashMap<String, String>();
        MIME_TYPES.put(".pdf", "application/pdf");
        MIME_TYPES.put(".doc", "application/msword");
        MIME_TYPES.put(".docx", "application/msword");
        MIME_TYPES.put(".xls", "application/vnd.ms-powerpoint");
        MIME_TYPES.put(".xlsx", "application/vnd.ms-powerpoint");
        MIME_TYPES.put(".rtf", "application/vnd.ms-excel");
        MIME_TYPES.put(".wav", "audio/x-wav");
        MIME_TYPES.put(".gif", "image/gif");
        MIME_TYPES.put(".jpg", "image/jpeg");
        MIME_TYPES.put(".jpeg", "image/jpeg");
        MIME_TYPES.put(".png", "image/png");
        MIME_TYPES.put(".txt", "text/plain");
        MIME_TYPES.put(".mpg", "video/*");
        MIME_TYPES.put(".mpeg", "video/*");
        MIME_TYPES.put(".mpe", "video/*");
        MIME_TYPES.put(".mp4", "video/*");
        MIME_TYPES.put(".avi", "video/*");
        MIME_TYPES.put(".ods", "application/vnd.oasis.opendocument.spreadsheet");
        MIME_TYPES.put(".odt", "application/vnd.oasis.opendocument.text");
        MIME_TYPES.put(".ppt", "application/vnd.ms-powerpoint");
        MIME_TYPES.put(".pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        MIME_TYPES.put(".apk", "application/vnd.android.package-archive");
    }

    private String getMimeType(String extension) {
        return MIME_TYPES.get(extension);
    }

    private void openFile(Uri localUri, String extension, Context context) throws JSONException {
        EventLogger.logError(getClass(), "URI --> " + localUri.getPath());
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.setDataAndType(localUri, getMimeType(extension));
        JSONObject obj = new JSONObject();

        try {
            context.startActivity(i);
            obj.put("message", "successfull downloading and openning");
        } catch (ActivityNotFoundException e) {
            obj.put("message", "Failed to open the file, no reader found");
            obj.put("ActivityNotFoundException", e.getMessage());
        }

    }

    private void downloadAndOpenFile(final Context context, final String fileUrl) {
        String filename = fileUrl.substring(fileUrl.lastIndexOf("/") + 1);
        filename = filename.replace("%20", "");
        final String extension = fileUrl.substring(fileUrl.lastIndexOf("."));
        final File tempFile = new File(getDirectorty(), filename);

        if (tempFile.exists()) {
            try {
                openFile(Uri.fromFile(tempFile), extension, context);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return;
        }

        DownloadManager.Request r = new DownloadManager.Request(Uri.parse(fileUrl));

        String cookie = CookieManager.getInstance().getCookie(cordovaWebView.getUrl());
        cookie = cookie.replace('\n', ' ').replace('\r', ' '); // http://cwe.mitre.org/data/definitions/113.html escape CR and LF chars from cookie string

        r.addRequestHeader("Cookie", cookie);
        r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
        final DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        BroadcastReceiver onComplete = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {

                context.unregisterReceiver(this);

                long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                Cursor c = dm.query(new DownloadManager.Query().setFilterById(downloadId));

                if (c.moveToFirst()) {
                    int status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        try {
                            EventLogger.logMessage(getClass(), "Download with success");
                            String fileName = c.getString(c.getColumnIndex(DownloadManager.COLUMN_TITLE));
                            File tempFile = new File(getDirectorty(), fileName);
                            openFile(Uri.fromFile(tempFile), extension, context);
                        } catch (JSONException e) {
                            EventLogger.logError(getClass(), e);
                        }
                    } else {
                        EventLogger.logMessage(getClass(),
                                "Reason: " + c.getInt(c.getColumnIndex(DownloadManager.COLUMN_REASON)));
                    }

                }
                c.close();
            }
        };
        context.registerReceiver(onComplete, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));

        dm.enqueue(r);
    }

    /**
     * Gets the directorty.
     *
     * @return the directorty
     */
    private File getDirectorty() {
        File directory = null;
        if (Environment.getExternalStorageState() == null) {
            // create new file directory object
            directory = new File(Environment.getDataDirectory() + "/Download/");
            // if no directory exists, create new directory
            if (!directory.exists()) {
                directory.mkdir();
            }

            // if phone DOES have sd card
        } else if (Environment.getExternalStorageState() != null) {
            // search for directory on SD card
            directory = new File(Environment.getExternalStorageDirectory() + "/Download/");
            // if no directory exists, create new directory to store test
            // results
            if (!directory.exists()) {
                directory.mkdir();
            }
        }
        return directory;
    }

    /**
     * Offline Support
     */

    private void showWebViewGroup(boolean show){
        View mainView = findViewById(R.id.mainView);
        mainView.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
    }


    private void showNetworkErrorView(boolean show){
        if(this.networkErrorView != null) {
            showNetworkErrorRetryLoading(false);
            if(!show){
                if(this.networkErrorView.getVisibility() == View.VISIBLE) {
                    final Animation animationFadeOut = AnimationUtils.loadAnimation(getApplicationContext(), R.anim.fadeout);
                    animationFadeOut.setAnimationListener(new Animation.AnimationListener() {
                        @Override
                        public void onAnimationStart(Animation animation) {

                        }

                        @Override
                        public void onAnimationEnd(Animation animation) {
                            showWebViewGroup(true);
                            networkErrorView.setVisibility(View.INVISIBLE);

                            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                        }

                        @Override
                        public void onAnimationRepeat(Animation animation) {

                        }
                    });
                    networkErrorView.startAnimation(animationFadeOut);
                }
            }
            else{
                if(this.networkErrorView.getVisibility() == View.INVISIBLE) {
                    final Animation animationFadeIn = AnimationUtils.loadAnimation(getApplicationContext(), R.anim.fade_in);
                    animationFadeIn.setAnimationListener(new Animation.AnimationListener() {
                        @Override
                        public void onAnimationStart(Animation animation) {

                        }

                        @Override
                        public void onAnimationEnd(Animation animation) {
                            showWebViewGroup(false);
                            networkErrorView.setVisibility(View.VISIBLE);

                            int currentOrientation = getResources().getConfiguration().orientation;
                            if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                            } else {
                                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                            }

                            Animation shake = AnimationUtils.loadAnimation(getApplicationContext(), R.anim.shake);
                            networkErrorView.startAnimation(shake);
                        }

                        @Override
                        public void onAnimationRepeat(Animation animation) {

                        }
                    });
                    networkErrorView.startAnimation(animationFadeIn);
                }
            }

        }
    }


    private void retryWebViewAction(){
        showNetworkErrorRetryLoading(true);

        ApplicationOutsystems app = (ApplicationOutsystems)getApplication();

        OfflineSupport.getInstance(getApplicationContext()).retryWebViewAction(this,app,cordovaWebView, failingUrl);
    }


    protected void showNetworkErrorRetryLoading(boolean show) {

        if(this.networkErrorView != null) {
            ProgressBar progressbar = (ProgressBar) networkErrorView.findViewById(R.id.networkErrorProgressBar);
            progressbar.setVisibility(show ? View.VISIBLE : View.INVISIBLE);

            View retryButton = networkErrorView.findViewById(R.id.networkErrorButtonRetry);
            retryButton.setVisibility(show ? View.INVISIBLE : View.VISIBLE);

            if(networkErrorView.getVisibility() == View.VISIBLE && retryButton.getVisibility() == View.VISIBLE){
                Animation shake = AnimationUtils.loadAnimation(getApplicationContext(), R.anim.shake);
                networkErrorView.startAnimation(shake);
            }

        }
    }


    /**
     * The Class CordovaCustomWebClient.
     */
    public class CordovaCustomChromeClient extends CordovaWebViewChromeClient {

        private ProgressBar progressBar;

        public CordovaCustomChromeClient(SystemWebViewEngine engine, CordovaInterface cordovaInterface) {
            super(engine, cordovaInterface);
        }


        @Override
        public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
            return super.onJsAlert(view, url, message, result);
        }

        public ProgressBar getProgressBar() {
            return progressBar;
        }

        public void setProgressBar(ProgressBar progressBar) {
            this.progressBar = progressBar;
        }

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            super.onProgressChanged(view, newProgress);

            if(applicationHasPreloader)
                return;

            final LinearLayout viewLoading = (LinearLayout) findViewById(R.id.view_loading);

            if(newProgress < 100 && viewLoading != null && viewLoading.getVisibility() == View.GONE){
                  viewLoading.setVisibility(View.VISIBLE);
            }

            if(progressBar !=null && newProgress > progressBar.getProgress())
                progressBar.setProgress(newProgress);

            if(newProgress == 100){
                final Animation animationFadeOut = AnimationUtils.loadAnimation(getApplicationContext(), R.anim.fade_out);
                animationFadeOut.setAnimationListener(new Animation.AnimationListener() {
                    @Override
                    public void onAnimationStart(Animation animation) {

                    }

                    @Override
                    public void onAnimationEnd(Animation animation) {
                        viewLoading.setVisibility(View.GONE);
                        progressBar.setProgress(0);
                    }

                    @Override
                    public void onAnimationRepeat(Animation animation) {

                    }
                });
                viewLoading.startAnimation(animationFadeOut);
            }

        }
    }

}
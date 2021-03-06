package cn.yhq.http.core;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Type;

import cn.yhq.http.core.cache.BasicCaching;
import cn.yhq.http.core.cache.CacheUtils;
import cn.yhq.http.core.cache.CachingSystem;
import cn.yhq.http.core.cache.SmartUtils;
import cn.yhq.http.core.interceptor.AuthTokenInterceptor;
import cn.yhq.http.core.interceptor.AuthenticatorInterceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;

/**
 * Created by Yanghuiqiang on 2016/10/14.
 */

final class XCall<T> implements ICall<T> {
    private final static String TAG = "XCall";
    // 拦截器
    private final static AuthenticatorInterceptor mAuthenticatorInterceptor = new AuthenticatorInterceptor();
    private final static AuthTokenInterceptor mAuthTokenInterceptor = new AuthTokenInterceptor();

    private static IHttpRequestListener mDefaultHttpRequestListener;
    private static IHttpExceptionHandler mDefaultHttpExceptionHandler;

    // 缓存有效时间
    private static int CACHE_STALE = 7 * 24 * 3600;
    private static CacheStrategy CACHE_STRATEGY = CacheStrategy.ONLY_NETWORK;

    private IHttpRequestListener mHttpRequestListener;
    private IHttpResponseListener<T> mHttpResponseListener;
    private IHttpExceptionHandler mHttpExceptionHandler;
    private Call<T> mCall;
    private Response<T> mResponse;
    private T mResponseBody;
    private CallUIHandler mCallUIHandler;
    private int mRequestCode;
    private boolean isAsync = true;
    private CacheStrategy mCacheStrategy = CACHE_STRATEGY;
    private int mCacheStale = CACHE_STALE;
    private Retrofit mRetrofit;
    private Type mResponseType;
    private static CachingSystem mCachingSystem;
    private boolean mCacheSupport;
    private static boolean mCacheEnable;

    private HttpRequestListenerProxy mHttpRequestListenerProxy = new HttpRequestListenerProxy();
    private HttpResponseListenerProxy mHttpResponseListenerProxy = new HttpResponseListenerProxy();

    static {
        setCacheStrategy(CACHE_STRATEGY, CACHE_STALE);
        setDefaultHttpExceptionHandler(new DefaultHttpExceptionListener());
        setDefaultHttpRequestListener(new DefaultHttpRequestListener());
    }

    // 回调处理
    private Callback<T> mUICallback = new Callback<T>() {

        @Override
        public void onResponse(Call<T> call, retrofit2.Response<T> response) {
            mResponse = response;
            mResponseBody = mResponse.body();
            if (mCacheEnable && mCacheSupport) {
                byte[] bytes = SmartUtils.requestToBytes(mRetrofit, mResponseBody, mResponseType, null, null);
                byte[] cacheData = CacheUtils.newByteArrayWithDateInfo(mCacheStale, bytes);
                mCachingSystem.addInCache(response, cacheData);
            }
            mCallUIHandler.responseSuccess(response, mRequestCode);
        }

        @Override
        public void onFailure(Call<T> call, Throwable t) {
            mCallUIHandler.responseException(t, mRequestCode);
        }
    };

    @Override
    public Call<T> getRaw() {
        return mCall;
    }

    class HttpResponseListenerProxy extends HttpResponseListener<T> {

        @Override
        public void onResponse(Context context, int requestCode, T response, boolean isFromCache) {
            super.onResponse(context, requestCode, response, isFromCache);
            if (mHttpResponseListener != null) {
                mHttpResponseListener.onResponse(context, requestCode, response, isFromCache);
            }
        }

        @Override
        public void onException(Context context, Throwable t) {
            super.onException(context, t);
            if (mHttpExceptionHandler != null) {
                mHttpExceptionHandler.onException(context, t);
            }
            if (mHttpResponseListener != null) {
                mHttpResponseListener.onException(context, t);
            }
        }
    }

    class HttpRequestListenerProxy extends HttpRequestListener {

        HttpRequestListenerProxy() {
        }

        @Override
        public void onStart(Context context, ICancelable cancelable, int requestCode) {
            if (mHttpRequestListener != null) {
                mHttpRequestListener.onStart(context, cancelable, requestCode);
            }
        }

        @Override
        public void onException(Context context, int requestCode, Throwable t) {
            if (mHttpExceptionHandler != null) {
                mHttpExceptionHandler.onException(context, t);
            }
            if (mHttpRequestListener != null) {
                mHttpRequestListener.onException(context, requestCode, t);
            }
        }

        @Override
        public void onComplete(int requestCode) {
            if (mHttpRequestListener != null) {
                mHttpRequestListener.onComplete(requestCode);
            }
        }

    }

    public static void setAuthTokenHandler(AuthTokenHandler handler) {
        mAuthenticatorInterceptor.setAuthTokenHandler(handler);
        mAuthTokenInterceptor.setAuthTokenHandler(handler);
    }

    public static void setCacheStrategy(CacheStrategy cacheStrategy, int cacheStale) {
        CACHE_STRATEGY = cacheStrategy;
        CACHE_STALE = cacheStale;
    }

    public static void setDefaultCachingSystem(File cacheFile) {
        setCachingSystem(new BasicCaching(cacheFile));
    }

    public static void setCachingSystem(CachingSystem cachingSystem) {
        mCachingSystem = cachingSystem;
        mCacheEnable = true;
    }

    public static void setDefaultHttpExceptionHandler(IHttpExceptionHandler httpExceptionHandler) {
        mDefaultHttpExceptionHandler = httpExceptionHandler;
    }

    public static void setDefaultHttpRequestListener(IHttpRequestListener httpRequestListener) {
        mDefaultHttpRequestListener = httpRequestListener;
    }

    public static void init(OkHttpClient.Builder builder) {
        builder.authenticator(mAuthenticatorInterceptor)
                .addNetworkInterceptor(mAuthTokenInterceptor);
    }

    @Deprecated
    XCall(Call<T> call) {
        this(null, call, null);
        this.mCacheSupport = false;
    }

    XCall(Retrofit retrofit, Call<T> call, Type responseType) {
        this.mRetrofit = retrofit;
        this.mCall = call;
        this.mResponseType = responseType;
        this.mHttpExceptionHandler = mDefaultHttpExceptionHandler;
        this.mHttpRequestListener = mDefaultHttpRequestListener;
        // 默认使用全局的缓存策略
        this.mCacheStrategy = CACHE_STRATEGY;
        this.mCacheSupport = true;
    }

    @Override
    public ICall<T> requestCode(int requestCode) {
        this.mRequestCode = requestCode;
        return this;
    }

    @Override
    public ICallResponse<T> execute(Context context, IHttpResponseListener<T> listener) {
        return execute(context, mHttpRequestListener, listener);
    }

    @Override
    public ICallResponse<T> execute(Context context, IHttpRequestListener requestListener, IHttpResponseListener<T> responseListener) {
        // 监听器
        this.mCallUIHandler = new CallUIHandler(context);
        this.mHttpRequestListener = requestListener;
        this.mHttpResponseListener = responseListener;
        this.mCallUIHandler.setHttpRequestListener(mHttpRequestListenerProxy);
        this.mCallUIHandler.setHttpResponseListener(mHttpResponseListenerProxy);

        return handleRequest();
    }

    @Override
    public ICall<T> async(boolean isAsync) {
        this.isAsync = isAsync;
        return this;
    }

    @Override
    public ICall<T> exceptionHandler(IHttpExceptionHandler handler) {
        this.mHttpExceptionHandler = handler;
        return this;
    }

    @Override
    public ICallExecutor<T> cacheStale(int cacheStale) {
        this.mCacheStale = cacheStale;
        return this;
    }

    @Override
    public ICall<T> cacheStrategy(CacheStrategy cacheStrategy) {
        if (mCacheEnable && mCacheSupport) {
            this.mCacheStrategy = cacheStrategy;
        } else {
            // 不支持缓存处理
            this.mCacheStrategy = CacheStrategy.ONLY_NETWORK;
        }
        return this;
    }

    @Override
    public void cancel() {
        mCall.cancel();
    }

    private Request getRequest() {
        Request request = mCall.request();
        return request;
    }

    /**
     * 缓存处理，现在默认使用当前线程
     */
    private void handleCache() {
        T cache = getCache();
        mResponseBody = cache;
        Log.i(TAG, "cache：" + mResponseBody);
        mCallUIHandler.responseCache(cache, mRequestCode);
    }

    private T getCache() {
        Request request = getRequest();
        byte[] data = mCachingSystem.getFromCache(request);
        if (data == null) {
            return null;
        }
        byte[] response;
        if (!CacheUtils.isDue(data)) {
            response = CacheUtils.clearDateInfo(data);
        } else {
            mCachingSystem.clearCache(request);
            response = null;
        }
        if (response == null) {
            return null;
        }
        T responseBody = SmartUtils.bytesToResponse(mRetrofit, mResponseType, null, response);
        return responseBody;
    }

    private ICallResponse<T> handleRequest() {
        Log.i(TAG, mCacheStrategy.toString());
        if (mCall.request().method() != "GET") {
            mCacheStrategy = CacheStrategy.ONLY_NETWORK;
        }
        switch (mCacheStrategy) {
            case ONLY_CACHE:
                handleCache();
                break;
            case BOTH:
            case FIRST_CACHE_THEN_REQUEST:
                handleCache();
                handleNetwork();
                break;
            case ONLY_NETWORK:
            case NOCACHE:
                handleNetwork();
                break;
            case REQUEST_FAILED_READ_CACHE:
                final Callback<T> originalCallback = mUICallback;
                mUICallback = new Callback<T>() {

                    @Override
                    public void onResponse(Call<T> call, Response<T> response) {
                        originalCallback.onResponse(call, response);
                    }

                    @Override
                    public void onFailure(Call<T> call, Throwable t) {
                        originalCallback.onFailure(call, t);
                        handleCache();
                    }
                };
                handleNetwork();
                break;
            case IF_NONE_CACHE_REQUEST:
                handleCache();
                if (mResponseBody == null) {
                    handleNetwork();
                }
                break;
        }
        return getCallResponse();
    }

    private ICallResponse<T> getCallResponse() {
        ICallResponse<T> callResponse = new ICallResponse<T>() {
            @Override
            public T getResponseBody() {
                return mResponseBody;
            }

            @Override
            public Response getResponse() {
                return mResponse;
            }
        };
        return callResponse;
    }

    private void handleNetwork() {
        try {
            mCallUIHandler.requestStart(this, mRequestCode);
            // 真正开始请求的地方
            if (isAsync) {
                // 异步执行
                mCall.enqueue(mUICallback);
            } else {
                // 同步执行
                mResponse = mCall.execute();
                mResponseBody = mResponse.body();
                mUICallback.onResponse(mCall, mResponse);
            }
        } catch (Throwable t) {
            mUICallback.onFailure(mCall, t);
        }
    }

}

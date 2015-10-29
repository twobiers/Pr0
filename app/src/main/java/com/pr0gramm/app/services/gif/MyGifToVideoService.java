package com.pr0gramm.app.services.gif;

import com.google.common.base.Charsets;
import com.google.common.io.BaseEncoding;
import com.squareup.okhttp.OkHttpClient;

import javax.inject.Inject;
import javax.inject.Singleton;

import proguard.annotation.KeepClassMembers;
import retrofit.GsonConverterFactory;
import retrofit.Retrofit;
import retrofit.RxJavaCallAdapterFactory;
import retrofit.http.GET;
import retrofit.http.Path;
import rx.Observable;

/**
 * Converts a gif to a webm using my own conversion service.
 */
@Singleton
public class MyGifToVideoService implements GifToVideoService {
    private static final String DEFAULT_ENDPOINT = "http://pr0.wibbly-wobbly.de/api/gif-to-webm/v1/";

    private final Api api;

    @Inject
    public MyGifToVideoService(OkHttpClient httpClient) {
        this.api = new Retrofit.Builder()
                .baseUrl(DEFAULT_ENDPOINT)
                .client(httpClient)
                .addCallAdapterFactory(RxJavaCallAdapterFactory.create())
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(Api.class);
    }

    /**
     * Converts this gif into a webm, if possible.
     *
     * @param gifUrl The url of the gif to convert.
     * @return A observable producing exactly one item with the result.
     */
    @Override
    public Observable<Result> toVideo(String gifUrl) {
        Observable<Result> fallback = Observable.just(new Result(gifUrl));
        if (!gifUrl.toLowerCase().endsWith(".gif")) {
            return fallback;
        }

        String encoded = BaseEncoding.base64Url().encode(gifUrl.getBytes(Charsets.UTF_8));
        return api.convert(encoded)
                .map(result -> new Result(gifUrl, DEFAULT_ENDPOINT + result.path))
                .onErrorResumeNext(fallback);
    }

    /**
     * Simple gif-to-webm service.
     */
    private interface Api {
        @GET("convert/{url}")
        Observable<ConvertResult> convert(@Path("url") String encodedUrl);
    }

    /**
     */
    @KeepClassMembers
    private static class ConvertResult {
        String path;
    }
}

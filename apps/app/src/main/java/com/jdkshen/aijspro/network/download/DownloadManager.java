package com.jdkshen.aijspro.network.download;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import com.afollestad.materialdialogs.MaterialDialog;
import com.jdkshen.aijspro.BuildConfig;
import com.jakewharton.retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import com.stardust.pio.PFiles;

import com.jdkshen.aijspro.R;
import com.jdkshen.aijspro.model.script.ScriptFile;
import com.jdkshen.aijspro.network.api.DownloadApi;
import com.jdkshen.aijspro.tool.SimpleObserver;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.ReplaySubject;
import io.reactivex.subjects.Subject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import retrofit2.Retrofit;

/**
 * Created by Stardust on 2017/10/20.
 */

public class DownloadManager {

    private static final String LOG_TAG = "DownloadManager";
    private static DownloadManager sInstance;

    private static final int RETRY_COUNT = 3;
    private Retrofit mRetrofit;
    private DownloadApi mDownloadApi;
    private final ConcurrentHashMap<String, DownloadTask> mDownloadTasks = new ConcurrentHashMap<>();

    public DownloadManager() {
        mRetrofit = new Retrofit.Builder()
                // Downloads use absolute URLs. Keep the Retrofit base on a maintained HTTPS
                // origin instead of constructing every download client with legacy autojs.org.
                .baseUrl("https://api.github.com/")
                .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                .client(new OkHttpClient.Builder()
                        .addInterceptor(chain -> {
                            Request request = chain.request();
                            Response response = chain.proceed(request);
                            int tryCount = 0;
                            while (!response.isSuccessful() && tryCount < RETRY_COUNT) {
                                tryCount++;
                                response.close();
                                response = chain.proceed(request);
                            }
                            return response;
                        })
                        .build()
                )
                .build();
        mDownloadApi = mRetrofit.create(DownloadApi.class);
    }


    public static DownloadManager getInstance() {
        if (sInstance == null) {
            sInstance = new DownloadManager();
        }
        return sInstance;
    }


    public static String parseFileNameLocally(String url) {
        int i = url.lastIndexOf('-');
        if (i < 0) {
            i = url.lastIndexOf('/');
        }
        return URLDecoder.decode(url.substring(i + 1));
    }

    public Observable<Integer> download(String url, String path) {
        DownloadTask task = new DownloadTask(url, path);
        task.setRequest(mDownloadApi.download(url)
                .subscribeOn(Schedulers.io())
                .subscribe(task::start, task::fail));
        return task.progress();
    }

    public Observable<File> downloadWithProgress(Context context, String url, String path) {
        String fileName = DownloadManager.parseFileNameLocally(url);
        return download(url, path, createDownloadProgressUi(context, url, fileName));
    }

    /** 下载进度 UI 的抽象：Miuix（Compose）与 Material 两套实现都实现它。 */
    private interface ProgressUi {
        void setProgress(int progress);

        void dismiss();
    }

    /**
     * 下载进度 UI：Miuix（Compose）优先，context 不是 Activity（或挂不上对话框）时回退 Material。
     * miuix 源集已是唯一界面线，这里直接调用，不再需要反射包装。
     */
    private ProgressUi createDownloadProgressUi(Context context, String url, String fileName) {
        if (context instanceof Activity) {
            Object handle = com.jdkshen.aijspro.ui.update.MiuixDownloadProgressDialog.show(
                    (Activity) context, "正在下载更新", fileName,
                    () -> DownloadManager.getInstance().cancelDownload(url));
            if (handle != null) {
                return new ProgressUi() {
                    @Override
                    public void setProgress(int progress) {
                        com.jdkshen.aijspro.ui.update.MiuixDownloadProgressDialog
                                .setProgress(handle, progress);
                    }

                    @Override
                    public void dismiss() {
                        com.jdkshen.aijspro.ui.update.MiuixDownloadProgressDialog.dismiss(handle);
                    }
                };
            }
        }
        MaterialDialog dialog = new MaterialDialog.Builder(context)
                .progress(false, 100)
                .title(fileName)
                .cancelable(false)
                .positiveText(R.string.text_cancel_download)
                .onPositive((d, which) -> DownloadManager.getInstance().cancelDownload(url))
                .build();
        dialog.show();
        return new ProgressUi() {
            @Override
            public void setProgress(int progress) {
                dialog.setProgress(progress);
            }

            @Override
            public void dismiss() {
                dialog.dismiss();
            }
        };
    }

    private Observable<File> download(String url, String path, ProgressUi progressUi) {
        PublishSubject<File> subject = PublishSubject.create();
        DownloadManager.getInstance().download(url, path)
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(progressUi::setProgress)
                .subscribe(new SimpleObserver<Integer>() {
                    @Override
                    public void onComplete() {
                        progressUi.dismiss();
                        subject.onNext(new File(path));
                        subject.onComplete();
                    }

                    @Override
                    public void onError(Throwable error) {
                        Log.e(LOG_TAG, "Download failed", error);
                        progressUi.dismiss();
                        subject.onError(error);
                    }
                });
        return subject;
    }

    public void cancelDownload(String url) {
        DownloadTask task = mDownloadTasks.get(url);
        if (task != null) {
            task.cancel();
        }
    }

    private class DownloadTask {

        private String mUrl;
        private String mPath;
        private volatile boolean mActive = true;
        private final AtomicBoolean mTerminated = new AtomicBoolean();
        private InputStream mInputStream;
        private FileOutputStream mFileOutputStream;
        private volatile boolean mOutputCreated;
        private final Subject<Integer> mProgress = ReplaySubject.<Integer>createWithSize(1).toSerialized();
        private Disposable mRequest;

        public DownloadTask(String url, String path) {
            mUrl = url;
            mPath = path;
            DownloadTask previous = mDownloadTasks.put(mUrl, this);
            if (previous != null) previous.cancel();
        }

        void setRequest(Disposable request) {
            mRequest = request;
            if (mTerminated.get()) request.dispose();
        }

        private void startImpl(ResponseBody body) throws IOException {
            byte[] buffer = new byte[4096];
            mFileOutputStream = new FileOutputStream(mPath);
            mOutputCreated = true;
            mInputStream = body.byteStream();
            long total = body.contentLength();
            long read = 0;
            while (true) {
                if (!mActive) {
                    return;
                }
                int len = mInputStream.read(buffer);
                if (len == -1) {
                    break;
                }
                read += len;
                mFileOutputStream.write(buffer, 0, len);
                if (total > 0) {
                    mProgress.onNext((int) (100 * read / total));
                }
            }
            complete();
        }

        public void start(ResponseBody body) {
            if (!mActive) {
                body.close();
                return;
            }
            try {
                PFiles.ensureDir(mPath);
                startImpl(body);
            } catch (Exception e) {
                fail(e);
            }
        }

        private void complete() {
            if (!mTerminated.compareAndSet(false, true)) return;
            recycle();
            mProgress.onComplete();
        }

        private void fail(Throwable error) {
            if (!mTerminated.compareAndSet(false, true)) return;
            mActive = false;
            recycle();
            deletePartialFile();
            mProgress.onError(error);
        }

        private void cancel() {
            if (!mTerminated.compareAndSet(false, true)) return;
            mActive = false;
            Disposable request = mRequest;
            if (request != null) request.dispose();
            recycle();
            deletePartialFile();
            mProgress.onError(new CancellationException("Download cancelled"));
        }

        private void deletePartialFile() {
            if (!mOutputCreated) return;
            File partial = new File(mPath);
            if (partial.exists() && !partial.delete()) {
                Log.w(LOG_TAG, "Could not remove incomplete download: " + mPath);
            }
        }

        public synchronized void recycle() {
            mDownloadTasks.remove(mUrl, this);
            if (mInputStream != null) {
                try {
                    mInputStream.close();
                } catch (IOException ignored) {

                }
            }
            if (mFileOutputStream != null) {
                try {
                    mFileOutputStream.close();
                } catch (IOException ignored) {
                }
            }
            mInputStream = null;
            mFileOutputStream = null;

        }

        public Observable<Integer> progress() {
            return mProgress;
        }


    }
}

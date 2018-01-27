package com.just.agentweb;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.text.TextUtils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Observable;
import java.util.Observer;
import java.util.UnknownFormatConversionException;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * Created by cenxiaozhong on 2017/5/13.
 * source code  https://github.com/Justson/AgentWeb
 */

public class Downloader extends AsyncTask<Void, Integer, Integer> implements Observer {

    /**
     * 下载参数
     */
    private DownLoadTask mDownLoadTask;
    /**
     * 已经下载的大小
     */
    private long loaded = 0;
    /**
     * 总大小
     */
    private long totals = -1;
    /**
     *
     */
    private long tmp = 0;
    private long begin = 0;
    private long used = 1;
    private long mTimeLast = 0;
    /**
     * 当前下载速度
     */
    private long mSpeed = 0;
    /**
     * 下载错误回调给用户的错误
     */
    private Exception e;
    /**
     * 下载最大时长
     */
    private static final int TIME_OUT = 30000000;
    /**
     * 通知
     */
    private Notify mNotify;

    private static final int ERROR_LOAD = 406;

    private static final String TAG = Downloader.class.getSimpleName();
    /**
     * false 表示用户已经取消下载
     */
    private AtomicBoolean atomic = new AtomicBoolean(false);
    /**
     * Observable 缓存当前Downloader，如果用户滑动通知取消下载，通知所有 Downloader 找到
     * 相应的 Downloader 取消下载。
     */
    private static Observable mObservable = new Observable() {
        @Override
        public synchronized void setChanged() {
            super.setChanged();
        }
    };


    Downloader(DownLoadTask downLoadTask) {
        this.mDownLoadTask = downLoadTask;
        this.totals = mDownLoadTask.getLength();
        checkNullTask(downLoadTask);
    }

    private void checkNullTask(DownLoadTask downLoadTask) {

    }

    @Override
    protected void onPreExecute() {
        super.onPreExecute();
        mObservable.addObserver(this);
        buildNotify(new Intent(), mDownLoadTask.getId(), mDownLoadTask.getDownloadMsgConfig().getPreLoading());
    }

    private boolean checkDownloadCondition() {

        if (mDownLoadTask.getLength() - mDownLoadTask.getFile().length() > AgentWebUtils.getAvailableStorage()) {
            LogUtils.i(TAG, " 空间不足");
            return false;
        }
        return true;
    }

    private boolean checknet() {
        if (!mDownLoadTask.isForce()) {
            return AgentWebUtils.checkWifi(mDownLoadTask.getContext());
        } else {
            return AgentWebUtils.checkNetwork(mDownLoadTask.getContext());
        }


    }


    @Override
    protected Integer doInBackground(Void... params) {
        int result = ERROR_LOAD;
        try {
            begin = System.currentTimeMillis();
            if (!checkDownloadCondition())
                return DownloadMsg.STORAGE_ERROR.CODE;
            if (!checknet())
                return DownloadMsg.NETWORK_ERROR_CONNECTION.CODE;
            result = doDownload();

        } catch (Exception e) {

            this.e = e;//发布
            LogUtils.i(TAG, "doInBackground   Exception:" + e.getMessage());
            // e.printStackTrace();

        }

        return result;
    }

    private int doDownload() throws IOException {

        HttpURLConnection mHttpURLConnection = createUrlConnection(mDownLoadTask.getUrl());


        if (mDownLoadTask.getFile().length() > 0) {

            mHttpURLConnection.addRequestProperty("Range", "bytes=" + (tmp = mDownLoadTask.getFile().length()) + "-");
        }
        try {
            mHttpURLConnection.connect();
            boolean isSeek = false;
            if (mHttpURLConnection.getResponseCode() != 200 && mHttpURLConnection.getResponseCode() != 206 && (isSeek = true)) {
                return DownloadMsg.NETWORK_ERROR_STATUS_CODE.CODE;
            }
            return doDownload(mHttpURLConnection.getInputStream(), new LoadingRandomAccessFile(mDownLoadTask.getFile()), isSeek);
        } finally {
            if (mHttpURLConnection != null)
                mHttpURLConnection.disconnect();
        }

    }

    private HttpURLConnection createUrlConnection(String url) throws IOException {


        HttpURLConnection mHttpURLConnection = (HttpURLConnection) new URL(url).openConnection();
        mHttpURLConnection.setRequestProperty("Accept", "application/*");
        mHttpURLConnection.setConnectTimeout(5000 * 2);
        return mHttpURLConnection;
    }

    private long time = 0;

    @Override
    protected void onProgressUpdate(Integer... values) {

        try {
            long c = System.currentTimeMillis();
            if (mNotify != null && c - time > 800) {
                time = c;
                if (!mNotify.hasDeleteContent())
                    mNotify.setDelecte(buildCancelContent(mDownLoadTask.getContext().getApplicationContext(), mDownLoadTask.getId()));

                int mProgress = (int) ((tmp + loaded) / Float.valueOf(totals) * 100);
                mNotify.setContentText(String.format(mDownLoadTask.getDownloadMsgConfig().getLoading(), mProgress + "%"));
                mNotify.setProgress(100, mProgress, false);
            }

        } catch (UnknownFormatConversionException e) {
            e.printStackTrace();
        }
        long current = System.currentTimeMillis();
        used = current - begin;


    }

    @Override
    protected void onPostExecute(Integer integer) {

        try {
            LogUtils.i(TAG, "onPostExecute:" + integer);
            mObservable.deleteObserver(this);
            boolean t = doCallback(integer);
            if (integer > 200) {

                if (mNotify != null)
                    mNotify.cancel(mDownLoadTask.getId());
                return;
            }
            if (mDownLoadTask.isEnableIndicator()) {
                if (mNotify != null)
                    mNotify.cancel(mDownLoadTask.getId());

                if(t){
                    return;
                }
                Intent mIntent = AgentWebUtils.getCommonFileIntentCompat(mDownLoadTask.getContext(), mDownLoadTask.getFile());
                try {
                    if (mIntent != null) {
                        if (!(mDownLoadTask.getContext() instanceof Activity))
                            mIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        PendingIntent rightPendIntent = PendingIntent.getActivity(mDownLoadTask.getContext(),
                                mDownLoadTask.getId() << 4, mIntent, PendingIntent.FLAG_UPDATE_CURRENT);
                        mNotify.setProgressFinish(mDownLoadTask.getDownloadMsgConfig().getClickOpen(), rightPendIntent);
                    }
                    return;
                } catch (Throwable throwable) {
                    if (LogUtils.isDebug())
                        throwable.printStackTrace();
                }
            }
        } catch (Exception e) {
            if (LogUtils.isDebug()) {
                e.printStackTrace();
            }
        }


    }

    private boolean doCallback(Integer code) {
        DownloadListener mDownloadListener = null;
        if ((mDownloadListener = mDownLoadTask.getDownLoadResultListener()) == null) {
            LogUtils.e(TAG, "DownloadListener has been death");
            DefaultDownloadImpl.ExecuteTasksMap.getInstance().removeTask(mDownLoadTask.getFile().getPath());
            return false;
        }
        return mDownloadListener.result(mDownLoadTask.getFile().getAbsolutePath(), mDownLoadTask.getUrl(), code <= 200 ? null : this.e == null ? new RuntimeException("download fail ， cause:" + DownloadMsg.getMsgByCode(code)) : this.e);

    }


    private void buildNotify(Intent intent, int id, String progressHint) {

        Context mContext = mDownLoadTask.getContext().getApplicationContext();
        if (mContext != null && mDownLoadTask.isEnableIndicator()) {

            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent rightPendIntent = PendingIntent.getActivity(mContext,
                    0x33 * id, intent, PendingIntent.FLAG_UPDATE_CURRENT);
            int smallIcon = mDownLoadTask.getDrawableRes();
            String ticker = mDownLoadTask.getDownloadMsgConfig().getTrickter();
            mNotify = new Notify(mContext, id);

            String title = TextUtils.isEmpty(mDownLoadTask.getFile().getName()) ? mDownLoadTask.getDownloadMsgConfig().getFileDownLoad() : mDownLoadTask.getFile().getName();

            mNotify.notify_progress(rightPendIntent, smallIcon, ticker, title, progressHint, false, false, false, buildCancelContent(mContext, id));
            mNotify.sent();
        }
    }


    private PendingIntent buildCancelContent(Context context, int id) {

        Intent intentCancel = new Intent(context, NotificationBroadcastReceiver.class);
        intentCancel.setAction("com.agentweb.cancelled");
        intentCancel.putExtra("type", "type");
        intentCancel.putExtra("TAG", mDownLoadTask.getUrl());
        PendingIntent pendingIntentCancel = PendingIntent.getBroadcast(context, id << 3, intentCancel, PendingIntent.FLAG_UPDATE_CURRENT);
        LogUtils.i(TAG, "id<<3:" + (id << 3));
        return pendingIntentCancel;
    }


    private int doDownload(InputStream in, RandomAccessFile out, boolean isSeek) throws IOException {

        byte[] buffer = new byte[10240];
        BufferedInputStream bis = new BufferedInputStream(in, 1024 * 10);
        try {

            if (isSeek) {
                LogUtils.i(TAG, "seek -- >" + isSeek + "  length:" + out.length());
                out.seek(out.length());
            }

            int bytes = 0;
            long previousBlockTime = -1;


            while (!atomic.get()) {
                int n = bis.read(buffer, 0, 1024 * 10);
                if (n == -1) {
                    break;
                }
                out.write(buffer, 0, n);
                bytes += n;

                if (!checknet()) {
                    LogUtils.i(TAG, "network");
                    return DownloadMsg.NETWORK_ERROR_CONNECTION.CODE;
                }

                if (mSpeed != 0) {
                    previousBlockTime = -1;
                } else if (previousBlockTime == -1) {
                    previousBlockTime = System.currentTimeMillis();
                } else if ((System.currentTimeMillis() - previousBlockTime) > TIME_OUT) {
                    LogUtils.i(TAG, "timeout");
                    return DownloadMsg.TIME_OUT.CODE;
                }
            }
            LogUtils.i(TAG, "atomic:" + atomic.get());
            if (atomic.get()) {
                return DownloadMsg.USER_CANCEL.CODE;
            }
            return DownloadMsg.SUCCESSFULL.CODE;
        } finally {
            CloseUtils.closeIO(out);
            CloseUtils.closeIO(bis);

        }

    }

    private final void toCancel() {
        atomic.set(true);
    }

    @Override
    public void update(Observable o, Object arg) {
        //LogUtils.i(TAG, "update Object    ... ");
        String url = "";
        if (arg instanceof String && !TextUtils.isEmpty(url = (String) arg) && url.equals(mDownLoadTask.getUrl())) {
            toCancel();
        }


    }

    private final class LoadingRandomAccessFile extends RandomAccessFile {

        public LoadingRandomAccessFile(File file) throws FileNotFoundException {
            super(file, "rw");
        }

        @Override
        public void write(byte[] buffer, int offset, int count) throws IOException {

            super.write(buffer, offset, count);
            loaded += count;
            publishProgress(0);

        }
    }


    enum DownloadMsg {
        NETWORK_ERROR_CONNECTION(400), NETWORK_ERROR_STATUS_CODE(401), STORAGE_ERROR(402), TIME_OUT(403), USER_CANCEL(404), SUCCESSFULL(200);
        int CODE;

        DownloadMsg(int e) {
            this.CODE = e;
        }


        public static String getMsgByCode(int code) {
            LogUtils.i(TAG, "  CODE:" + code);
            switch (code) {


                case 400:
                    return "Network connection result";
                case 401:
                    return "Connection status code result, non-200 or non 206";
                case 402:
                    return "Insufficient memory space";
                case 403:
                    return "Download time is overtime";
                case 404:
                    return "The user canceled the download";
                case 200:
                    return "Download successful";
                default:
                    return "Unknown exception";

            }
        }


    }


    public static class NotificationBroadcastReceiver extends BroadcastReceiver {


        public NotificationBroadcastReceiver() {
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals("com.agentweb.cancelled")) {

                try {
                    String url = intent.getStringExtra("TAG");
                    Class<?> mClazz = mObservable.getClass();
                    Method mMethod = mClazz.getMethod("setChanged", (Class<?>[]) null);
                    mMethod.setAccessible(true);
                    mMethod.invoke(mObservable, (Object[]) null);
                    mObservable.notifyObservers(url);
                    LogUtils.i(TAG, "size:" + mObservable.countObservers());
                } catch (Throwable ignore) {
//                    ignore.printStackTrace();
                }

            }
        }


    }


}

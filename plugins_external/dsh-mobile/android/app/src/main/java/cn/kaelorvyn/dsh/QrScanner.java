package cn.kaelorvyn.dsh;

import android.app.Activity;
import android.hardware.Camera;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 全屏扫码取景器：调起后置摄像头实时解二维码。
 *
 * <p>只用系统自带的 {@link Camera}（API 1，虽已废弃但无需任何依赖库）+ ZXing core 解码，
 * 不引 AndroidX、不引扫码 UI 库 —— 个人 App 够用且最省事。
 *
 * <p>用法：{@code show()} 出取景框，扫到第一个二维码回调一次后自动收起。
 */
public final class QrScanner {

    /** 扫到二维码时回到主线程回调，text 是二维码里的原始字符串。 */
    public interface OnResult {
        void onQr(String text);
    }

    /** 点「从相册选择」时回调；由 Activity 去拉系统相册（取景器这边不认识相册）。 */
    public interface OnPickImage {
        void onPickImage();
    }

    private static final int TARGET_W = 1280;
    private static final int TARGET_H = 720;

    private final Activity act;
    private final OnResult callback;
    private final FrameLayout layer;
    private final SurfaceView surface;
    private final TextView hint;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicBoolean decoded = new AtomicBoolean(false);
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final QRCodeReader reader = new QRCodeReader();

    private Camera camera;
    private boolean surfaceReady = false;
    private int frameSkip = 0;
    private OnPickImage onPickImage;

    /** 注册「从相册选择」的回调（Activity 在 buildUi 时装上）。 */
    public void setOnPickImage(OnPickImage cb) {
        this.onPickImage = cb;
    }

    public QrScanner(Activity act, OnResult callback) {
        this.act = act;
        this.callback = callback;

        layer = new FrameLayout(act);
        layer.setBackgroundColor(0xFF101418);
        layer.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layer.setVisibility(View.GONE);
        layer.setClickable(true);   // 挡住底下的 WebView，别让误触穿透

        surface = new SurfaceView(act);
        surface.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layer.addView(surface);

        // 半透明说明条，压在最上面
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(24), dp(28), dp(24), dp(28));
        bar.setBackgroundColor(0xCC000000);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.BOTTOM;
        bar.setLayoutParams(blp);

        hint = new TextView(act);
        hint.setTextColor(0xFFFFFFFF);
        hint.setTextSize(15);
        hint.setGravity(Gravity.CENTER);
        hint.setText("把电脑上的二维码放进框里");
        bar.addView(hint);

        TextView sub = new TextView(act);
        sub.setTextColor(0xFFB9C0CC);
        sub.setTextSize(12);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(6), 0, 0);
        sub.setText("DSH 电脑端 → 设置 → 手机访问");
        bar.addView(sub);

        // 底部一排：从相册选择 | 取消
        // 相册这条路是给「二维码在另一台设备/截图里、没法拿摄像头对着扫」的场合兜底。
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(14), 0, 0);
        bar.addView(row);

        TextView pick = new TextView(act);
        pick.setTextColor(0xFF7FB0FF);
        pick.setTextSize(14);
        pick.setPadding(dp(14), 0, dp(14), 0);
        pick.setText("从相册选择");
        pick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 先收起取景框再拉相册：相册是另一个界面，摄像头留着只会白耗电
                hide();
                if (onPickImage != null) onPickImage.onPickImage();
            }
        });
        row.addView(pick);

        TextView sep = new TextView(act);
        sep.setTextColor(0xFF4A5260);
        sep.setTextSize(14);
        sep.setText("|");
        row.addView(sep);

        TextView cancel = new TextView(act);
        cancel.setTextColor(0xFF7FB0FF);
        cancel.setTextSize(14);
        cancel.setPadding(dp(14), 0, dp(14), 0);
        cancel.setText("取消");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hide();
            }
        });
        row.addView(cancel);

        layer.addView(bar);

        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder h) {
                surfaceReady = true;
                startPreview();
            }

            @Override
            public void surfaceChanged(SurfaceHolder h, int format, int w, int hh) {
                if (camera != null) {
                    try {
                        camera.stopPreview();
                        camera.setDisplayOrientation(90);
                        camera.startPreview();
                    } catch (Throwable ignored) {
                        // 个别机器切分辨率时会抛，忽略即可
                    }
                }
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder h) {
                surfaceReady = false;
            }
        });
    }

    /** 把这个取景层挂到 Activity 的根布局上（只挂一次）。 */
    public void attach(FrameLayout root) {
        if (layer.getParent() == null) root.addView(layer);
    }

    public boolean isShowing() {
        return layer.getVisibility() == View.VISIBLE;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                act.getResources().getDisplayMetrics());
    }

    /** 出取景框并开始预览。 */
    public void show() {
        decoded.set(false);
        layer.setVisibility(View.VISIBLE);
        layer.bringToFront();
        if (surfaceReady) startPreview();
    }

    /** 收起并释放摄像头。 */
    public void hide() {
        layer.setVisibility(View.GONE);
        stopPreview();
    }

    private void startPreview() {
        if (camera != null || !surfaceReady || layer.getVisibility() != View.VISIBLE) return;
        try {
            camera = Camera.open(findBackCamera());
        } catch (Throwable t) {
            fail("打不开摄像头：" + t.getMessage());
            return;
        }
        try {
            Camera.Parameters p = camera.getParameters();
            p.setPreviewFormat(android.graphics.ImageFormat.NV21);
            Camera.Size best = pickSize(p.getSupportedPreviewSizes());
            if (best != null) p.setPreviewSize(best.width, best.height);
            // 连续对焦：扫屏幕上的码时最容易对上
            List<String> modes = p.getSupportedFocusModes();
            if (modes != null && modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            }
            camera.setParameters(p);
            camera.setDisplayOrientation(90);
            camera.setPreviewDisplay(surface.getHolder());
            camera.setPreviewCallback(new Camera.PreviewCallback() {
                @Override
                public void onPreviewFrame(byte[] data, Camera cam) {
                    onFrame(data, cam);
                }
            });
            camera.startPreview();
        } catch (Throwable t) {
            fail("摄像头启动失败：" + t.getMessage());
        }
    }

    private void stopPreview() {
        Camera c = camera;
        camera = null;
        if (c == null) return;
        try { c.setPreviewCallback(null); } catch (Throwable ignored) { }
        try { c.stopPreview(); } catch (Throwable ignored) { }
        try { c.release(); } catch (Throwable ignored) { }
    }

    private static int findBackCamera() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        int count = Camera.getNumberOfCameras();
        for (int i = 0; i < count; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) return i;
        }
        return 0;
    }

    /** 挑一个接近 720p 的预览尺寸：太大解码慢，太小扫不清屏幕上的码。 */
    private static Camera.Size pickSize(List<Camera.Size> sizes) {
        if (sizes == null || sizes.isEmpty()) return null;
        Camera.Size best = null;
        long bestScore = Long.MAX_VALUE;
        for (Camera.Size s : sizes) {
            long score = Math.abs((long) s.width * s.height - (long) TARGET_W * TARGET_H);
            if (score < bestScore) { bestScore = score; best = s; }
        }
        return best;
    }

    private void onFrame(final byte[] data, final Camera cam) {
        if (decoded.get()) return;
        if ((frameSkip++ % 3) != 0) return;          // 每 3 帧解一次，省电
        if (!busy.compareAndSet(false, true)) return; // 解码线程还忙着就丢帧
        final Camera.Size size = cam.getParameters().getPreviewSize();
        decoder.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(
                            data, size.width, size.height, 0, 0, size.width, size.height, false);
                    Result r = reader.decode(new BinaryBitmap(new HybridBinarizer(src)));
                    final String text = r == null ? null : r.getText();
                    if (text != null && decoded.compareAndSet(false, true)) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                hide();
                                callback.onQr(text);
                            }
                        });
                    }
                } catch (Throwable ignored) {
                    // 绝大多数帧都解不出来（没对焦、没码），正常现象，继续下一帧
                } finally {
                    busy.set(false);
                }
            }
        });
    }

    private void fail(final String msg) {
        stopPreview();
        ui.post(new Runnable() {
            @Override
            public void run() {
                hint.setText(msg);
            }
        });
    }

    public void dispose() {
        stopPreview();
        decoder.shutdownNow();
    }
}

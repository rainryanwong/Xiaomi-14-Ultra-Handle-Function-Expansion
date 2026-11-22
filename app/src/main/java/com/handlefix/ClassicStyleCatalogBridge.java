package com.handlefix;

import android.content.Context;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * 相机进程与 HandleFix 设置页之间的运行时风格目录桥。
 *
 * <p>不能使用跨包 ContentProvider：在 Android 16/当前 HyperOS 上，即使模块声明
 * {@code forceQueryable}，注入 {@code com.android.camera} 的代码仍无法解析模块包的
 * Provider。这里让相机把不含敏感信息的目录快照写进自己的外部专属 files 目录，设置
 * App 再复用模块已有的 Root 能力读取。不能使用相机 CE 私有目录或
 * {@code Android/data/com.android.camera}：当前系统中，从普通 App 发起的 KernelSU
 * 子进程仍会分别被 SELinux 和 FUSE 按包隔离。相机本身持有已授予的
 * {@code MANAGE_EXTERNAL_STORAGE}，因此双方以 HandleFix 的公共配置目录交换这份
 * 非敏感快照；设置页仍会核对版本和适配档案。</p>
 *
 * <p>文件写入采用同目录临时文件加原子替换。设置页还会核对 profile 与相机
 * versionCode，因此切换相机包后不会误用旧目录。</p>
 */
public final class ClassicStyleCatalogBridge {
    public static final String FILE_NAME = "handlefix_classic_style_catalog.json";
    public static final String CAMERA_CATALOG_PATH =
            "/storage/emulated/0/Download/HandleFixConfig/.cache/" + FILE_NAME;

    public static final String KEY_PROFILE = "profile";
    public static final String KEY_CAMERA_VERSION_CODE = "cameraVersionCode";
    public static final String KEY_LSP_VERSION_CODE = "lspVersionCode";
    public static final String KEY_IDS = "ids";
    public static final String KEY_LABELS = "labels";
    public static final String KEY_PUBLISHED_AT = "publishedAt";

    private ClassicStyleCatalogBridge() {
    }

    /**
     * 纯版本映射，供相机 Hook 与普通设置进程共同使用。
     *
     * <p>不要让设置 App 为了取得一个字符串去加载包含 Xposed API 的控制器；部分
     * LSPosed 环境不会把 Xposed 运行库暴露给模块自身进程。</p>
     */
    public static String profileForVersion(long cameraVersionCode) {
        if (CameraBuilds.isCamera68Port(cameraVersionCode)) {
            return "camera68-001770";
        }
        if (cameraVersionCode == CameraBuilds.PHOENIX_030_VERSION_CODE) {
            return "phoenix-030";
        }
        if (cameraVersionCode == CameraBuilds.NRV_161_VERSION_CODE) {
            return "nrv-161";
        }
        if (CameraBuilds.isPhoenixOs4Family(cameraVersionCode)) {
            // 新 Phoenix 仍按 versionCode 分桶，避免把旧包或另一份作者目录复用过来。
            return "phoenix-os4-" + cameraVersionCode;
        }
        return null;
    }

    public static boolean publish(Context context, String profile, long cameraVersionCode,
            long lspVersionCode, String[] ids, String[] labels) {
        if (context == null || profile == null || profile.trim().length() == 0
                || ids == null || ids.length == 0) {
            return false;
        }
        File downloads = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        File directory = new File(downloads, "HandleFixConfig/.cache");
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory()) {
            return false;
        }
        File target = new File(directory, FILE_NAME);
        File temporary = new File(directory, FILE_NAME + ".tmp");
        try {
            JSONArray idArray = new JSONArray();
            JSONArray labelArray = new JSONArray();
            for (int i = 0; i < ids.length; i++) {
                String id = ids[i] == null ? "" : ids[i].trim();
                if (id.length() == 0) {
                    continue;
                }
                String label = labels != null && i < labels.length ? labels[i] : id;
                idArray.put(id);
                labelArray.put(label == null || label.trim().length() == 0 ? id : label.trim());
            }
            if (idArray.length() == 0) {
                return false;
            }

            JSONObject value = new JSONObject();
            value.put(KEY_PROFILE, profile.trim());
            value.put(KEY_CAMERA_VERSION_CODE, cameraVersionCode);
            value.put(KEY_LSP_VERSION_CODE, lspVersionCode);
            value.put(KEY_IDS, idArray);
            value.put(KEY_LABELS, labelArray);
            value.put(KEY_PUBLISHED_AT, System.currentTimeMillis());

            byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream stream = new FileOutputStream(temporary, false)) {
                stream.write(bytes);
                stream.flush();
                stream.getFD().sync();
            }
            try {
                Files.move(temporary.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Throwable ignored) {
            // 目录只影响设置页的可选风格列表；写入失败不能中断相机或实际切换。
            try {
                Files.deleteIfExists(temporary.toPath());
            } catch (Throwable ignoredDelete) {
                // 临时文件会在下一次发布时被覆盖。
            }
            return false;
        }
    }
}

package com.jdkshen.aijspro.pluginclient;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import androidx.annotation.Nullable;

/**
 * Lets the Auto.js Pro VS Code extension discover the debug server over USB.
 *
 * <p>The extension runs
 * {@code adb shell content query --uri content://org.autojs.autojspro.debug.provider/debug-server}
 * and parses {@code state=(\d+)(?:, type=([^,]+), port=(\d+))?} from a single row;
 * it only proceeds when {@code state == 2}, then forwards a local port to
 * {@code tcp:<port>} (9317 for the legacy type) and connects.
 *
 * <p>One provider serves two hard-coded authorities:
 * {@code org.autojs.autojspro.debug.provider} (legacy, our 9317 protocol) and
 * {@code com.boyai.auma.debug.provider} (Pro 9.3.11 ws-rpc). The legacy row reports
 * {@link DevPluginServer.ServerState} ordinal + {@code type=legacy} + port 9317;
 * the ws-rpc row reports the endpoint registered through {@link WsRpcEndpoint}.
 */
public class DebugServerProvider extends ContentProvider {

    /** 旧 Pro 包名 authority；自研扩展与旧官方扩展都写死它，对应我们的 legacy 协议。 */
    public static final String AUTHORITY = "org.autojs.autojspro.debug.provider";
    /** 新 Pro（com.boyai.auma）的 authority，对应 ws-rpc 协议（清单里与旧 authority 一起声明）。 */
    public static final String AUTHORITY_WS_RPC = "com.boyai.auma.debug.provider";
    public static final String TYPE_LEGACY = "legacy";
    public static final String TYPE_WS_RPC = "ws-rpc";

    /**
     * ws-rpc 通道（A 任务）向本 provider 注册状态/端口的钩子。
     *
     * <p>两个通道用不同 authority 区分：{@link #AUTHORITY} 永远返回 legacy + 9317；
     * {@link #AUTHORITY_WS_RPC} 返回这里注册的 ws-rpc 状态与动态端口。与真 Pro 同机安装
     * 会 provider 冲突，用 {@code -PdevPluginUsbDiscovery=false} 构建时清单覆盖层会按
     * provider 名把两个 authority 一起去掉。
     */
    public interface WsRpcEndpoint {
        /** 0=DISCONNECTED, 1=CONNECTING, 2=LISTENING。 */
        int stateOrdinal();

        /** 真实监听端口；未监听返回 0。 */
        int port();
    }

    private static volatile WsRpcEndpoint sWsRpcEndpoint;

    public static void setWsRpcEndpoint(WsRpcEndpoint endpoint) {
        sWsRpcEndpoint = endpoint;
    }

    private static final String[] COLUMNS = {"state", "type", "port"};

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        String authority = uri == null ? null : uri.getAuthority();
        if (AUTHORITY_WS_RPC.equals(authority)) {
            WsRpcEndpoint endpoint = sWsRpcEndpoint;
            cursor.addRow(new Object[]{
                    String.valueOf(endpoint == null ? 0 : endpoint.stateOrdinal()),
                    TYPE_WS_RPC,
                    String.valueOf(endpoint == null ? 0 : endpoint.port())
            });
            return cursor;
        }
        DevPluginServer.ServerState state = DevPluginServer.getInstance().getServerState();
        cursor.addRow(new Object[]{
                // 扩展只看 state == 2；用 ordinal 与 Pro 的 0/1/2 对齐。
                String.valueOf(state.ordinal()),
                TYPE_LEGACY,
                // 未监听时端口没有意义；Pro 也返回 0。
                String.valueOf(state == DevPluginServer.ServerState.LISTENING
                        ? DevPluginServer.PORT : 0)
        });
        return cursor;
    }

    @Nullable
    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}

package com.jdkshen.aijspro.pluginclient;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashSet;
import java.util.Set;

/**
 * Pro ws-rpc 通道的 JSON-RPC 2.0 分发器（每连接一个）。
 *
 * <p>与反编译出的 {@code DebugRpcService} / {@code JsonRpcBasicServer} /
 * {@code DeveloperModeAuthorizer} 的语义对齐：
 * <ul>
 *   <li>{@code method} 按 {@code '.'} 切成「命名空间.方法名」，目前只注册
 *       {@code debug}（{@code vfs} / {@code rsync} 未实现，返回 -32601）；</li>
 *   <li>第一个调用应当是 {@code debug.authorize}，参数 {@code params.token}；</li>
 *   <li>之后的<b>每个</b>请求都要带<b>顶层</b> {@code token}（不在 params 里）；
 *       缺失/非字符串 → {@code {code:2, message:"no token field or token is not a string"}}；
 *       不在本连接已授权集合 → {@code {code:3, message:"Forbidden"}}；</li>
 *   <li>用户拒绝/20 秒超时 → {@code {code:3, message:"user rejected"}}；</li>
 *   <li>params 不是对象 → {@code {code:2, message:"params should be a object"}}。</li>
 * </ul>
 *
 * <p>不依赖 Android：真正的设备能力通过 {@link Host} 注入，单测可以用假 Host 跑通
 * 整条 JSON-RPC 链路。
 */
public final class ProRpcDispatcher {

    public static final String JSON_RPC_VERSION = "2.0";
    /** 与 Pro 对齐：authorize 返回的协议版本。 */
    public static final int PROTOCOL_VERSION = 1;

    /** Pro 自定义错误码：参数/令牌字段问题。 */
    public static final int CODE_PARAMS = 2;
    public static final int CODE_TOKEN = 2;
    /** Pro 自定义错误码：没有权限（token 未授权）。 */
    public static final int CODE_FORBIDDEN = 3;
    /** Pro 自定义错误码：用户拒绝（或 20s 超时）。 */
    public static final int CODE_USER_REJECTED = 3;

    public static final int CODE_INVALID_REQUEST = -32600;
    public static final int CODE_METHOD_NOT_FOUND = -32601;
    public static final int CODE_INTERNAL_ERROR = -32603;
    public static final int CODE_PARSE_ERROR = -32700;

    /** 设备侧能力注入点；由 {@code ProRpcServer} 用 Android 实现，单测用假实现。 */
    public interface Host {

        String deviceName();

        String deviceId();

        String uuid();

        String appVersion();

        long appVersionCode();

        /** 弹确认框/查已信任设备；返回 false 表示用户拒绝或超时。 */
        boolean authorize(String token);

        JsonObject inspectLayout(JsonObject params) throws Exception;

        JsonObject listFiles(JsonObject params) throws Exception;

        JsonObject runFile(JsonObject params) throws Exception;

        JsonObject runProject(JsonObject params) throws Exception;

        void stop(JsonObject params) throws Exception;

        void stopAll() throws Exception;
    }

    /** 带 Pro 错误码的异常：分发器把它原样翻成 JSON-RPC error。 */
    public static final class RpcException extends Exception {

        private static final long serialVersionUID = 1L;

        public final int code;

        public RpcException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private final Host mHost;
    /** 本连接已授权的 token（Pro 的 {@code DebugRpcService.f19003: Set<String>}）。 */
    private final Set<String> mAuthorizedTokens = new HashSet<>();

    public ProRpcDispatcher(Host host) {
        if (host == null) {
            throw new IllegalArgumentException("host == null");
        }
        mHost = host;
    }

    /** 处理一条 JSON 文本；返回 null 表示这是通知（没有 id），不需要回包。 */
    public JsonObject handleText(String text) {
        JsonElement parsed;
        try {
            parsed = new JsonParser().parse(text);
        } catch (RuntimeException error) {
            return errorResponse(JsonNull.INSTANCE, CODE_PARAMS, "params should be a object");
        }
        if (parsed == null || !parsed.isJsonObject()) {
            return errorResponse(JsonNull.INSTANCE, CODE_PARAMS, "params should be a object");
        }
        return handle(parsed.getAsJsonObject());
    }

    /**
     * 处理一条 JSON-RPC 请求。
     *
     * @return 响应；请求没有 {@code id}（通知）时返回 {@code null}
     */
    public JsonObject handle(JsonObject request) {
        JsonElement idElement = request.get("id");
        boolean notification = idElement == null || idElement.isJsonNull();
        JsonElement id = notification ? JsonNull.INSTANCE : idElement;

        JsonElement methodElement = request.get("method");
        if (methodElement == null || !methodElement.isJsonPrimitive()
                || !methodElement.getAsJsonPrimitive().isString()) {
            return notification ? null
                    : errorResponse(id, CODE_INVALID_REQUEST, "method should be a string");
        }
        String method = methodElement.getAsString();
        int dot = method.indexOf('.');
        if (dot <= 0 || dot == method.length() - 1) {
            return notification ? null
                    : errorResponse(id, CODE_METHOD_NOT_FOUND, "Method not found: " + method);
        }
        String namespace = method.substring(0, dot);
        String name = method.substring(dot + 1);

        JsonElement paramsElement = request.get("params");
        JsonObject params = null;
        if (paramsElement != null && !paramsElement.isJsonNull()) {
            if (!paramsElement.isJsonObject()) {
                return notification ? null
                        : errorResponse(id, CODE_PARAMS, "params should be a object");
            }
            params = paramsElement.getAsJsonObject();
        }

        boolean authorizeCall = "debug".equals(namespace) && "authorize".equals(name);
        if (!authorizeCall) {
            JsonElement tokenElement = request.get("token");
            if (tokenElement == null || tokenElement.isJsonNull()
                    || !tokenElement.isJsonPrimitive()
                    || !tokenElement.getAsJsonPrimitive().isString()) {
                return notification ? null
                        : errorResponse(id, CODE_TOKEN, "no token field or token is not a string");
            }
            if (!mAuthorizedTokens.contains(tokenElement.getAsString())) {
                return notification ? null
                        : errorResponse(id, CODE_FORBIDDEN, "Forbidden");
            }
        }

        try {
            JsonObject result = invoke(namespace, name, params);
            return notification ? null : successResponse(id, result);
        } catch (RpcException error) {
            return notification ? null : errorResponse(id, error.code, error.getMessage());
        } catch (Throwable error) {
            return notification ? null
                    : errorResponse(id, CODE_INTERNAL_ERROR, messageOf(error));
        }
    }

    private JsonObject invoke(String namespace, String name, JsonObject params) throws Exception {
        if (!"debug".equals(namespace)) {
            throw new RpcException(CODE_METHOD_NOT_FOUND, "Method not found: " + namespace + "." + name);
        }
        switch (name) {
            case "authorize":
                return authorize(params);
            case "inspectLayout":
                return mHost.inspectLayout(params);
            case "listFiles":
                return mHost.listFiles(params);
            case "runFile":
                return mHost.runFile(params);
            case "runProject":
                return mHost.runProject(params);
            case "stop":
                mHost.stop(params);
                return null;
            case "stopAll":
                mHost.stopAll();
                return null;
            default:
                throw new RpcException(CODE_METHOD_NOT_FOUND, "Method not found: debug." + name);
        }
    }

    private JsonObject authorize(JsonObject params) throws RpcException {
        if (params == null) {
            throw new RpcException(CODE_PARAMS, "params should be a object");
        }
        String token = stringOf(params, "token");
        if (token == null || token.isEmpty()) {
            throw new RpcException(CODE_PARAMS, "token should be a string");
        }
        if (!mAuthorizedTokens.contains(token)) {
            if (!mHost.authorize(token)) {
                throw new RpcException(CODE_USER_REJECTED, "user rejected");
            }
            mAuthorizedTokens.add(token);
        }
        JsonObject info = new JsonObject();
        info.addProperty("deviceName", mHost.deviceName());
        info.addProperty("deviceId", mHost.deviceId());
        info.addProperty("uuid", mHost.uuid());
        info.addProperty("appVersion", mHost.appVersion());
        info.addProperty("appVersionCode", mHost.appVersionCode());
        info.addProperty("protocolVersion", PROTOCOL_VERSION);
        return info;
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.toString() : message;
    }

    public static String stringOf(JsonObject object, String name) {
        if (object == null || !object.has(name)) {
            return null;
        }
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        return element.getAsString();
    }

    public static JsonObject successResponse(JsonElement id, JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", JSON_RPC_VERSION);
        response.add("id", id == null ? JsonNull.INSTANCE : id);
        response.add("result", result == null ? JsonNull.INSTANCE : result);
        return response;
    }

    public static JsonObject errorResponse(JsonElement id, int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message == null ? "" : message);
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", JSON_RPC_VERSION);
        response.add("id", id == null ? JsonNull.INSTANCE : id);
        response.add("error", error);
        return response;
    }

    /** 本连接当前已授权的 token 快照（调试/测试用）。 */
    public Set<String> authorizedTokens() {
        return new HashSet<>(mAuthorizedTokens);
    }
}

package wendu.dsbridge;

import android.os.Build;
import android.util.Log;
import android.webkit.JavascriptInterface;

import androidx.annotation.Keep;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class DsBridge {
    static final String LOG_TAG = "dsBridge";
    static boolean isDebug = false;

    private final DWebView webview;
    private final Map<String, Object> interfaces = new HashMap<>();

    private final Map<Integer, OnReturnValue> handlerMap = new HashMap<>();
    private ArrayList<Call> calls;
    private final Object dsbObject = new Object() {

        @Keep
        @JavascriptInterface
        public boolean hasNativeMethod(Object args) throws JSONException {
            JSONObject jsonObject = (JSONObject) args;
            String name = jsonObject.getString("name").trim();
            String type = jsonObject.getString("type").trim();
            String[] nameStr = parseMethod(name);
            Object jsb = interfaces.get(nameStr[0]);

            if (jsb != null) {
                Class<?> cls = jsb.getClass();
                boolean async = false;
                Method method = null;
                try {
                    method = cls.getMethod(nameStr[1], Object.class, CompletionHandler.class);
                    async = true;
                } catch (Exception e) {
                    try {
                        method = cls.getMethod(nameStr[1], Object.class);
                    } catch (Exception ignored) {
                    }
                }
                if (method != null) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
                        JavascriptInterface annotation = method.getAnnotation(JavascriptInterface.class);
                        if (annotation == null) {
                            return false;
                        }
                    }
                    return "all".equals(type) || (async && "asyn".equals(type) || (!async && "syn".equals(type)));
                }
            }
            return false;
        }

        @Keep
        @JavascriptInterface
        public String closePage(Object object) throws JSONException {
            return webview.closePage(object);
        }

        @Keep
        @JavascriptInterface
        public void disableJavascriptDialogBlock(Object object) throws JSONException {
            webview.disableJavascriptDialogBlock(((JSONObject) object).getBoolean("disable"));
        }

        @Keep
        @JavascriptInterface
        public void dsinit(Object jsonObject) {
            webview.runOnMainThread(new Runnable() {
                @Override
                public void run() {
                    dispatchStartupQueue();
                }
            });
        }

        @Keep
        @JavascriptInterface
        public void returnValue(final Object obj) {
            webview.runOnMainThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        JSONObject jsonObj = (JSONObject) obj;
                        int id = jsonObj.getInt("id");
                        OnReturnValue handler = handlerMap.get(id);
                        if (handler != null) {
                            handler.onValue(jsonObj.opt("data"));
                            if (jsonObj.getBoolean("complete")) {
                                handlerMap.remove(id);
                            }
                        }
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
            });
        }
    };
    private int callID = 0;

    DsBridge(DWebView webview) {
        this.webview = webview;
        addJavascriptObject(dsbObject, "_dsb");
    }

    private void PrintDebugInfo(String error) {
        if (isDebug) {
            Log.e(LOG_TAG, "dsBridge DEBUG ERR MSG:\\n" + error.replaceAll("'", "\\\\'"));
        }
    }

    @Keep
    @JavascriptInterface
    public String call(String methodName, String argStr) {
        String[] nameStr = parseMethod(methodName.trim());
        methodName = nameStr[1];
        Object jsb = interfaces.get(nameStr[0]);
        if (jsb == null) {
            PrintDebugInfo("Js bridge called, but can't find a corresponded JavascriptInterface object , please check your code!");
            return errorRet(-1);
        }

        Object arg = null;
        Method method = null;
        String callback = null;

        try {
            JSONObject args = new JSONObject(argStr);
            if (args.has("_dscbstub")) {
                callback = args.getString("_dscbstub");
            }
            if (args.has("data")) {
                arg = args.get("data");
            }
        } catch (JSONException e) {
            PrintDebugInfo(String.format("The argument of \"%s\" must be a JSON object string!", methodName));
            return errorRet(-1);
        }

        Class<?> cls = jsb.getClass();
        boolean async = false;
        try {
            method = cls.getMethod(methodName, Object.class, CompletionHandler.class);
            async = true;
        } catch (Exception e) {
            try {
                method = cls.getMethod(methodName, Object.class);
            } catch (Exception ignored) {
            }
        }

        if (method == null) {
            PrintDebugInfo(String.format("Not find method \"%s\" implementation! please check if the  signature or namespace of the method is right ", methodName));
            return errorRet(-1);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            if (method.getAnnotation(JavascriptInterface.class) == null) {
                PrintDebugInfo(String.format("Method %s is not invoked, since it is not declared with JavascriptInterface annotation! ", methodName));
                return errorRet(-1);
            }
        }

        method.setAccessible(true);
        try {
            if (async) {
                final String cb = callback;
                method.invoke(jsb, arg, new CompletionHandler() {

                    @Override
                    public void complete(Object retValue) {
                        complete(retValue, true);
                    }

                    @Override
                    public void complete() {
                        complete(null, true);
                    }

                    @Override
                    public void setProgressData(Object value) {
                        complete(value, false);
                    }

                    private void complete(Object retValue, boolean complete) {
                        try {
                            if (cb != null) {
                                String script = String.format("%s(%s.data);", cb, successRet(retValue));
                                if (complete) {
                                    script += String.format("delete window.%s", cb);
                                }
                                webview.evaluateJavascript(script);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                });
            } else {
                return successRet(method.invoke(jsb, arg));
            }
        } catch (Exception e) {
            PrintDebugInfo(String.format("Call failed：The parameter of \"%s\" in Java is invalid.", methodName));
            return errorRet(-1);
        }
        return errorRet(-1);
    }

    private String errorRet(int code) {
        return String.format("{\"code\":%d}", code);
    }

    private String successRet(Object data) throws JSONException {
        JSONObject jo = new JSONObject();
        jo.put("code", 0);
        jo.put("data", data);
        return jo.toString();
    }

    private String[] parseMethod(String method) {
        int pos = method.lastIndexOf('.');
        String namespace = "";
        if (pos != -1) {
            namespace = method.substring(0, pos);
            method = method.substring(pos + 1);
        }
        return new String[]{namespace, method};
    }

    /**
     * Add a java object which implemented the javascript interfaces to dsBridge with namespace.
     * Remove the object using {@link #removeJavascriptObject(String) removeJavascriptObject(String)}
     *
     * @param object
     * @param namespace if empty, the object have no namespace.
     */
    public void addJavascriptObject(Object object, String namespace) {
        if (namespace == null) {
            namespace = "";
        }
        if (object != null) {
            interfaces.put(namespace, object);
        }
    }

    /**
     * remove the javascript object with supplied namespace.
     *
     * @param namespace
     */
    public void removeJavascriptObject(String namespace) {
        if (namespace == null) {
            namespace = "";
        }
        interfaces.remove(namespace);
    }

    public void resetCalls() {
        calls = new ArrayList<>();
    }

    public <T> void callHandler(String method, Object[] args, OnReturnValue<T> handler) {
        Call callInfo = new Call(method, ++callID, args);
        if (handler != null) {
            handlerMap.put(callInfo.callbackId, handler);
        }

        if (calls != null) {
            calls.add(callInfo);
        } else {
            dispatchJavascriptCall(callInfo);
        }
    }

    private synchronized void dispatchStartupQueue() {
        if (calls != null) {
            for (Call info : calls) {
                dispatchJavascriptCall(info);
            }
            calls = null;
        }
    }

    private void dispatchJavascriptCall(Call info) {
        webview.evaluateJavascript(String.format("window._handleMessageFromNative(%s)", info.toString()));
    }

    private static class Call {
        private final String data;
        private final int callbackId;
        private final String method;

        Call(String handlerName, int id, Object[] args) {
            if (args == null) args = new Object[0];
            data = new JSONArray(Arrays.asList(args)).toString();
            callbackId = id;
            method = handlerName;
        }

        @Override
        public String toString() {
            JSONObject jo = new JSONObject();
            try {
                jo.put("method", method);
                jo.put("callbackId", callbackId);
                jo.put("data", data);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return jo.toString();
        }
    }
}



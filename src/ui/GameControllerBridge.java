package ui;

import com.sun.jna.Function;
import com.sun.jna.Pointer;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Small macOS GameController.framework bridge that feeds the browser-side
 * Gamepad API shim. It is deliberately best-effort: if the framework or a
 * selector is unavailable, Rigel falls back to the embedded WebKit behavior.
 */
final class GameControllerBridge {
    private static final String GAME_CONTROLLER_FRAMEWORK =
            "/System/Library/Frameworks/GameController.framework/GameController";

    private final Function objcMsgSend;
    private final Function selRegisterName;
    private final Pointer gcControllerClass;
    private final Map<String, Pointer> selectors = new HashMap<>();
    private boolean warnedUnavailable = false;
    private boolean runtimeConfigured = false;
    private volatile String faceButtonLayout = "standard";

    GameControllerBridge() {
        Function msg = null;
        Function sel = null;
        Pointer cls = null;

        try {
            com.sun.jna.NativeLibrary.getInstance(GAME_CONTROLLER_FRAMEWORK);
            com.sun.jna.NativeLibrary objc = com.sun.jna.NativeLibrary.getInstance("objc");
            Function getClass = objc.getFunction("objc_getClass");
            sel = objc.getFunction("sel_registerName");
            msg = objc.getFunction("objc_msgSend");
            cls = (Pointer)getClass.invoke(Pointer.class, new Object[]{"GCController"});
        } catch (Throwable ignored) {
            msg = null;
            sel = null;
            cls = null;
        }

        objcMsgSend = msg;
        selRegisterName = sel;
        gcControllerClass = cls;
        configureControllerRuntime();
    }

    void setFaceButtonLayout(String layout) {
        String clean = layout == null ? "" : layout.trim().toLowerCase(Locale.ROOT);
        faceButtonLayout = clean.equals("nintendo") ? "nintendo" : "standard";
    }

    String snapshotJson() {
        if (!isAvailable()) return "[]";

        try {
            Pointer controllers = sendPointer(gcControllerClass, "controllers");
            long count = controllers == null ? 0 : sendLong(controllers, "count");
            if (count <= 0) return "[]";

            StringBuilder json = new StringBuilder(512);
            json.append('[');
            int emitted = 0;

            for (long i = 0; i < count && emitted < 8; i++) {
                Pointer controller = sendPointer(controllers, "objectAtIndex:", i);
                Pointer profile = firstObject(controller, "extendedGamepad", "gamepad", "microGamepad", "physicalInputProfile");
                if (controller == null || profile == null) continue;

                if (emitted > 0) json.append(',');
                appendController(json, controller, profile, emitted);
                emitted++;
            }

            json.append(']');
            return json.toString();
        } catch (Throwable ex) {
            if (!warnedUnavailable) {
                System.err.println("[Rigel] GameController bridge disabled: " + ex.getMessage());
                warnedUnavailable = true;
            }
            return "[]";
        }
    }

    private boolean isAvailable() {
        return objcMsgSend != null && selRegisterName != null && gcControllerClass != null;
    }

    private void configureControllerRuntime() {
        if (runtimeConfigured || !isAvailable()) return;
        runtimeConfigured = true;
        try {
            if (responds(gcControllerClass, "setShouldMonitorBackgroundEvents:")) {
                sendVoid(gcControllerClass, "setShouldMonitorBackgroundEvents:", (byte)1);
            }
        } catch (Throwable ignored) {}
        try {
            if (responds(gcControllerClass, "startWirelessControllerDiscoveryWithCompletionHandler:")) {
                sendVoid(gcControllerClass, "startWirelessControllerDiscoveryWithCompletionHandler:", Pointer.NULL);
            }
        } catch (Throwable ignored) {}
    }

    private void appendController(StringBuilder json, Pointer controller, Pointer profile, int index) {
        Pointer dpad = getObject(profile, "dpad");
        Pointer left = getObject(profile, "leftThumbstick");
        Pointer right = getObject(profile, "rightThumbstick");

        Pointer primary = left != null ? left : dpad;
        float lx = axis(primary, "xAxis");
        float ly = axis(primary, "yAxis");
        float rx = axis(right, "xAxis");
        float ry = axis(right, "yAxis");
        float dx = axis(dpad, "xAxis");
        float dy = axis(dpad, "yAxis");

        String name = firstString(controller, "vendorName", "productCategory", "description");
        if (name == null || name.isBlank()) name = "Rigel Controller";
        String product = firstString(controller, "productCategory");
        double timestamp = responds(profile, "lastEventTimestamp") ? sendDouble(profile, "lastEventTimestamp") : 0d;

        json.append('{');
        json.append("\"id\":\"").append(escape(name)).append("\",");
        json.append("\"index\":").append(index).append(',');
        json.append("\"mapping\":\"standard\",");
        json.append("\"connected\":true,");
        if (product != null && !product.isBlank()) {
            json.append("\"product\":\"").append(escape(product)).append("\",");
        }
        json.append("\"timestamp\":").append(rounded(timestamp, 1000d)).append(',');
        json.append("\"axes\":[")
            .append(num(lx)).append(',')
            .append(num(-ly)).append(',')
            .append(num(rx)).append(',')
            .append(num(-ry)).append("],");
        json.append("\"buttons\":[");

        boolean first = true;
        ButtonState buttonA = buttonState(profile, "buttonA");
        ButtonState buttonB = buttonState(profile, "buttonB");
        ButtonState buttonX = buttonState(profile, "buttonX");
        ButtonState buttonY = buttonState(profile, "buttonY");
        if ("nintendo".equals(faceButtonLayout)) {
            first = appendButton(json, first, buttonB);
            first = appendButton(json, first, buttonA);
            first = appendButton(json, first, buttonY);
            first = appendButton(json, first, buttonX);
        } else {
            first = appendButton(json, first, buttonA);
            first = appendButton(json, first, buttonB);
            first = appendButton(json, first, buttonX);
            first = appendButton(json, first, buttonY);
        }
        first = appendButton(json, first, firstButtonState(profile, "leftShoulder", "leftBumper"));
        first = appendButton(json, first, firstButtonState(profile, "rightShoulder", "rightBumper"));
        first = appendButton(json, first, buttonState(profile, "leftTrigger"));
        first = appendButton(json, first, buttonState(profile, "rightTrigger"));
        first = appendButton(json, first, firstButtonState(profile, "buttonOptions", "buttonShare", "selectButton"));
        first = appendButton(json, first, firstButtonState(profile, "buttonMenu", "startButton"));
        first = appendButton(json, first, buttonState(profile, "leftThumbstickButton"));
        first = appendButton(json, first, buttonState(profile, "rightThumbstickButton"));
        first = appendButton(json, first, dy > 0.5f ? ButtonState.fromAxis(dy) : ButtonState.ZERO);
        first = appendButton(json, first, dy < -0.5f ? ButtonState.fromAxis(-dy) : ButtonState.ZERO);
        first = appendButton(json, first, dx < -0.5f ? ButtonState.fromAxis(-dx) : ButtonState.ZERO);
        first = appendButton(json, first, dx > 0.5f ? ButtonState.fromAxis(dx) : ButtonState.ZERO);
        first = appendButton(json, first, buttonState(profile, "buttonHome"));
        first = appendButton(json, first, buttonState(profile, "touchpadButton"));
        first = appendButton(json, first, buttonState(profile, "paddleButton1"));
        first = appendButton(json, first, buttonState(profile, "paddleButton2"));
        first = appendButton(json, first, buttonState(profile, "paddleButton3"));
        appendButton(json, first, buttonState(profile, "paddleButton4"));

        json.append("]}");
    }

    private Pointer firstObject(Pointer receiver, String... names) {
        for (String name : names) {
            Pointer value = getObject(receiver, name);
            if (value != null) return value;
        }
        return null;
    }

    private String firstString(Pointer receiver, String... names) {
        for (String name : names) {
            String value = getString(receiver, name);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private ButtonState firstButtonState(Pointer profile, String... selectors) {
        for (String selector : selectors) {
            Pointer button = getObject(profile, selector);
            if (button != null) return buttonState(button);
        }
        return ButtonState.ZERO;
    }

    private ButtonState buttonState(Pointer profile, String selector) {
        Pointer button = getObject(profile, selector);
        if (button == null) return ButtonState.ZERO;
        return buttonState(button);
    }

    private ButtonState buttonState(Pointer button) {
        float value = responds(button, "value") ? sendFloat(button, "value") : 0f;
        if ((responds(button, "isPressed") && sendBool(button, "isPressed")) ||
            (responds(button, "pressed") && sendBool(button, "pressed"))) {
            value = Math.max(value, 1f);
        }
        boolean touched = value > 0f ||
                (responds(button, "isTouched") && sendBool(button, "isTouched")) ||
                (responds(button, "touched") && sendBool(button, "touched"));
        return new ButtonState(clamp(value), touched);
    }

    private float axis(Pointer pad, String selector) {
        Pointer axis = getObject(pad, selector);
        if (axis == null || !responds(axis, "value")) return 0f;
        return clamp(sendFloat(axis, "value"));
    }

    private Pointer getObject(Pointer receiver, String selector) {
        if (receiver == null || !responds(receiver, selector)) return null;
        return sendPointer(receiver, selector);
    }

    private String getString(Pointer receiver, String selector) {
        Pointer nsString = getObject(receiver, selector);
        if (nsString == null || !responds(nsString, "UTF8String")) return null;
        Pointer utf8 = sendPointer(nsString, "UTF8String");
        return utf8 == null ? null : utf8.getString(0, "UTF-8");
    }

    private boolean appendButton(StringBuilder json, boolean first, ButtonState state) {
        if (!first) json.append(',');
        float value = clamp(state.value);
        json.append("{\"pressed\":").append(value > 0.5f)
            .append(",\"touched\":").append(state.touched || value > 0f)
            .append(",\"value\":").append(num(value)).append('}');
        return false;
    }

    private boolean responds(Pointer receiver, String selector) {
        if (receiver == null) return false;
        return sendBool(receiver, "respondsToSelector:", sel(selector));
    }

    private Pointer sel(String name) {
        Pointer cached = selectors.get(name);
        if (cached != null) return cached;
        Pointer selector = (Pointer)selRegisterName.invoke(Pointer.class, new Object[]{name});
        selectors.put(name, selector);
        return selector;
    }

    private Object[] args(Pointer receiver, String selector, Object... extra) {
        Object[] args = new Object[2 + extra.length];
        args[0] = receiver;
        args[1] = sel(selector);
        System.arraycopy(extra, 0, args, 2, extra.length);
        return args;
    }

    private Pointer sendPointer(Pointer receiver, String selector, Object... extra) {
        return (Pointer)objcMsgSend.invoke(Pointer.class, args(receiver, selector, extra));
    }

    private long sendLong(Pointer receiver, String selector, Object... extra) {
        Object value = objcMsgSend.invoke(Long.class, args(receiver, selector, extra));
        return value instanceof Number ? ((Number)value).longValue() : 0L;
    }

    private boolean sendBool(Pointer receiver, String selector, Object... extra) {
        Object value = objcMsgSend.invoke(Byte.class, args(receiver, selector, extra));
        if (value instanceof Boolean) return (Boolean)value;
        return value instanceof Number && ((Number)value).byteValue() != 0;
    }

    private void sendVoid(Pointer receiver, String selector, Object... extra) {
        objcMsgSend.invoke(Void.class, args(receiver, selector, extra));
    }

    private float sendFloat(Pointer receiver, String selector, Object... extra) {
        Object value = objcMsgSend.invoke(Float.class, args(receiver, selector, extra));
        return value instanceof Number ? ((Number)value).floatValue() : 0f;
    }

    private double sendDouble(Pointer receiver, String selector, Object... extra) {
        Object value = objcMsgSend.invoke(Double.class, args(receiver, selector, extra));
        return value instanceof Number ? ((Number)value).doubleValue() : 0d;
    }

    private static float clamp(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return 0f;
        return Math.max(-1f, Math.min(1f, v));
    }

    private static String num(float v) {
        return Float.toString(Math.round(v * 10_000f) / 10_000f);
    }

    private static String rounded(double value, double scale) {
        return Double.toString(Math.rint(value * scale) / scale);
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private record ButtonState(float value, boolean touched) {
        static final ButtonState ZERO = new ButtonState(0f, false);

        static ButtonState fromAxis(float value) {
            value = clamp(value);
            return new ButtonState(value, value > 0.5f);
        }
    }
}

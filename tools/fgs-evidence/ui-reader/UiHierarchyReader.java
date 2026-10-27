package org.samouraiworld.wesh.tools;

import android.app.UiAutomation;
import android.graphics.Rect;
import android.util.Xml;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.regex.Pattern;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/** One-shot shell reader for a fresh platform hierarchy without an idle wait. */
public final class UiHierarchyReader {
    private static final String WRAPPER =
            "com.android.uiautomator.core.UiAutomationShellWrapper";
    private static final String DUMPER =
            "com.android.uiautomator.core.AccessibilityNodeInfoDumper";
    private static final Pattern OWNED_OUTPUT = Pattern.compile(
            "/data/local/tmp/wesh-fgs-window-[ab]-[0-9a-f]{16}-[0-9]{8}\\.xml");
    private static final long MAX_XML_BYTES = 8L * 1024L * 1024L;

    private static final int OK = 0;
    private static final int ARGUMENT_ERROR = 20;
    private static final int BRIDGE_ERROR = 21;
    private static final int API_ERROR = 22;
    private static final int ROOT_NULL = 23;
    private static final int DUMP_ERROR = 24;
    private static final int OUTPUT_ERROR = 25;
    private static final int DISCONNECT_ERROR = 26;

    private UiHierarchyReader() {}

    public static void main(String[] args) {
        int result = run(args);
        System.exit(result);
    }

    static int run(String[] args) {
        if (args.length != 1 || !OWNED_OUTPUT.matcher(args[0]).matches()) {
            emit("WESH_UI_READER_ARGUMENT_ERROR", true);
            return ARGUMENT_ERROR;
        }

        Object wrapper = null;
        Method disconnect = null;
        int result = OK;
        try {
            File output = new File(args[0]);
            if (output.exists()) {
                emit("WESH_UI_READER_OUTPUT_ERROR", true);
                result = OUTPUT_ERROR;
            } else {
                Class<?> wrapperClass = Class.forName(WRAPPER);
                wrapper = wrapperClass.getConstructor().newInstance();
                Method connect = wrapperClass.getMethod("connect");
                Method compressed = wrapperClass.getMethod(
                        "setCompressedLayoutHierarchy", boolean.class);
                Method getAutomation = wrapperClass.getMethod("getUiAutomation");
                disconnect = wrapperClass.getMethod("disconnect");
                connect.invoke(wrapper);
                compressed.invoke(wrapper, false);
                Object automationObject = getAutomation.invoke(wrapper);
                if (!(automationObject instanceof UiAutomation)) {
                    emit("WESH_UI_READER_BRIDGE_ERROR", true);
                    result = BRIDGE_ERROR;
                } else {
                    result = dump((UiAutomation) automationObject, output);
                }
            }
        } catch (ClassNotFoundException | NoSuchMethodException error) {
            emit("WESH_UI_READER_API_ERROR", true);
            result = API_ERROR;
        } catch (ReflectiveOperationException | SecurityException error) {
            emit("WESH_UI_READER_BRIDGE_ERROR", true);
            result = BRIDGE_ERROR;
        } catch (RuntimeException error) {
            emit("WESH_UI_READER_BRIDGE_ERROR", true);
            result = BRIDGE_ERROR;
        } finally {
            if (wrapper != null && disconnect != null) {
                try {
                    disconnect.invoke(wrapper);
                } catch (IllegalAccessException | InvocationTargetException
                        | RuntimeException error) {
                    if (result == OK) {
                        emit("WESH_UI_READER_DISCONNECT_ERROR", true);
                        result = DISCONNECT_ERROR;
                    }
                }
            }
        }
        return result;
    }

    private static int dump(UiAutomation automation, File output) {
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        if (root == null) {
            emit("WESH_UI_READER_ROOT_NULL", true);
            return ROOT_NULL;
        }
        try {
            int[] dimensions = dimensions(root);
            if (dimensions[0] <= 0 || dimensions[1] <= 0) {
                emit("WESH_UI_READER_DUMP_ERROR", true);
                return DUMP_ERROR;
            }
            Class<?> dumper = Class.forName(DUMPER);
            Method dump = dumper.getMethod(
                    "dumpWindowToFile", AccessibilityNodeInfo.class, File.class,
                    int.class, int.class, int.class);
            dump.invoke(null, root, output, 0, dimensions[0], dimensions[1]);
            if (!validOutput(output)) {
                output.delete();
                emit("WESH_UI_READER_OUTPUT_ERROR", true);
                return OUTPUT_ERROR;
            }
            emit("WESH_UI_READER_OK", false);
            return OK;
        } catch (ClassNotFoundException | NoSuchMethodException error) {
            output.delete();
            emit("WESH_UI_READER_API_ERROR", true);
            return API_ERROR;
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException error) {
            output.delete();
            emit("WESH_UI_READER_DUMP_ERROR", true);
            return DUMP_ERROR;
        } finally {
            try {
                root.recycle();
            } catch (RuntimeException ignored) {
                // One-shot process termination releases the shell-side reference.
            }
        }
    }

    private static int[] dimensions(AccessibilityNodeInfo root) {
        int width = 0;
        int height = 0;
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            AccessibilityNodeInfo node = pending.removeFirst();
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            width = Math.max(width, bounds.right);
            height = Math.max(height, bounds.bottom);
            int children = node.getChildCount();
            for (int index = 0; index < children; index++) {
                AccessibilityNodeInfo child = node.getChild(index);
                if (child != null) {
                    pending.addLast(child);
                }
            }
            if (node != root) {
                node.recycle();
            }
        }
        return new int[] {width, height};
    }

    private static boolean validOutput(File output) {
        long length = output.length();
        if (!output.isFile() || length <= 0 || length > MAX_XML_BYTES) {
            return false;
        }
        try (FileInputStream stream = new FileInputStream(output)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, "UTF-8");
            boolean rootSeen = false;
            boolean nodeSeen = false;
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    if (!rootSeen) {
                        if (!"hierarchy".equals(parser.getName()) || parser.getDepth() != 1) {
                            return false;
                        }
                        rootSeen = true;
                    } else if ("node".equals(parser.getName())) {
                        nodeSeen = true;
                    }
                }
            }
            return rootSeen && nodeSeen;
        } catch (IOException | XmlPullParserException error) {
            return false;
        }
    }

    private static void emit(String value, boolean error) {
        if (error) {
            System.err.println(value);
        } else {
            System.out.println(value);
        }
    }
}

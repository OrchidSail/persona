package persona.platform.phonetools.control.shell;

import android.view.Surface;

// Resident uid-2000 shell service hosting Persona background virtual display and targeted input injection.
interface IPhoneControlShell {
    int createDisplay(in Surface surface, int width, int height, int dpi) = 1;
    void releaseDisplay(int displayId) = 2;
    // 0=OK, 1=IN_USE_ON_MAIN, 2=NOT_FOUND, 3=ERROR
    int startApp(int displayId, String packageName) = 3;
    int topTaskId(int displayId) = 4;
    String topTaskPackage(int displayId) = 5;
    boolean moveTask(int taskId, int displayId) = 6;
    boolean injectTap(int displayId, int x, int y) = 7;
    boolean injectStroke(int displayId, in int[] xs, in int[] ys, in long[] times) = 8;
    boolean injectKey(int displayId, int keyCode) = 9;
    int[] identity() = 10;
    void releaseAll() = 11;
    void destroy() = 12;
    // Plan C Phase 1: returns "taskId|packageName|isHome(0/1)" for Display 0 top task, or null.
    String captureMainForegroundTask() = 13;
    // Plan C Phase 1: restores main-screen task by taskId (>0) or goes Home (taskId<=0).
    boolean restoreMainTask(int taskId) = 14;
    // Plan C Phase 2: hot-swaps VirtualDisplay output surface (ImageReader <-> TextureView).
    boolean setDisplaySurface(int displayId, in Surface surface) = 15;
    // Plan C Phase 2: low-latency streaming single MotionEvent injection into an owned virtual display.
    boolean injectMotionEvent(int displayId, int action, long downTime, long eventTime, float x, float y, float pressure) = 16;
}

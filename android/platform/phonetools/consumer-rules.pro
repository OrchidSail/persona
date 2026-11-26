# Privileged uid-2000 shell helper entry point invoked by class name via app_process.
-keep class persona.platform.phonetools.control.shell.PhoneControlShellMain {
    public static void main(java.lang.String[]);
}

# Shizuku UserService instantiated by class name via reflection in Shizuku process.
-keep class persona.platform.phonetools.control.shell.ShizukuPhoneControlService {
    public <init>();
    public <init>(android.content.Context);
}

# ContentProvider receiving uid-2000 helper Binder handoff.
-keep class persona.platform.phonetools.control.shell.PhoneControlHandoffProvider {
    public <init>();
}

# AIDL Binder interface and Stub/Proxy used across process boundary.
-keep class persona.platform.phonetools.control.shell.IPhoneControlShell** { *; }

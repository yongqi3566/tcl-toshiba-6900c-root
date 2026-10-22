import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.os.IBinder;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Set the HOME preferred activity on TCL 75U6900C (Android 6) at runtime.
 * The stock Resolver's "Always" writes always=false entries which
 * findPreferredActivity skips; replacePreferredActivity writes a real one.
 *
 * Build (JDK 17 + build-tools 34):
 *   javac --release 8 -cp android.jar -d out SetHome.java
 *   d8 --min-api 23 --lib android.jar --output out out/SetHome.class
 *   zip classes.dex into SetHome.jar
 *
 * Run as root on the TV:
 *   CLASSPATH=/data/local/tmp/SetHome.jar app_process /system/bin SetHome [--system] <package> <activity>
 */
public class SetHome {
    public static void main(String[] args) throws Exception {
        int ai = 0;
        boolean systemUid = args.length > 0 && args[0].equals("--system");
        if (systemUid) {
            Class<?> process = Class.forName("android.os.Process");
            process.getMethod("setUid", int.class).invoke(null, 1000);
            System.out.println("uid=" + process.getMethod("myUid").invoke(null));
            ai++;
        }
        if (args.length < ai + 2) {
            System.out.println("usage: SetHome [--system] <package> <activity>");
            return;
        }
        String pkg = args[ai];
        String cls = args[ai + 1];

        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) sm.getMethod("getService", String.class).invoke(null, "package");
        Class<?> stub = Class.forName("android.content.pm.IPackageManager$Stub");
        Object pm = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
        Class<?> iface = Class.forName("android.content.pm.IPackageManager");

        Intent q = new Intent(Intent.ACTION_MAIN);
        q.addCategory(Intent.CATEGORY_HOME);
        Method query = iface.getMethod("queryIntentActivities", Intent.class, String.class, int.class, int.class);
        List<?> ris = (List<?>) query.invoke(pm, q, null, 0, 0);
        int n = ris == null ? 0 : ris.size();
        ComponentName[] set = new ComponentName[n];
        System.out.println("HOME candidates: " + n);
        for (int i = 0; i < n; i++) {
            ResolveInfo ri = (ResolveInfo) ris.get(i);
            set[i] = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            int match = -1;
            try { match = ri.getClass().getField("match").getInt(ri); } catch (Throwable ignored) { }
            System.out.println("  " + set[i] + " match=0x" + Integer.toHexString(match));
        }
        IntentFilter filter = new IntentFilter(Intent.ACTION_MAIN);
        filter.addCategory(Intent.CATEGORY_HOME);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        ComponentName target = new ComponentName(pkg, cls);

        Method clear = iface.getMethod("clearPackagePreferredActivities", String.class);
        for (ComponentName c : set) {
            try { clear.invoke(pm, c.getPackageName()); }
            catch (Throwable t) { System.out.println("clear failed " + c.getPackageName() + ": " + t); }
        }
        // 0x100000 is the HOME preferred mMatch; the candidate query prints 0x108000,
        // which is NOT this argument.
        Method replace = iface.getMethod("replacePreferredActivity", IntentFilter.class, int.class,
                ComponentName[].class, ComponentName.class, int.class);
        replace.invoke(pm, filter, 0x100000, set, target, 0);
        System.out.println("replaced target=" + target + " setSize=" + set.length);
    }
}

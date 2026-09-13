package com.appgarage.dash;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.Environment;
import android.os.StatFs;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;

/** Read-only inventory of interfaces already exposed by the head unit. */
final class SystemProbe {
    static final class Entry {
        final String kind, name, detail;
        final boolean relevant;
        Entry(String k,String n,String d) {
            kind=k; name=n==null?"":n; detail=d==null?"":d;
            relevant=matches(name)||matches(detail);
        }
    }

    private final Context context;
    private final ArrayList<Entry> entries=new ArrayList<Entry>();
    private boolean collected;

    SystemProbe(Context c) { context=c.getApplicationContext(); }
    int size() { return entries.size(); }
    Entry get(int i) { return entries.get(i); }

    void collectIfNeeded() {
        if (collected) return;
        collected=true;
        collectBluetooth();
        collectNetwork();
        collectStorage();
        collectBinderServices();
        collectPackages();
        // Keep the bytecode simple for the old API-10 DEX toolchain: stable-partition
        // matches to the front without an anonymous Comparator class.
        ArrayList<Entry> ordered=new ArrayList<Entry>();
        for (Entry e:entries) if (e.relevant) ordered.add(e);
        for (Entry e:entries) if (!e.relevant) ordered.add(e);
        entries.clear();
        entries.addAll(ordered);
        if (entries.size()==0) entries.add(new Entry("INFO","No entries","Platform denied inventory"));
    }

    private void collectNetwork() {
        try {
            Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
            if (interfaces==null) {
                entries.add(new Entry("NET","Network interfaces","none"));
                return;
            }
            while (interfaces.hasMoreElements()) {
                NetworkInterface item=interfaces.nextElement();
                String addresses="";
                Enumeration<InetAddress> values=item.getInetAddresses();
                while (values.hasMoreElements()) {
                    if (addresses.length()>0) addresses=addresses+" ";
                    addresses=addresses+values.nextElement().getHostAddress();
                }
                entries.add(new Entry("NET",item.getName(),addresses.length()==0?"no IP":addresses));
            }
        } catch (Throwable t) {
            entries.add(new Entry("ERROR","Network probe",shortError(t)));
        }
    }

    private void collectStorage() {
        try {
            File external=Environment.getExternalStorageDirectory();
            entries.add(new Entry("STORAGE",external==null?"external":external.getAbsolutePath(),
                    Environment.getExternalStorageState()+fileAccess(external)+spaceInfo(external)));
        } catch (Throwable t) {
            entries.add(new Entry("ERROR","External storage",shortError(t)));
        }
        BufferedReader reader=null;
        try {
            reader=new BufferedReader(new FileReader("/proc/mounts"));
            String line;
            while ((line=reader.readLine())!=null) {
                String[] part=line.split("\\s+");
                if (part.length<4) continue;
                String mount=part[1], lower=mount.toLowerCase();
                if (lower.indexOf("usb")<0&&lower.indexOf("sdcard")<0&&lower.indexOf("media")<0
                        &&lower.indexOf("storage")<0&&lower.indexOf("mnt")<0) continue;
                File target=new File(mount);
                entries.add(new Entry("MOUNT",mount,fileAccess(target)+spaceInfo(target)+" "+part[2]+" "+part[3]));
            }
        } catch (Throwable t) {
            entries.add(new Entry("ERROR","Mount inventory",shortError(t)));
        } finally {
            if (reader!=null) try { reader.close(); } catch (Throwable ignored) {}
        }
    }

    private void collectBluetooth() {
        try {
            BluetoothAdapter adapter=BluetoothAdapter.getDefaultAdapter();
            if (adapter==null) {
                entries.add(new Entry("BT","BluetoothAdapter","not available to App Garage"));
            } else {
                entries.add(new Entry("BT","BluetoothAdapter",adapter.isEnabled()?"enabled":"disabled"));
                Set<BluetoothDevice> bonded=adapter.getBondedDevices();
                if (bonded==null||bonded.size()==0) {
                    entries.add(new Entry("BT","Bonded devices","none visible"));
                } else for (BluetoothDevice device:bonded) {
                    String name=device.getName()==null?"(unnamed)":device.getName();
                    entries.add(new Entry("BT-PAIR",name,device.getAddress()));
                }
            }

            Intent share=new Intent(Intent.ACTION_SEND);
            share.setType("application/vnd.android.package-archive");
            List<ResolveInfo> handlers=context.getPackageManager().queryIntentActivities(share,0);
            if (handlers==null||handlers.size()==0) {
                entries.add(new Entry("BT-OPP","APK share handler","none"));
            } else {
                boolean foundOpp=false;
                for (ResolveInfo ri:handlers) {
                    ActivityInfo ai=ri.activityInfo;
                    String pkg=ai==null?"unknown":ai.packageName;
                    String activity=ai==null?"ACTION_SEND":ai.name;
                    boolean opp=isBluetoothName(pkg)||isBluetoothName(activity);
                    if (opp) foundOpp=true;
                    entries.add(new Entry(opp?"BT-OPP":"SHARE",pkg,activity));
                }
                if (!foundOpp) entries.add(new Entry("BT-OPP","Bluetooth APK handler","not identified"));
            }
        } catch (Throwable t) {
            entries.add(new Entry("ERROR","Bluetooth probe",shortError(t)));
        }
    }

    private void collectBinderServices() {
        try {
            Class<?> cls=Class.forName("android.os.ServiceManager");
            Method list=cls.getDeclaredMethod("listServices");
            Method get=cls.getDeclaredMethod("getService",String.class);
            list.setAccessible(true); get.setAccessible(true);
            String[] names=(String[])list.invoke(null);
            if (names!=null) for (String name:names) {
                String descriptor="";
                try {
                    IBinder binder=(IBinder)get.invoke(null,name);
                    if (binder!=null) descriptor=binder.getInterfaceDescriptor();
                } catch (Throwable t) { descriptor="descriptor denied"; }
                entries.add(new Entry("BINDER",name,descriptor));
            }
        } catch (Throwable t) {
            entries.add(new Entry("ERROR","Binder inventory",shortError(t)));
        }
    }

    private void collectPackages() {
        try {
            PackageManager pm=context.getPackageManager();
            int flags=PackageManager.GET_PERMISSIONS|PackageManager.GET_SERVICES
                    |PackageManager.GET_PROVIDERS|PackageManager.GET_RECEIVERS;
            List<PackageInfo> packages=pm.getInstalledPackages(flags);
            if (packages==null) return;
            for (PackageInfo pi:packages) {
                String source=pi.applicationInfo==null?"":pi.applicationInfo.sourceDir;
                File apk=source.length()==0?null:new File(source);
                String apkDetail=source;
                if (apk!=null) apkDetail=apkDetail+" "+apk.length()+"B"+fileAccess(apk);
                entries.add(new Entry("PKG",pi.packageName,apkDetail));
                boolean packageRelevant=matches(pi.packageName);
                if (pi.services!=null) for (ServiceInfo s:pi.services)
                    if (packageRelevant||matches(s.name)||matches(s.permission))
                        entries.add(new Entry("SERVICE",s.name,s.permission));
                if (pi.providers!=null) for (ProviderInfo p:pi.providers)
                    if (packageRelevant||matches(p.name)||matches(p.authority))
                        entries.add(new Entry("PROVIDER",p.name,p.authority));
                if (pi.receivers!=null) for (ActivityInfo r:pi.receivers)
                    if (packageRelevant||matches(r.name)||matches(r.permission))
                        entries.add(new Entry("RECEIVER",r.name,r.permission));
                if (pi.requestedPermissions!=null) for (String permission:pi.requestedPermissions)
                    if (packageRelevant||matches(permission))
                        entries.add(new Entry("PERM",pi.packageName,permission));
            }
        } catch (Throwable t) {
            entries.add(new Entry("ERROR","Package inventory",shortError(t)));
        }
    }

    private static boolean matches(String value) {
        if (value==null) return false;
        String s=value.toLowerCase();
        String[] words={"ygomi","ivi","vehicle","can","energy","hybrid","nissan",
                "infiniti","drive","meter","cluster","eco","bluetooth","opp","bt-pair","bt-opp",
                "network","bnep","pan","wlan","usb","sdcard","storage","mount"};
        for (String word:words) if (s.indexOf(word)>=0) return true;
        return false;
    }
    private static boolean isBluetoothName(String value) {
        if (value==null) return false;
        String s=value.toLowerCase();
        return s.indexOf("bluetooth")>=0||s.indexOf("opp")>=0;
    }
    private static String fileAccess(File file) {
        if (file==null) return " R=no W=no";
        return " R="+(file.canRead()?"yes":"no")+" W="+(file.canWrite()?"yes":"no");
    }
    private static String spaceInfo(File file) {
        if (file==null||!file.exists()) return "";
        try {
            StatFs stat=new StatFs(file.getAbsolutePath());
            long blockSize=(long)stat.getBlockSize();
            long total=blockSize*(long)stat.getBlockCount();
            long free=blockSize*(long)stat.getAvailableBlocks();
            return " free="+formatBytes(free)+" total="+formatBytes(total);
        } catch (Throwable ignored) {
            return "";
        }
    }
    private static String formatBytes(long bytes) {
        long mb=bytes/(1024L*1024L);
        if (mb>=1024L) return (mb/1024L)+"."+((mb%1024L)*10L/1024L)+"GB";
        return mb+"MB";
    }
    private static String shortError(Throwable t) {
        Throwable cause=t.getCause()==null?t:t.getCause();
        return cause.getClass().getSimpleName()+": "+String.valueOf(cause.getMessage());
    }
}

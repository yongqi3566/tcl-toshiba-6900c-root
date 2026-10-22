---
name: tclroot
description: 在 TCL 75U6900C（中国版 Android 6.0，MStar，ro.product.device=tsbU9800C）上临时获取 root、可选固化 setuid su、安装第三方桌面并设为默认/开机 Home。Use when the user asks to root this TV, install a third-party launcher, or change the default/boot Home on a TCL 75U6900C / tsbU9800C.
---

# TCL 75U6900C：root / 第三方桌面 / 默认 Home

可执行操作手册，所有步骤都在 75U6900C 真机上验证过。只用于你自己的电视；
Dirty COW 有小概率导致电视重启，写 `/system` 有变砖风险。

**已验证的固件版本**（只是真机验证环境，不是硬性门槛）

| 项 | 值 |
|---|---|
| 机型 | TCL 75U6900C（`ro.product.device=tsbU9800C`，MStar；同系列其他尺寸 6900C 应同样适用） |
| Android | 6.0（API 23） |
| 内核 | 3.10.86 |
| 安全补丁级别 | `ro.build.version.security_patch` = 2016-05-01 |
| 完整 build | `aosp_tsbU9800C-userdebug 6.0 MRA58K TVOS.04.15.010.03.08 dev-keys` |
| SELinux | Disabled |

**不强制要求 tsbU9800C**。换机型看第 0 节预检的实际条件：SELinux 关闭、setuid `procmem`
存在、内核未修复 Dirty COW（跑 PoC 验证）、arm64。TCL Android 6 电视基本都符合。

**执行顺序**

1. 预检 + 备份，并在第一次 COW 前拉取原版 `procmem`（第 0 节）。
2. 临时 root（第 1 节）——只做一次安装的话到这里就够了。
3. 需要以后免 COW 再用 root 时，固化 su（第 2 节，可选）。
4. 把桌面 APK 放进 `/data/app` 并重启（第 3 节）。
5. `persist.sys.bootpackage` + `SetHome` + 重启（第 4 节）。
6. 验证；换桌面/回滚（第 5、6 节）。

固化 root、卸载/改名已有桌面都会改变电视的启动行为，做之前先向用户确认。

**环境变量**

```bash
# 以下命令默认在包含 scripts/ 的工作目录执行
export MSYS_NO_PATHCONV=1      # Git Bash 必需，防止 /data/... 被改写成 Windows 路径
export TV=<TV_IP>:5555         # 换成你的电视 IP
export NDK=/path/to/android-ndk   # 验证用 NDK 28.2；本 skill 只需 NDK + JDK17 + build-tools 34
adb connect "$TV"
```

## 0. 预检与备份（必须先做）

预检看的是**利用条件**，不是具体机型。tsbU9800C 只是验证过的机型，其他机型满足同样条件
即可尝试：

```bash
adb -s "$TV" shell 'uname -m; getprop ro.build.version.sdk; getprop ro.build.version.security_patch; getenforce'
# 关注：arm64（aarch64）、Android 5-7.x、安全补丁级别早于 2016-10（COW 大概率未修复）、
#       SELinux Disabled 或 Permissive

adb -s "$TV" shell 'ls -l /system/xbin/procmem /system/xbin/librank /system/xbin/procrank'
# 预期至少一个 setuid root（-rwsr-xr-x root root）
```

关键条件（不满足就停止，这套方法依赖它们）：

1. 内核存在 Dirty COW——版本号只是参考，最终以第 1 节实际跑 PoC 成功为准；
2. SELinux Disabled 或 Permissive；
3. 有 setuid root 的 `procmem`（或 `librank`/`procrank`）作覆写目标。

**在第一次 Dirty COW 之前**把原版 `procmem` 拉到 PC。COW 只改页缓存，之后读到的
`procmem` 已经是 payload，不再是原文件：

```bash
mkdir -p work/backup
adb -s "$TV" pull /system/xbin/procmem work/backup/procmem.orig
md5sum work/backup/procmem.orig
# 该固件为 5e53308db5a02f1abc555ea15ceac4c6；不同就记下你自己的值
```

其余备份：

```bash
adb -s "$TV" shell pm list packages -f         > work/backup/packages.txt
adb -s "$TV" shell getprop                     > work/backup/getprop.txt
adb -s "$TV" shell settings list system        > work/backup/settings-system.txt
adb -s "$TV" shell settings list secure        > work/backup/settings-secure.txt
adb -s "$TV" shell dumpsys package             > work/backup/dumpsys-package.txt
adb -s "$TV" shell dumpsys activity activities > work/backup/dumpsys-activity.txt
```

要卸载任何包之前先 `pm path` + `adb pull` + `md5sum` 备份 APK。**不要用
`pm uninstall -k --user 0`**：该固件返回 Success 却留下 installed=true 无 APK 的坏状态。

## 1. 临时 root（Dirty COW / CVE-2016-5195）

上游 PoC：<https://github.com/timwr/CVE-2016-5195>。取上游 `dirtycow.c`（主逻辑）与
`dcow.c`（辅助函数），把 `dirtycow.c` 里的
`if (canwritetoselfmem(mem_arg) == -1)` 改成 `if (1)`，强制走 ptrace 写入
（比 `/proc/self/mem` 分支稳定）。NDK 编 arm64：

```bash
CC=$NDK/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android23-clang
$CC -O2 -DDEBUG -o dirtycow_ptrace dirtycow.c dcow.c -llog -pthread
$CC -O2 -o rootsh2 scripts/rootsh2.c
```

**Windows Defender 会在写入瞬间删除 `dirtycow_ptrace` ELF**（改名 `.bin`、换目录、
甚至解码到 `%TEMP%` 都拦）。对策：PC 上只保留 base64，在电视上解码，PC 不落地 ELF：

```bash
base64 dirtycow_ptrace > dirtycow_ptrace.b64
adb -s "$TV" push dirtycow_ptrace.b64 /data/local/tmp/dcow.b64
adb -s "$TV" shell 'toybox base64 -d /data/local/tmp/dcow.b64 > /data/local/tmp/dcow_ptrace; chmod 755 /data/local/tmp/dcow_ptrace'
```

执行覆写并验证：

```bash
adb -s "$TV" push scripts/rootsh2 /data/local/tmp/rootsh2   # 或按 scripts/rootsh2.c 现编
adb -s "$TV" shell 'chmod 755 /data/local/tmp/rootsh2'
adb -s "$TV" shell '/data/local/tmp/dcow_ptrace /data/local/tmp/rootsh2 /system/xbin/procmem --no-pad'
adb -s "$TV" shell "echo 'id; getenforce' | /system/xbin/procmem"
# 预期 uid=0(root)；SELinux Disabled
```

- `/data/local/tmp` 每次重启被清空，root 工具每次开机要重新 push。
- COW 有小概率让电视重启；网络 ADB 重启后会断开，先 `adb connect "$TV"` 再继续，
  然后重新 push、重跑。
- 之后所有 root 操作：`adb -s "$TV" shell "echo '命令' | /system/xbin/procmem"`。
- 只写 `/data`（包括第 3 节）时 `sync` + `reboot` 安全；改 `/system` 必须用第 2 节模板。
- 临时 root 用完 `reboot` 即消失。

## 2. 固化 root（可选）：setuid `/system/xbin/su`

目标：`/system/xbin/su` 为 **4750 root:shell**，重启后仍在。ADB shell（shell 组）即可
`su -c`，不必每次跑 COW。

> 安全提示：4750 + shell 组 = **ADB 打开时等价于永久 root**。不用时关闭网络调试，
> 或 `chmod 0750`/删除 su。

```bash
CC=$NDK/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android23-clang
$CC -O2 -static -o su scripts/su.c     # -static 失败就去掉，动态版能用，只是有 linker 警告
adb -s "$TV" push su /data/local/tmp/su
adb -s "$TV" push work/backup/procmem.orig /data/local/tmp/procmem.orig
adb -s "$TV" shell 'chmod 755 /data/local/tmp/su; md5sum /data/local/tmp/procmem.orig'
```

**安全写 `/system` 模板（顺序不能变）：** Dirty COW 改的是 `procmem` 的页缓存；`sync`
前没把原文件写回去，`mount -o rw,remount /system` + `sync` 会把脏页回写系统分区，
触发块层保护复位/重启。任何 `/system` 写入都在同一条 root 管道里完成
"修改 → 恢复 procmem → 校验 md5 → sync"：

```bash
adb -s "$TV" shell "echo '\
mount -o rw,remount /system; \
cp /data/local/tmp/su /system/xbin/su; \
chown root:shell /system/xbin/su; \
chmod 4750 /system/xbin/su; \
ls -l /system/xbin/su; \
cp /data/local/tmp/procmem.orig /system/xbin/procmem; \
md5sum /system/xbin/procmem; \
sync' | /system/xbin/procmem"
```

必须检查：

- `ls -l` 输出 `-rwsr-x--- root shell ... /system/xbin/su`
- `md5sum /system/xbin/procmem` 等于原值（本固件 `5e53308db5a02f1abc555ea15ceac4c6`）
- 命令返回后**等 30-60 秒**确认没有复位，再 `adb -s "$TV" reboot`

`chown` 会清 setuid，顺序必须 **chown → chmod**。如果覆写过 `librank`，
同样先恢复它的原文件再 `sync`。新增文件（如 su）不在保护基线内，通常安全；
但模板仍要恢复 procmem 再 sync。

重启后验证：

```bash
adb -s "$TV" shell 'ls -l /system/xbin/su'      # -rwsr-x--- root shell
adb -s "$TV" shell '/system/xbin/su -c id'      # uid=0(root)
```

取消固化：

```bash
adb -s "$TV" shell "echo '\
mount -o rw,remount /system; \
rm -f /system/xbin/su; \
cp /data/local/tmp/procmem.orig /system/xbin/procmem; \
md5sum /system/xbin/procmem; \
sync' | /system/xbin/procmem"
adb -s "$TV" reboot
```

## 3. 安装第三方桌面：`/data/app` 开机扫描

先在真机上试一次普通 `adb install Launcher.apk`：不拦 HOME 包的固件直接就装上了，不需要
本节。只有出现下面的 TCL 拦截时才走 `/data/app` 路线：

**为什么普通安装不行**：声明 `MAIN`+`HOME` 的 APK 一律被 TCL 安装器拒绝：

```
INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES:
disable install launcher app not signature by tcl
```

`adb install` / `pm install` / PackageInstaller 会话一样，uid 0 也拦。
`setprop persist.tcl.debug.installapk 1` + `persist.tcl.installapk.enable 1` 只放开
**非 HOME** 包。可行路线：root 把 APK 放进 `/data/app/<包名>-1/base.apk`，开机扫描安装，
不经过 HOME 校验。

取包名和 MainActivity：

```bash
aapt dump badging Launcher.apk | grep -E "^package:|launchable-activity"
pkg=app.example.launcher          # 按输出填
cls=app.example.launcher.MainActivity
```

写入（有持久 su 时；没有就换成 `echo '...' | /system/xbin/procmem`）：

```bash
adb -s "$TV" push Launcher.apk /data/local/tmp/launcher.apk
adb -s "$TV" shell "su -c '
  mkdir -p /data/app/$pkg-1;
  cp /data/local/tmp/launcher.apk /data/app/$pkg-1/base.apk;
  chown 1000:1000 /data/app/$pkg-1 /data/app/$pkg-1/base.apk;
  chmod 755 /data/app/$pkg-1;
  chmod 644 /data/app/$pkg-1/base.apk;
  sync'"
adb -s "$TV" reboot
# 重启后网络 ADB 会断开：adb connect "$TV" 重新连上再验证
```

要求：目录名 `<pkg>-1`、文件名 `base.apk`、owner `1000:1000`、目录 755、APK 644。
包已存在时要么 `pm uninstall <pkg>`（丢数据），要么替换原目录里的 `base.apk`；
不要让同一包出现两个目录。

验证：

```bash
adb -s "$TV" shell "pm path $pkg"         # package:/data/app/<pkg>-1/base.apk
adb -s "$TV" shell "am start -n $pkg/$cls"
```

`pm path` 为空就查目录名/文件名/owner/权限；不要回头试 `pm install`。

## 4. 设为默认 Home + 开机桌面

两层配置缺一不可：

1. `persist.sys.bootpackage = <pkg>`：TCL 开机进哪个桌面。
2. Android framework 的 HOME preferred：Home 键和 generic HOME 走哪个 Activity。

```bash
adb -s "$TV" shell "su -c 'setprop persist.sys.bootpackage $pkg'"
adb -s "$TV" shell getprop persist.sys.bootpackage
```

### 4.1 设置 HOME preferred（SetHome）

标准 Resolver 的"始终"在该固件上只写 `always=false` 的 last-chosen 记录，
`findPreferredActivity` 会跳过；手改 `package-restrictions.xml` 会被 system_server
内存旧值写回。必须运行时调 `replacePreferredActivity`。源码在 `scripts/SetHome.java`
（全部反射，`IPackageManager` 是 hidden 类）。

编译（JDK 17 + build-tools 34；Windows 用 `d8.bat`）：

```bash
mkdir -p out
javac --release 8 -cp "$SDK/platforms/android-34/android.jar" -d out scripts/SetHome.java
"$SDK/build-tools/34.0.0/d8.bat" --min-api 23 --lib "$SDK/platforms/android-34/android.jar" \
    --output out out/SetHome.class
python -c "import zipfile; z=zipfile.ZipFile('out/SetHome.jar','w',zipfile.ZIP_DEFLATED); z.write('out/classes.dex','classes.dex'); z.close()"
```

运行：

```bash
adb -s "$TV" push out/SetHome.jar /data/local/tmp/SetHome.jar
adb -s "$TV" shell "su -c 'CLASSPATH=/data/local/tmp/SetHome.jar app_process /system/bin SetHome $pkg $cls'"
# binder 报 SecurityException 时加 --system（先 setUid(1000)）：
adb -s "$TV" shell "su -c 'CLASSPATH=/data/local/tmp/SetHome.jar app_process /system/bin SetHome --system $pkg $cls'"
```

`SetHome` 做的事：查 `MAIN+HOME` 全部候选 → 逐个 `clearPackagePreferredActivities` →
`replacePreferredActivity(MAIN+HOME+DEFAULT, 0x100000, 候选集, 目标, 0)`。
**第二个参数必须是 0x100000**；候选 query 打印的 `match=0x108000` 不是这个参数。

等 30 秒让 `scheduleWritePackageRestrictions` 落盘，重启。

### 4.2 有别的桌面在抢 Home

部分桌面（如当贝 4.1.7 的 `HomeKeyEventCatchService`）会截 Home 键，开机时自我恢复并把
`persist.sys.bootpackage` 改回自己。`pm disable-user` 开机被还原，`am force-stop` 只对
本次开机有效。要换走它，**卸载或改名它的 APK**：

```bash
# 路径以 pm path 为准（本机是 -1）
adb -s "$TV" shell "su -c 'mv /data/app/com.dangbei.tvlauncher-1/base.apk /data/app/com.dangbei.tvlauncher-1/base.apk.bak'"
adb -s "$TV" shell "su -c 'setprop persist.sys.bootpackage $pkg'"
# 重跑 4.1 的 SetHome，然后 reboot
```

改名后数据目录和 dock 配置保留；恢复就是把 `.bak` 改回 `base.apk` + 设回 bootpackage +
重跑 SetHome + 重启。若改回后 `pm path` 仍为空，按第 3 节重装。

## 5. 验证清单

```bash
adb -s "$TV" shell 'getprop persist.sys.bootpackage'        # = <pkg>
adb -s "$TV" shell "pm path $pkg"                           # /data/app/<pkg>-1/base.apk
adb -s "$TV" shell dumpsys activity activities | grep -m1 mResumedActivity   # <pkg>/<cls>
adb -s "$TV" shell dumpsys activity | grep -m1 mHomeProcess                  # <pkg>
adb -s "$TV" shell "dumpsys package | grep -A40 'Preferred Activities User 0:'" \
  | grep -A8 'android.intent.action.MAIN'                   # mMatch=0x100000 mAlways=true
adb -s "$TV" shell input keyevent 3
adb -s "$TV" shell dumpsys activity activities | grep -m1 mResumedActivity   # 仍是 <pkg>/<cls>
```

全对的标准：bootpackage、pm path、mHomeProcess、mResumedActivity、preferred 五处指向
同一个包；preferred 为 `mMatch=0x100000 mAlways=true`。

## 6. 回滚

1. **换回原桌面**：`su -c 'setprop persist.sys.bootpackage com.tcl.cyberui'` +
   `SetHome com.tcl.cyberui com.tcl.cyberui.MainActivity` + 重启（CyberUI 一直安装着）。
2. **换回被改名的桌面**：见 4.2 的恢复。
3. **删除 su**：见第 2 节"取消固化"。
4. **关闭 ADB**：设置 → 系统 → 开发者选项 → 关闭网络调试 → 撤销授权
   （4750 的 su + ADB 打开 = 局域网内可 root）。

## 7. 不要重复的失败尝试

| 尝试 | 结果 |
|---|---|
| `adb install` / `pm install` 装 HOME 包 | `INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES ... not signature by tcl`，uid 0 也一样 |
| `pm uninstall -k --user 0` | 假 Success，留下 installed=true 无 APK |
| `pm disable-user` / `pm disable` / `pm hide` | shell 无权限；`pm hide com.tcl.cyberui` 能持久但导致"Android系统已停止运行" |
| 标准 Resolver 选"始终" | 写入 `always=false` 的 last-chosen，`findPreferredActivity` 跳过 |
| 手改 `package-restrictions.xml` | 被 system_server 内存旧值写回；硬重启后 true/false 并存 |
| 只 force-stop 抢 Home 的桌面 | 只对本次开机有效，重启被抢回 |
| 没恢复 procmem 就 remount+sync `/system` | 脏页回写触发保护复位/重启；必须用第 2 节模板 |
| Janus（CVE-2017-13156）绕过签名 | 能装但启动崩溃/重启 |
| `persist.tcl.debug.installapk` 两个属性装 HOME 包 | 只放开普通 APK，HOME 包仍被签名校验拒 |

## 8. scripts/

| 文件 | 用途 |
|---|---|
| `scripts/rootsh2.c` | Dirty COW payload：setuid 后起 root shell |
| `scripts/su.c` | 固化用的 setuid root shell（装为 4750 root:shell） |
| `scripts/SetHome.java` | 运行时设置 HOME preferred（接收包名 + Activity） |

Dirty COW 本体不随本 skill 分发，取上游
<https://github.com/timwr/CVE-2016-5195> 并按第 1 节打一行补丁。

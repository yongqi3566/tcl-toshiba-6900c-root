# TCL 75U6900C(6900c系列)：临时 root / 固化 root / 安装第三方桌面 / 设为默认桌面

> **只用于你自己的电视。** Dirty COW 有小概率导致电视重启；写 `/system` 分区有变砖风险。
> 操作前先备份，确认电视没有正在进行的系统更新。本教程不涉及付费内容绕过，仅恢复用户对自己
> 设备的控制权。

## 适用范围

**真机验证过的环境**（下表只是"在这台上跑通了"，不是硬性门槛）：

| 项 | 值 | 如何确认 |
|---|---|---|
| 机型 | TCL 75U6900C（中国版，`tsbU9800C`；同系列其他尺寸的 6900C 应同样适用） | `getprop ro.product.device` = `tsbU9800C` |
| Android | 6.0（API 23） | `getprop ro.build.version.sdk` = `23` |
| 内核 | 3.10.86 | `uname -r` |
| 安全补丁级别 | 2016-05-01 | `getprop ro.build.version.security_patch` |
| 完整 build | `aosp_tsbU9800C-userdebug 6.0 MRA58K TVOS.04.15.010.03.08 dev-keys` | `getprop ro.build.fingerprint` |
| SELinux | Disabled | `getenforce` |

**不强制要求 tsbU9800C。** 真正的门槛是下面这些条件，满足就能试（TCL 的 Android 6 电视
基本都符合；其他品牌满足条件 1–4 也可以试，只是第 5 步的绕行路线可能不需要）：

1. 内核存在 Dirty COW（CVE-2016-5195）——版本号只能参考（3.10.x 且安全补丁级别早于
   2016-10 大概率未修复），最终以实际跑 PoC 成功为准；
2. SELinux 为 Disabled 或 Permissive；
3. `/system/xbin/` 下有 setuid root 的 `procmem`（或 `librank`、`procrank`）可作覆写目标；
4. arm64 架构（本教程编译目标是 aarch64；arm32 需自行换工具链）；
5. Android 5–7.x：`/data/app/<包名>-1/base.apk` 开机扫描和
   `IPackageManager.replacePreferredActivity` 的方法签名在这个区间验证过，其他版本参数
   可能不同；
6. 第 3 步的"/data/app 安装"是为了绕过 TCL 的 HOME 签名校验。**不拦 HOME 包的固件直接
   `adb install` 就行**——先试普通安装，被拦了再用这条路线。

文档里的"预检"就是按这些条件写的，按预检结果决定是否继续。

## 目录

| 路径 | 内容 | 适合谁 |
|---|---|---|
| [`prompt/PROMPT.md`](prompt/PROMPT.md) | **思路版**：一份可以直接发给 AI 编码代理（Claude Code 等）的完整 Prompt，包含全部已验证的技术路线、坑点和验证命令，代理会按它逐步执行并适配你的电视 | 想让 AI 帮自己操作的人 |
| [`skill/SKILL.md`](skill/SKILL.md) | **Skill 版**：Claude Code 项目技能 `tclroot`（带 frontmatter），放进仓库的 `.claude/skills/tclroot/` 后，AI 会自动发现并按手册执行；附带可直接编译的 `scripts/`（SetHome.java、su.c、rootsh2.c） | 想沉淀成可复用技能的人 |

两种内容同一套方法，选一种用即可。

## 方法一句话总结

1. **临时 root**：Dirty COW（CVE-2016-5195，ptrace 分支）覆写 setuid 的
   `/system/xbin/procmem`，执行它得到 uid 0 shell。
2. **固化 root**：借这次 root，把静态编译的 `su` 写进 `/system/xbin/su`（4750 root:shell）。
   关键安全模板：`sync` 之前必须把被 COW 覆写的 `procmem` 恢复原样，否则脏页回写触发保护重启。
3. **装第三方桌面**：TCL 安装器拒绝一切声明 HOME 的第三方 APK（uid 0 也拦），但 Android 6
   开机会扫描 `/data/app/<包名>-1/base.apk`，root 把 APK 放进去重启即装，绕过 HOME 校验。
4. **设为默认**：`persist.sys.bootpackage=<包名>`（开机桌面）+ 运行时
   `IPackageManager.replacePreferredActivity`（Home 键路由，`mMatch=0x100000`）。
   系统 Resolver 的"始终"在这台固件上写入的是 `always=false`，无效。

## 本教程的验证环境

- 上表全部条件满足的真机
- ADB 网络调试（5555），Windows + Git Bash（`MSYS_NO_PATHCONV=1`）
- Android NDK 28.2（编译 Dirty COW / su）、JDK 17 + build-tools 34（编译 SetHome）
- 全部步骤在真机上验证过，含多次重启和回滚

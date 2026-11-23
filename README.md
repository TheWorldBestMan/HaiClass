# 海课 HaiClass

海课是面向广东海洋大学学生的 Android 课表工具。它把课程起止时间、节次、教室、课前天气和校园跑进度放在手机上，也可以从桌面直接查看。

[下载 Android APK](https://github.com/TheWorldBestMan/HaiClass/releases/latest) · [查看逐版更新记录](CHANGELOG.md)

## 安装与使用

1. 从 Releases 下载对应版本的海课 APK，在 Android 8.0 或更高版本的手机上安装。这是调试签名的可安装版本。
2. 打开 App 即可试用模拟课表。使用自己的课表时，从[学校教务系统](https://jw.gdou.edu.cn/)导出 PDF，再在 App 中点「导入 PDF」。导入和课表保存都在手机上完成。
3. 在「上课设置」修改教学周起始日和节次，按需开启上课提醒、下一堂课实时通知、明天课表通知、校园天气提醒及校园跑提醒。
4. 在「上课设置」添加 **4×3 七天课表**或 **4×1 校园跑**组件，也可以从手机桌面的小组件列表添加。校园跑组件右侧的进度区可直接打开累计次数编辑。

课表和提醒按北京时间计算。实时通知的显示样式取决于 Android 版本和手机系统。天气功能按所选校区请求天气预报。[Open-Meteo](https://open-meteo.com/)

## 从源码构建

使用 JDK 17 和 Android SDK Platform 36。用 Android Studio 打开仓库目录，或在已配置 Android SDK 的终端运行 Gradle Wrapper。

Windows PowerShell

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

macOS 和 Linux

```sh
./gradlew :app:assembleDebug :app:lintDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。
